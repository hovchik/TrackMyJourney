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
import kotlin.math.sqrt

/**
 * Monitors the phone's physical sensors (accelerometer + gyroscope) to detect
 * whether the device is actually moving, independent of GPS.
 *
 * GPS drift when stationary can report phantom speeds of 5-15+ km/h.
 * By checking accelerometer magnitude and gyroscope rotation, we can
 * tell if the phone is truly in motion and override GPS noise.
 */
class MotionSensorManager(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "MotionSensorManager"

        // Accelerometer linear magnitude threshold (m/s²) for "moving"
        // Gravity is ~9.81, so we look at deviation from gravity.
        // Typical stationary noise is 0.1-0.3 m/s². Walking starts at ~0.5 m/s².
        private const val ACCEL_MOTION_THRESHOLD = 0.4f

        // Gyroscope angular speed threshold (rad/s) for rotation/movement
        // Stationary phone has <0.02 rad/s noise. Walking produces >0.1 rad/s.
        private const val GYRO_MOTION_THRESHOLD = 0.08f

        // Number of recent samples to average over (reduces noise)
        private const val SAMPLE_WINDOW = 20

        // Fraction of recent samples that must show motion to be "moving"
        private const val MOTION_VOTE_THRESHOLD = 0.4f

        // Step detection cooldown: if a step was detected within this window, user is moving
        private const val STEP_COOLDOWN_MS = 5000L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private val _motionState = MutableStateFlow(MotionState())
    val motionState: StateFlow<MotionState> = _motionState.asStateFlow()

    // Ring buffers for smoothing
    private val accelSamples = FloatArray(SAMPLE_WINDOW)
    private val gyroSamples = FloatArray(SAMPLE_WINDOW)
    private var sampleIndex = 0
    private var samplesCollected = 0

    private var lastStepTimestamp = 0L
    private var isMonitoring = false

    data class MotionState(
        /** True if accelerometer + gyroscope indicate the device is physically moving */
        val isDeviceMoving: Boolean = false,
        /** Smoothed linear acceleration magnitude (m/s²) after removing gravity */
        val accelerationMagnitude: Float = 0f,
        /** Smoothed angular velocity (rad/s) */
        val rotationRate: Float = 0f,
        /** True if step detector fired recently */
        val stepDetected: Boolean = false,
        /** Confidence 0..1 that the device is truly in motion */
        val motionConfidence: Float = 0f
    )

    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        // LINEAR_ACCELERATION already has gravity removed (= pure user acceleration)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "Accelerometer registered")
        } ?: Log.w(TAG, "No linear accelerometer available")

        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "Gyroscope registered")
        } ?: Log.w(TAG, "No gyroscope available")

        stepDetector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Step detector registered")
        } ?: Log.w(TAG, "No step detector available")
    }

    fun stopMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false
        sensorManager.unregisterListener(this)
        samplesCollected = 0
        sampleIndex = 0
        _motionState.value = MotionState()
        Log.i(TAG, "Sensor monitoring stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Magnitude of linear acceleration vector (gravity already removed)
                val magnitude = sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                accelSamples[sampleIndex % SAMPLE_WINDOW] = magnitude
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
            }
        }

        sampleIndex++
        samplesCollected++

        // Update state every SAMPLE_WINDOW samples to avoid excessive recompositions
        if (samplesCollected >= SAMPLE_WINDOW && sampleIndex % 5 == 0) {
            updateMotionState()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* unused */ }

    private fun updateMotionState() {
        val count = minOf(samplesCollected, SAMPLE_WINDOW)
        if (count == 0) return

        // Count how many samples exceed motion thresholds
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

        val recentStep = (System.currentTimeMillis() - lastStepTimestamp) < STEP_COOLDOWN_MS

        // Compute motion confidence from multiple signals
        // Accelerometer signal is strongest indicator
        val accelConfidence = (accelVoteRatio / MOTION_VOTE_THRESHOLD).coerceIn(0f, 1f)
        val gyroConfidence = (gyroVoteRatio / MOTION_VOTE_THRESHOLD).coerceIn(0f, 1f)
        val stepConfidence = if (recentStep) 1f else 0f

        // Weighted combination: accel > step > gyro
        val motionConfidence = (accelConfidence * 0.50f + stepConfidence * 0.30f + gyroConfidence * 0.20f)
            .coerceIn(0f, 1f)

        val isMoving = motionConfidence > 0.35f

        _motionState.value = MotionState(
            isDeviceMoving = isMoving,
            accelerationMagnitude = avgAccel,
            rotationRate = avgGyro,
            stepDetected = recentStep,
            motionConfidence = motionConfidence
        )
    }
}
