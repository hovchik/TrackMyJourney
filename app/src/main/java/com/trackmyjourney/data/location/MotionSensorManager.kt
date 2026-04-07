package com.trackmyjourney.data.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * Monitors the phone's physical sensors to detect whether the device is actually
 * moving, independent of GPS.  When real motion is detected the service should
 * activate GPS; when the device is stationary GPS can be suspended to save battery.
 *
 * Sensors used:
 *  • Linear Accelerometer  – pure user-acceleration (gravity removed)
 *  • Gyroscope             – angular velocity
 *  • Step Detector          – individual step events  (requires ACTIVITY_RECOGNITION)
 *  • Step Counter           – cumulative step count   (requires ACTIVITY_RECOGNITION)
 *  • Magnetometer           – ambient magnetic field → compass heading
 *
 * Step counting strategy (three tiers):
 *  1. TYPE_STEP_DETECTOR   – fires per step → increments detectorSteps (primary)
 *  2. TYPE_STEP_COUNTER    – cumulative, batched → cross-validates / catches missed steps
 *  3. Accelerometer peaks  – fallback when hardware step sensors are unavailable
 *
 * When ACTIVITY_RECOGNITION permission is not granted, step sensors are skipped
 * and the fusion algorithm redistributes their weight to accelerometer and gyroscope.
 */
