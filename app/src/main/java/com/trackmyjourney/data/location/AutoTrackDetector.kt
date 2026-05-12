package com.trackmyjourney.data.location

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.trackmyjourney.data.local.SettingsDataStore
import com.trackmyjourney.service.TrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the "auto-start tracking" feature.
 *
 * Two layers cooperate:
 *  • Google Play Services ActivityRecognition transitions handle background
 *    motion detection — they wake the app even when the process is dead.
 *  • [MotionSensorManager] provides faster, foreground-only detection while
 *    the app is open, complementing the higher-latency Play Services events.
 *
 * Call [attach] once from Application.onCreate; the detector then follows
 * the [SettingsDataStore.AUTO_START_TRACKING] toggle.
 */
@Singleton
class AutoTrackDetector @Inject constructor(
    private val appContext: Context,
    private val settingsDataStore: SettingsDataStore,
    private val motionSensorManager: MotionSensorManager
) {
    companion object {
        private const val TAG = "AutoTrackDetector"

        /** Minimum motion confidence to consider the user moving. */
        private const val MOTION_CONFIDENCE_START = 0.5f

        /** Sustained motion duration before tracking auto-starts. */
        private const val MOTION_SUSTAIN_MS = 8_000L

        /**
         * Confirmed steps required to auto-start tracking immediately.
         * Hardware step events are a strong "real locomotion" signal — when
         * the user has actually taken a few steps we don't need to wait for
         * the [MOTION_SUSTAIN_MS] confidence window.
         */
        private const val STEPS_TO_START = 3

        /** Stillness duration before an auto-started track auto-stops. */
        private const val STILL_TIMEOUT_MS = 2 * 60_000L

        private const val TRANSITION_REQUEST_CODE = 4231
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var attachJob: Job? = null
    private var rearmJob: Job? = null
    private var detectionLoop: Job? = null

    @Volatile private var enabled: Boolean = false
    @Volatile private var transitionsRegistered: Boolean = false

    /** Idempotent. Watches the toggle and (de)activates the detector. */
    fun attach() {
        if (attachJob?.isActive == true) return
        attachJob = scope.launch {
            settingsDataStore.settings
                .map { it.autoStartTracking }
                .distinctUntilChanged()
                .collect { on ->
                    enabled = on
                    if (on) {
                        registerTransitions()
                        startDetection()
                    } else {
                        unregisterTransitions()
                        stopDetection()
                    }
                }
        }
        if (rearmJob?.isActive != true) {
            rearmJob = scope.launch {
                TrackingService.isRunning
                    .collect { running ->
                        if (!running && enabled) startDetection()
                    }
            }
        }
    }

    // ── Foreground sensor-based detection ────────────────────────────────

    private fun startDetection() {
        val activityRecognitionGranted = hasActivityRecognitionPermission()
        motionSensorManager.startMonitoring(activityRecognitionGranted)

        if (detectionLoop?.isActive == true) return
        Log.i(TAG, "Beginning sensor motion watch")

        detectionLoop = scope.launch {
            var movingSince = 0L
            var stillSince = 0L
            // Baseline step count for the current still→moving streak.
            // -1 means "not yet captured"; set on the first sample where
            // we're not already tracking.
            var stepsBaseline = -1L

            motionSensorManager.motionState.collect { state ->
                val now = System.currentTimeMillis()
                val isMoving = state.isDeviceMoving ||
                        state.vehicleMotionDetected ||
                        state.motionConfidence >= MOTION_CONFIDENCE_START

                val tracking = TrackingService.isRunning.value

                if (!tracking) {
                    if (stepsBaseline < 0) stepsBaseline = state.steps
                    val newSteps = state.steps - stepsBaseline
                    if (newSteps >= STEPS_TO_START) {
                        Log.i(TAG, "$newSteps real steps detected — auto-starting tracking")
                        TrackingService.startTracking(appContext)
                    }
                } else {
                    // Reset baseline so the next still→moving streak starts fresh.
                    stepsBaseline = -1L
                }

                if (isMoving) {
                    stillSince = 0L
                    if (movingSince == 0L) movingSince = now

                    val sustained = now - movingSince >= MOTION_SUSTAIN_MS
                    if (sustained && !tracking) {
                        Log.i(TAG, "Sustained sensor motion — auto-starting tracking")
                        TrackingService.startTracking(appContext)
                    }
                } else {
                    movingSince = 0L
                    if (tracking) {
                        if (stillSince == 0L) stillSince = now
                        if (now - stillSince >= STILL_TIMEOUT_MS) {
                            Log.i(TAG, "Sensor stillness ${STILL_TIMEOUT_MS / 1000}s — auto-stopping tracking")
                            TrackingService.stopTracking(appContext)
                            stillSince = 0L
                        }
                    } else {
                        stillSince = 0L
                    }
                }
            }
        }
    }

    private fun stopDetection() {
        if (detectionLoop == null) return
        Log.i(TAG, "Stopping sensor motion watch")
        detectionLoop?.cancel()
        detectionLoop = null
        if (!TrackingService.isRunning.value) {
            motionSensorManager.stopMonitoring()
        }
    }

    // ── Background Play Services ActivityRecognition transitions ─────────

    private fun hasActivityRecognitionPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    private fun transitionPendingIntent(): PendingIntent {
        val intent = Intent(appContext, ActivityRecognitionReceiver::class.java).apply {
            action = ActivityRecognitionReceiver.ACTION
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(appContext, TRANSITION_REQUEST_CODE, intent, flags)
    }

    private fun registerTransitions() {
        if (transitionsRegistered) return
        if (!hasActivityRecognitionPermission()) {
            Log.w(TAG, "ACTIVITY_RECOGNITION not granted — background transitions unavailable")
            return
        }

        val motionTypes = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.ON_FOOT,
            DetectedActivity.RUNNING,
            DetectedActivity.WALKING
        )
        val transitions = buildList {
            motionTypes.forEach {
                add(
                    ActivityTransition.Builder()
                        .setActivityType(it)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                        .build()
                )
            }
            add(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build()
            )
        }

        try {
            val request = ActivityTransitionRequest(transitions)
            ActivityRecognition.getClient(appContext)
                .requestActivityTransitionUpdates(request, transitionPendingIntent())
                .addOnSuccessListener {
                    transitionsRegistered = true
                    Log.i(TAG, "Background activity transitions registered")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to register activity transitions: ${e.message}")
                }
        } catch (e: SecurityException) {
            Log.w(TAG, "Activity transition request denied: ${e.message}")
        }
    }

    private fun unregisterTransitions() {
        if (!transitionsRegistered) return
        try {
            ActivityRecognition.getClient(appContext)
                .removeActivityTransitionUpdates(transitionPendingIntent())
                .addOnCompleteListener {
                    transitionsRegistered = false
                    Log.i(TAG, "Background activity transitions removed")
                }
        } catch (e: SecurityException) {
            Log.w(TAG, "Activity transition removal denied: ${e.message}")
        }
    }
}
