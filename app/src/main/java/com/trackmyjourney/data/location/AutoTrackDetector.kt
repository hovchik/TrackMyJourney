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
import com.trackmyjourney.receiver.BootReceiver
import com.trackmyjourney.service.AutoStartMonitorService
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

        /**
         * Confirmed steps required to auto-start tracking.  Hardware step
         * events are the only signal we trust for starting — generic motion
         * (a phone shaken in hand, a buzzing notification) must not trigger
         * a track.
         */
        private const val STEPS_TO_START = 3

        /** No new steps for this long while tracking → auto-stop (if sensors also agree). */
        private const val STILL_TIMEOUT_MS = 180_000L  // 3 minutes

        /**
         * Hard ceiling: if steps haven't increased for this long, stop
         * regardless of sensor motion — prevents infinite tracking from
         * persistent low-level vibrations (e.g. phone on a washing machine).
         */
        private const val HARD_STILL_TIMEOUT_MS = 600_000L  // 10 minutes

        private const val TRANSITION_REQUEST_CODE = 4231
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var attachJob: Job? = null
    private var rearmJob: Job? = null

    /** Guards [detectionLoop] assignment and [startRequested] flag. */
    private val detectionLock = Any()
    private var detectionLoop: Job? = null

    /**
     * Set to `true` after [TrackingService.startTracking] is called, cleared
     * once [TrackingService.isRunning] becomes `true`.  Prevents redundant
     * start-intents being sent on every sensor emission while the service
     * is still launching.
     */
    @Volatile private var startRequested: Boolean = false

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
                    // Cache in SharedPreferences so BootReceiver can read it
                    // synchronously after a device reboot without needing DataStore.
                    appContext.getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(BootReceiver.KEY_AUTO_START, on).apply()

                    enabled = on
                    if (on) {
                        registerTransitions()
                        // Keep the process alive for background motion monitoring
                        AutoStartMonitorService.start(appContext)
                        startDetection()
                    } else {
                        unregisterTransitions()
                        stopDetection()
                        AutoStartMonitorService.stop(appContext)
                    }
                }
        }
        if (rearmJob?.isActive != true) {
            rearmJob = scope.launch {
                TrackingService.isRunning
                    .collect { running ->
                        // Re-arm detection when tracking ends (and feature is still on).
                        // Double-check `enabled` to avoid a race where the toggle was
                        // just turned off but `rearmJob` already entered this block.
                        if (!running && enabled) startDetection()
                    }
            }
        }
    }

    // ── Foreground sensor-based detection ────────────────────────────────

    private fun startDetection() {
        // Re-check volatile flag inside the method to close the race window
        // between rearmJob reading `enabled` and attachJob setting it to false.
        if (!enabled) return

        val activityRecognitionGranted = hasActivityRecognitionPermission()
        motionSensorManager.startMonitoring(activityRecognitionGranted)

        synchronized(detectionLock) {
            if (detectionLoop?.isActive == true) return
            Log.i(TAG, "Beginning sensor motion watch")

            detectionLoop = scope.launch {
                // Baseline step count for the current idle (not-tracking) phase.
                // -1 = "not yet captured"; set on first sample while idle.
                var idleStepsBaseline = -1L
                // Last cumulative step count seen while tracking is active, and
                // the wall-clock time we last observed it grow.  Used to decide
                // when the user has stopped walking.
                var lastTrackingStepCount = -1L
                var lastStepIncreaseTime = 0L

                motionSensorManager.motionState.collect { state ->
                    val now = System.currentTimeMillis()
                    val tracking = TrackingService.isRunning.value

                    if (tracking) {
                        // Tracking is confirmed running — clear the start guard.
                        startRequested = false
                    }

                    if (!tracking) {
                        // ── IDLE: wait for steps to auto-start ──
                        if (idleStepsBaseline < 0) idleStepsBaseline = state.steps
                        val newSteps = state.steps - idleStepsBaseline
                        if (newSteps >= STEPS_TO_START && !startRequested) {
                            Log.i(TAG, "$newSteps real steps detected — auto-starting tracking")
                            startRequested = true
                            TrackingService.startTracking(appContext)
                        }
                        // Reset tracking-phase state so the next session starts fresh.
                        lastTrackingStepCount = -1L
                        lastStepIncreaseTime = 0L
                    } else {
                        // ── TRACKING: monitor for stillness to auto-stop ──
                        // Clear idle baseline; capture it fresh once tracking ends.
                        idleStepsBaseline = -1L

                        if (lastTrackingStepCount < 0) {
                            lastTrackingStepCount = state.steps
                            lastStepIncreaseTime = now
                        } else if (state.steps > lastTrackingStepCount) {
                            lastTrackingStepCount = state.steps
                            lastStepIncreaseTime = now
                        } else if (now - lastStepIncreaseTime >= HARD_STILL_TIMEOUT_MS) {
                            // Hard ceiling — stop regardless of sensor motion to prevent
                            // infinite tracking from ambient vibrations.
                            Log.i(TAG, "No new steps for ${HARD_STILL_TIMEOUT_MS / 1000}s — hard auto-stop")
                            TrackingService.stopTracking(appContext)
                            lastTrackingStepCount = -1L
                            lastStepIncreaseTime = 0L
                        } else if (now - lastStepIncreaseTime >= STILL_TIMEOUT_MS) {
                            // Steps haven't increased — cross-validate with motion sensors.
                            // Only stop if the device is truly stationary; step detection
                            // can be unreliable with the phone in a bag or pocket.
                            if (!state.isDeviceMoving && !state.vehicleMotionDetected && state.motionConfidence < 0.25f) {
                                Log.i(TAG, "No new steps for ${STILL_TIMEOUT_MS / 1000}s and device stationary " +
                                        "(confidence=${state.motionConfidence}) — auto-stopping tracking")
                                TrackingService.stopTracking(appContext)
                                lastTrackingStepCount = -1L
                                lastStepIncreaseTime = 0L
                            } else {
                                // Sensors still detect motion — step counter may be unreliable.
                                // Keep tracking alive but don't reset the timer fully so the
                                // hard ceiling can still kick in.
                                Log.d(TAG, "No new steps but device still moving " +
                                        "(confidence=${state.motionConfidence}, " +
                                        "moving=${state.isDeviceMoving}, " +
                                        "vehicle=${state.vehicleMotionDetected}) — keeping tracking alive")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopDetection() {
        synchronized(detectionLock) {
            if (detectionLoop == null) return
            Log.i(TAG, "Stopping sensor motion watch")
            detectionLoop?.cancel()
            detectionLoop = null
            startRequested = false
        }
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

        // Subscribe only to walking activities — vehicle/bicycle motion
        // must not trigger a track.  STILL is NOT registered because the
        // receiver ignores it (auto-stop is handled by sensor fusion in
        // the detection loop) and subscribing to it wastes Play Services
        // resources.
        val walkingTypes = listOf(
            DetectedActivity.ON_FOOT,
            DetectedActivity.RUNNING,
            DetectedActivity.WALKING
        )
        val transitions = walkingTypes.map {
            ActivityTransition.Builder()
                .setActivityType(it)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
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
