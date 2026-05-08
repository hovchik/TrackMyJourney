package com.trackmyjourney.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
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
 * Watches the phone's motion sensors and automatically starts a tracking
 * session when sustained movement is detected, then stops it when the user
 * has been still for a while.
 *
 * Activated only while the auto-start setting is on.  Call [attach] once
 * (e.g. from Application.onCreate) — the detector then turns itself on and
 * off as the toggle changes.
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

        /** Stillness duration before an auto-started track auto-stops. */
        private const val STILL_TIMEOUT_MS = 2 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var attachJob: Job? = null
    private var rearmJob: Job? = null
    private var detectionLoop: Job? = null

    @Volatile private var enabled: Boolean = false

    /** Idempotent. Watches the toggle and (de)activates the detector. */
    fun attach() {
        if (attachJob?.isActive == true) return
        attachJob = scope.launch {
            settingsDataStore.settings
                .map { it.autoStartTracking }
                .distinctUntilChanged()
                .collect { on ->
                    enabled = on
                    if (on) startDetection() else stopDetection()
                }
        }
        // Re-arm sensors whenever a tracking session ends while the
        // detector is enabled — the service shuts the sensors down on stop.
        if (rearmJob?.isActive != true) {
            rearmJob = scope.launch {
                TrackingService.isRunning
                    .distinctUntilChanged()
                    .collect { running ->
                        if (!running && enabled) startDetection()
                    }
            }
        }
    }

    private fun startDetection() {
        // Sensor registration is idempotent on the manager — call it on
        // every (re)arm because TrackingService stops sensors when it ends.
        val activityRecognitionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        motionSensorManager.startMonitoring(activityRecognitionGranted)

        if (detectionLoop?.isActive == true) return
        Log.i(TAG, "Auto-start tracking enabled — beginning motion watch")

        detectionLoop = scope.launch {
            var movingSince = 0L
            var stillSince = 0L

            motionSensorManager.motionState.collect { state ->
                val now = System.currentTimeMillis()
                val isMoving = state.isDeviceMoving ||
                        state.vehicleMotionDetected ||
                        state.motionConfidence >= MOTION_CONFIDENCE_START

                val tracking = TrackingService.isRunning.value

                if (isMoving) {
                    stillSince = 0L
                    if (movingSince == 0L) movingSince = now

                    val sustained = now - movingSince >= MOTION_SUSTAIN_MS
                    if (sustained && !tracking) {
                        Log.i(TAG, "Sustained motion detected — auto-starting tracking")
                        TrackingService.startTracking(appContext)
                    }
                } else {
                    movingSince = 0L
                    if (tracking) {
                        if (stillSince == 0L) stillSince = now
                        if (now - stillSince >= STILL_TIMEOUT_MS) {
                            Log.i(TAG, "No motion for ${STILL_TIMEOUT_MS / 1000}s — auto-stopping tracking")
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
        Log.i(TAG, "Auto-start tracking disabled — stopping motion watch")
        detectionLoop?.cancel()
        detectionLoop = null
        if (!TrackingService.isRunning.value) {
            motionSensorManager.stopMonitoring()
        }
    }
}
