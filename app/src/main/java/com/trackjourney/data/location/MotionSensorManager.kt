package com.trackjourney.data.location

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
 * When [ACTIVITY_RECOGNITION] permission is not granted, step sensors are skipped
 * and the fusion algorithm redistributes their weight to accelerometer and gyroscope.
 *
 * Outputs (via [motionState]):
 *  • isDeviceMoving / motionConfidence
 *  • step count since monitoring started (0 when permission denied)
 *  • magnetic heading (degrees, 0 = north, clockwise)
 *  • approximate displacement (meters) from dead reckoning (steps × stride × heading)
 *  • gpsNeeded – true when sensors indicate real locomotion that warrants a GPS fix
 *  • stepPermissionGranted – whether step sensors are active
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
        private const val WEIGHT_ACCEL_WITH_STEPS = 0.50f
        private const val WEIGHT_STEPS = 0.30f
        private const val WEIGHT_GYRO_WITH_STEPS = 0.20f

        // ── Fusion weights (without step permission) ──
        // Steps weight is redistributed: accel gets +20%, gyro gets +10%
        private const val WEIGHT_ACCEL_NO_STEPS = 0.70f
        private const val WEIGHT_GYRO_NO_STEPS = 0.30f

        // ── Dead reckoning ──
        private const val DEFAULT_STRIDE_METERS = 0.75f
        private const val RUNNING_STRIDE_METERS = 1.2f
        private const val RUNNING_ACCEL_THRESHOLD = 1.8f

        // ── GPS activation ──
        private const val GPS_NEEDED_CONFIDENCE = 0.35f
        private const val GPS_LINGER_MS = 8000L

        // ── Magnetometer low-pass ──
        private const val MAG_ALPHA = 0.15f
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

    // Step tracking
    private var lastStepTimestamp = 0L
    private var stepCounterBaseline: Float? = null
    private var totalSteps = 0L

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

    /** Whether ACTIVITY_RECOGNITION permission was granted for this monitoring session. */
    private var hasStepPermission = false

    data class MotionState(
        /** True if sensors indicate the device is physically moving */
        val isDeviceMoving: Boolean = false,
        /** Smoothed linear acceleration magnitude (m/s²) */
        val accelerationMagnitude: Float = 0f,
        /** Smoothed angular velocity (rad/s) */
        val rotationRate: Float = 0f,
        /** True if step detector fired recently */
        val stepDetected: Boolean = false,
        /** Confidence 0..1 that the device is truly in motion */
        val motionConfidence: Float = 0f,
        /** Total steps detected since monitoring started (0 when permission denied) */
        val steps: Long = 0,
        /** Compass heading in degrees (0 = magnetic north, clockwise) */
        val headingDeg: Float = 0f,
        /** Approximate displacement in meters from dead reckoning */
        val displacementMeters: Double = 0.0,
        /** True when real motion warrants activating GPS */
        val gpsNeeded: Boolean = false,
        /** Whether step counter/detector sensors are active (permission granted) */
        val stepPermissionGranted: Boolean = false
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

        // Reset dead reckoning state
        stepCounterBaseline = null
        totalSteps = 0
        displacementX = 0.0
        displacementY = 0.0
        totalDisplacement = 0.0
        hasGravity = false
        hasMagnetic = false
        currentHeadingDeg = 0f
        lastMotionTimestamp = 0L
        lastStepTimestamp = 0L

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
                Log.i(TAG, "Step detector registered")
            } ?: Log.w(TAG, "No step detector available")

            stepCounter?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                Log.i(TAG, "Step counter registered")
            } ?: Log.w(TAG, "No step counter available")
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

    /**
     * Reset dead-reckoning accumulators.  Called by the service after a GPS fix
     * so the displacement estimate restarts from the last known position.
     */
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
                lastStepTimestamp = System.currentTimeMillis()
                onStepDetected()
            }

            Sensor.TYPE_STEP_COUNTER -> {
                val raw = event.values[0]
                if (stepCounterBaseline == null) {
                    stepCounterBaseline = raw
                }
                totalSteps = (raw - (stepCounterBaseline ?: raw)).toLong().coerceAtLeast(0)
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

    // ── Motion state update ──

    private fun updateMotionState() {
        val count = minOf(samplesCollected, SAMPLE_WINDOW)
        if (count == 0) return

        var accelMotionVotes = 0
        var gyroMotionVotes = 0
        var accelSum = 0f
        var gyroSum = 0f

        for (i in 0 until count) {
            accelSum += accelSamples[i]
            gyroSum += gyroSamples[i]
            if (accelSamples[i] > ACCEL_MOTION_THRESHOLD) accelMotionVotes++
            if (gyroSamples[i] > GYRO_MOTION_THRESHOLD) gyroMotionVotes++
        }

        val avgAccel = accelSum / count
        val avgGyro = gyroSum / count

        val accelVoteRatio = accelMotionVotes.toFloat() / count
        val gyroVoteRatio = gyroMotionVotes.toFloat() / count

        val now = System.currentTimeMillis()
        val recentStep = hasStepPermission && (now - lastStepTimestamp) < STEP_COOLDOWN_MS

        val accelConfidence = (accelVoteRatio / MOTION_VOTE_THRESHOLD).coerceIn(0f, 1f)
        val gyroConfidence = (gyroVoteRatio / MOTION_VOTE_THRESHOLD).coerceIn(0f, 1f)

        // Dynamic fusion: include step weight only when permission is granted
        val motionConfidence = if (hasStepPermission) {
            val stepConfidence = if (recentStep) 1f else 0f
            (accelConfidence * WEIGHT_ACCEL_WITH_STEPS +
             stepConfidence * WEIGHT_STEPS +
             gyroConfidence * WEIGHT_GYRO_WITH_STEPS)
        } else {
            // No step data — redistribute to accel (70%) and gyro (30%)
            (accelConfidence * WEIGHT_ACCEL_NO_STEPS +
             gyroConfidence * WEIGHT_GYRO_NO_STEPS)
        }.coerceIn(0f, 1f)

        val isMoving = motionConfidence > GPS_NEEDED_CONFIDENCE

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
            steps = totalSteps,
            headingDeg = currentHeadingDeg,
            displacementMeters = totalDisplacement,
            gpsNeeded = gpsNeeded,
            stepPermissionGranted = hasStepPermission
        )
    }
}