class MotionSensorManager(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "MotionSensorManager"

        // ── Thresholds ──
        private const val ACCEL_MOTION_THRESHOLD = 0.4f    // m/s²
        private const val GYRO_MOTION_THRESHOLD = 0.08f    // rad/s
        private const val SAMPLE_WINDOW = 20
        private const val MOTION_VOTE_THRESHOLD = 0.4f
        private const val STEP_COOLDOWN_MS = 5000L

        // ── Fusion weights (with step permission) ──
        // Steps are the strongest indicator of real locomotion — a step event
        // is unambiguous proof the user is walking/running, while accelerometer
        // and gyroscope can fire from hand gestures or vibrations.
        private const val WEIGHT_ACCEL_WITH_STEPS = 0.25f
        private const val WEIGHT_STEPS = 0.55f
        private const val WEIGHT_GYRO_WITH_STEPS = 0.20f

        // ── Fusion weights (without step permission) ──
        private const val WEIGHT_ACCEL_NO_STEPS = 0.70f
        private const val WEIGHT_GYRO_NO_STEPS = 0.30f

        // ── Dead reckoning ──
        private const val DEFAULT_STRIDE_METERS = 0.75f
        private const val RUNNING_STRIDE_METERS = 1.2f
        private const val RUNNING_ACCEL_THRESHOLD = 1.8f

        // ── GPS activation ──
        private const val GPS_NEEDED_CONFIDENCE = 0.35f
        private const val GPS_LINGER_MS = 8000L

        // ── Vehicle motion detection ──
        // When accelerometer shows sustained activity but NO steps are detected,
        // the user is likely in a vehicle.  A vehicle starting, accelerating, or
        // turning produces accelerometer readings well above the walking threshold
        // but no step events.  We use a separate, higher accel threshold plus a
        // "no recent steps" condition to flag vehicle motion and force GPS on.
        private const val VEHICLE_ACCEL_THRESHOLD = 0.6f   // m/s² average (lower than walking peak but sustained)
        private const val VEHICLE_ACCEL_VOTE_MIN = 0.5f    // ≥50% of samples must exceed threshold
        private const val NO_STEP_WINDOW_MS = 10_000L      // no steps in 10s → not on foot

        // ── Magnetometer low-pass ──
        private const val MAG_ALPHA = 0.15f

        // ── Accelerometer step detection (fallback) ──
        // Peak detection on linear acceleration magnitude to count steps
        // when no hardware step sensor is available.
        private const val ACCEL_STEP_THRESHOLD = 1.2f      // m/s² peak to count as step
        private const val ACCEL_STEP_MIN_INTERVAL_MS = 250L // fastest step ~4 steps/s
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _motionState = MutableStateFlow(MotionState())
    val motionState: StateFlow<MotionState> = _motionState.asStateFlow()

    // Ring buffers for smoothing
    private val accelSamples = FloatArray(SAMPLE_WINDOW)
    private val gyroSamples = FloatArray(SAMPLE_WINDOW)
    private var sampleIndex = 0
    private var samplesCollected = 0

    // ── Step tracking ──
    private var lastStepTimestamp = 0L
    /** Steps counted by TYPE_STEP_DETECTOR (fires per step, real-time). */
    private var detectorSteps = 0L
    /** Steps from TYPE_STEP_COUNTER (cumulative since reboot, batched delivery). */
    private var stepCounterBaseline: Float? = null
    private var counterSteps = 0L
    /** Whether hardware step sensors are present on this device. */
    private var hasHardwareStepSensor = false

    // ── Accelerometer step detection fallback ──
    private var accelSteps = 0L
    private var lastAccelStepTime = 0L
    private var prevAccelMagnitude = 0f
    private var accelRising = false

    // Magnetometer / heading
    private val gravityValues = FloatArray(3)
    private val magneticValues = FloatArray(3)
    private var hasGravity = false
    private var hasMagnetic = false
    private var currentHeadingDeg: Float = 0f

    // Dead reckoning
    private var displacementX = 0.0
    private var displacementY = 0.0
    private var totalDisplacement = 0.0

    // GPS activation linger
    private var lastMotionTimestamp = 0L

    private var isMonitoring = false
    private var hasStepPermission = false

    data class MotionState(
        val isDeviceMoving: Boolean = false,
        val accelerationMagnitude: Float = 0f,
        val rotationRate: Float = 0f,
        val stepDetected: Boolean = false,
        val motionConfidence: Float = 0f,
        val steps: Long = 0,
        val headingDeg: Float = 0f,
        val displacementMeters: Double = 0.0,
        val gpsNeeded: Boolean = false,
        val stepPermissionGranted: Boolean = false,
        /** True when accelerometer detects sustained motion without steps (likely vehicle). */
        val vehicleMotionDetected: Boolean = false
    )

    /**
     * Start monitoring device sensors.
     *
     * @param activityRecognitionGranted whether the ACTIVITY_RECOGNITION runtime
     *        permission has been granted.  When false, step detector and step
     *        counter sensors are not registered and their weight is redistributed
     *        to accelerometer and gyroscope in the fusion algorithm.
     */
    fun startMonitoring(activityRecognitionGranted: Boolean = true) {
        if (isMonitoring) return
        isMonitoring = true
        hasStepPermission = activityRecognitionGranted

        // Reset all state
        stepCounterBaseline = null
        detectorSteps = 0
        counterSteps = 0
        accelSteps = 0
        lastAccelStepTime = 0L
        prevAccelMagnitude = 0f
        accelRising = false
        displacementX = 0.0
        displacementY = 0.0
        totalDisplacement = 0.0
        hasGravity = false
        hasMagnetic = false
        currentHeadingDeg = 0f
        lastMotionTimestamp = 0L
        lastStepTimestamp = 0L
        hasHardwareStepSensor = false

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "Linear accelerometer registered")
        } ?: Log.w(TAG, "No linear accelerometer available")

        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "Gyroscope registered")
        } ?: Log.w(TAG, "No gyroscope available")

        if (activityRecognitionGranted) {
            stepDetector?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                hasHardwareStepSensor = true
                Log.i(TAG, "Step detector registered")
            } ?: Log.w(TAG, "No step detector sensor on this device")

            stepCounter?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                hasHardwareStepSensor = true
                Log.i(TAG, "Step counter registered")
            } ?: Log.w(TAG, "No step counter sensor on this device")

            if (!hasHardwareStepSensor) {
                Log.i(TAG, "No hardware step sensors — using accelerometer peak detection fallback")
            }
        } else {
            Log.w(TAG, "ACTIVITY_RECOGNITION not granted — step sensors skipped, " +
                    "fusion weights redistributed (accel=${WEIGHT_ACCEL_NO_STEPS}, gyro=${WEIGHT_GYRO_NO_STEPS})")
        }

        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            Log.i(TAG, "Magnetometer registered")
        } ?: Log.w(TAG, "No magnetometer available")

        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            Log.i(TAG, "Gravity (accelerometer) registered for orientation")
        } ?: Log.w(TAG, "No accelerometer available for gravity")
    }

    fun stopMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false
        sensorManager.unregisterListener(this)
        samplesCollected = 0
        sampleIndex = 0
        hasStepPermission = false
        _motionState.value = MotionState()
        Log.i(TAG, "Sensor monitoring stopped")
    }

    fun resetDisplacement() {
        displacementX = 0.0
        displacementY = 0.0
        totalDisplacement = 0.0
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val magnitude = sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                accelSamples[sampleIndex % SAMPLE_WINDOW] = magnitude
                sampleIndex++
                samplesCollected++

                // Accelerometer-based step detection fallback:
                // Detect peaks in acceleration magnitude when no hardware step sensor
                if (hasStepPermission && !hasHardwareStepSensor) {
                    detectAccelStep(magnitude)
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                val angularSpeed = sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                gyroSamples[sampleIndex % SAMPLE_WINDOW] = angularSpeed
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                detectorSteps++
                lastStepTimestamp = System.currentTimeMillis()
                onStepDetected()
                Log.d(TAG, "Step detected (#$detectorSteps)")
            }

            Sensor.TYPE_STEP_COUNTER -> {
                val raw = event.values[0]
                if (stepCounterBaseline == null) {
                    stepCounterBaseline = raw
                    Log.d(TAG, "Step counter baseline set: $raw")
                }
                counterSteps = (raw - (stepCounterBaseline ?: raw)).toLong().coerceAtLeast(0)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) {
                    gravityValues[i] = MAG_ALPHA * event.values[i] + (1 - MAG_ALPHA) * gravityValues[i]
                }
                hasGravity = true
                updateHeading()
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                for (i in 0..2) {
                    magneticValues[i] = MAG_ALPHA * event.values[i] + (1 - MAG_ALPHA) * magneticValues[i]
                }
                hasMagnetic = true
                updateHeading()
            }
        }

        // Update state periodically
        if (samplesCollected >= SAMPLE_WINDOW && sampleIndex % 5 == 0) {
            updateMotionState()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* unused */ }

    // ── Accelerometer step detection (fallback) ──

    /**
     * Simple peak detection on linear acceleration magnitude.
     * When the signal rises above [ACCEL_STEP_THRESHOLD] and then falls back
     * below, that counts as one step. A minimum interval prevents double-counting.
     */
    private fun detectAccelStep(magnitude: Float) {
        if (magnitude > prevAccelMagnitude) {
            accelRising = true
        } else if (accelRising && prevAccelMagnitude >= ACCEL_STEP_THRESHOLD) {
            // We were rising and just crossed a peak above the threshold
            val now = System.currentTimeMillis()
            if (now - lastAccelStepTime >= ACCEL_STEP_MIN_INTERVAL_MS) {
                accelSteps++
                lastAccelStepTime = now
                lastStepTimestamp = now
                onStepDetected()
            }
            accelRising = false
        } else {
            accelRising = false
        }
        prevAccelMagnitude = magnitude
    }

    // ── Heading computation ──

    private fun updateHeading() {
        if (!hasGravity || !hasMagnetic) return

        val rotationMatrix = FloatArray(9)
        val inclinationMatrix = FloatArray(9)

        if (!SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, gravityValues, magneticValues)) {
            return
        }

        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)

        val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        currentHeadingDeg = (azimuthDeg + 360f) % 360f
    }

    // ── Dead reckoning on step ──

    private fun onStepDetected() {
        val avgAccel = if (samplesCollected > 0) {
            val count = minOf(samplesCollected, SAMPLE_WINDOW)
            var sum = 0f
            for (i in 0 until count) sum += accelSamples[i]
            sum / count
        } else 0f

        val stride = if (avgAccel > RUNNING_ACCEL_THRESHOLD) RUNNING_STRIDE_METERS
                     else DEFAULT_STRIDE_METERS

        val headingRad = Math.toRadians(currentHeadingDeg.toDouble())
        displacementX += stride * sin(headingRad)
        displacementY += stride * cos(headingRad)
        totalDisplacement = sqrt(displacementX * displacementX + displacementY * displacementY)
    }

    // ── Best step count ──

    /**
     * Returns the best available step count:
     *  1. Hardware step detector count (most responsive, real-time)
     *  2. Hardware step counter (cumulative, may catch missed detector events)
     *  3. Accelerometer peak detection (fallback)
     *
     * Uses the maximum of detector and counter to handle devices where one
     * sensor delivers more reliably than the other.
     */
    private fun bestStepCount(): Long {
        if (!hasStepPermission) return 0
        return when {
            hasHardwareStepSensor -> maxOf(detectorSteps, counterSteps)
            else -> accelSteps
        }
    }

    // ── Motion state update ──

    private fun updateMotionState() {
        val count = minOf(samplesCollected, SAMPLE_WINDOW)
        if (count == 0) return

        var accelMotionVotes = 0
        var gyroMotionVotes = 0
        var vehicleAccelVotes = 0
        var accelSum = 0f
        var gyroSum = 0f

        for (i in 0 until count) {
            accelSum += accelSamples[i]
            gyroSum += gyroSamples[i]
            if (accelSamples[i] > ACCEL_MOTION_THRESHOLD) accelMotionVotes++
            if (accelSamples[i] > VEHICLE_ACCEL_THRESHOLD) vehicleAccelVotes++
            if (gyroSamples[i] > GYRO_MOTION_THRESHOLD) gyroMotionVotes++
        }

        val avgAccel = accelSum / count
        val avgGyro = gyroSum / count

        val accelVoteRatio = accelMotionVotes.toFloat() / count
        val gyroVoteRatio = gyroMotionVotes.toFloat() / count
        val vehicleVoteRatio = vehicleAccelVotes.toFloat() / count

        val now = System.currentTimeMillis()
        val recentStep = hasStepPermission && (now - lastStepTimestamp) < STEP_COOLDOWN_MS

        // ── Vehicle motion detection ──
        // Sustained accelerometer activity with NO recent steps strongly suggests
        // the device is in a moving vehicle (car, bus, train).  In this case we
        // must activate GPS even though the step-heavy fusion score is low.
        val noRecentSteps = !recentStep && (lastStepTimestamp == 0L || (now - lastStepTimestamp) > NO_STEP_WINDOW_MS)
        val vehicleMotion = vehicleVoteRatio >= VEHICLE_ACCEL_VOTE_MIN && noRecentSteps

        val accelConfidence = (accelVoteRatio / MOTION_VOTE_THRESHOLD).coerceIn(0f, 1f)
        val gyroConfidence = (gyroVoteRatio / MOTION_VOTE_THRESHOLD).coerceIn(0f, 1f)

        val motionConfidence = if (hasStepPermission) {
            if (vehicleMotion) {
                // Vehicle mode: ignore step weight, redistribute to accel/gyro
                // so that vehicle vibrations reliably cross the threshold
                (accelConfidence * WEIGHT_ACCEL_NO_STEPS +
                 gyroConfidence * WEIGHT_GYRO_NO_STEPS)
            } else {
                val stepConfidence = if (recentStep) 1f else 0f
                (accelConfidence * WEIGHT_ACCEL_WITH_STEPS +
                 stepConfidence * WEIGHT_STEPS +
                 gyroConfidence * WEIGHT_GYRO_WITH_STEPS)
            }
        } else {
            (accelConfidence * WEIGHT_ACCEL_NO_STEPS +
             gyroConfidence * WEIGHT_GYRO_NO_STEPS)
        }.coerceIn(0f, 1f)

        // GPS is needed if fusion says moving OR vehicle motion is detected
        val isMoving = motionConfidence > GPS_NEEDED_CONFIDENCE || vehicleMotion

        if (isMoving) {
            lastMotionTimestamp = now
        }

        val gpsNeeded = isMoving ||
                (lastMotionTimestamp > 0L && (now - lastMotionTimestamp) < GPS_LINGER_MS)

        _motionState.value = MotionState(
            isDeviceMoving = isMoving,
            accelerationMagnitude = avgAccel,
            rotationRate = avgGyro,
            stepDetected = recentStep,
            motionConfidence = motionConfidence,
            steps = bestStepCount(),
            headingDeg = currentHeadingDeg,
            displacementMeters = totalDisplacement,
            gpsNeeded = gpsNeeded,
            stepPermissionGranted = hasStepPermission,
            vehicleMotionDetected = vehicleMotion
        )
    }
}
