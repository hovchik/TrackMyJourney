package com.trackmyjourney.data.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.trackmyjourney.service.AutoStartMonitorService
import com.trackmyjourney.service.TrackingService

/**
 * Receives ActivityRecognition transition broadcasts and starts
 * [TrackingService] when any motion activity is detected (walking, running,
 * cycling, or vehicle travel).  Manifest-registered so transitions are
 * delivered even when the app process is not running.
 *
 * Only ENTER transitions are subscribed (by [AutoTrackDetector]).
 * Auto-stop is NOT handled here — it is driven by the sensor-fusion logic in
 * [AutoTrackDetector] which cross-validates step counter, accelerometer, and
 * gyroscope data for much higher reliability than Play Services transitions.
 */
class ActivityRecognitionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AutoTrackReceiver"
        const val ACTION = "com.trackmyjourney.ACTION_ACTIVITY_TRANSITION"

        /** All motion types that should trigger auto-start tracking. */
        private fun isMotionActivity(type: Int): Boolean = when (type) {
            DetectedActivity.ON_FOOT,
            DetectedActivity.RUNNING,
            DetectedActivity.WALKING,
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE -> true
            else -> false
        }

        private fun activityName(type: Int): String = when (type) {
            DetectedActivity.ON_FOOT -> "on_foot"
            DetectedActivity.RUNNING -> "running"
            DetectedActivity.WALKING -> "walking"
            DetectedActivity.IN_VEHICLE -> "in_vehicle"
            DetectedActivity.ON_BICYCLE -> "on_bicycle"
            else -> "unknown($type)"
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        for (event in result.transitionEvents) {
            if (event.transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) continue

            if (isMotionActivity(event.activityType) && !TrackingService.isRunning.value) {
                Log.i(TAG, "Motion transition (${activityName(event.activityType)}) — starting tracking")
                try {
                    // Ensure AutoStartMonitorService is running first so the app
                    // has an active foreground service.  On Android 12+ starting a
                    // foreground service from a BroadcastReceiver is only allowed
                    // when the app already holds a foreground-service exemption.
                    AutoStartMonitorService.start(context)
                    TrackingService.startTracking(context)
                } catch (e: Exception) {
                    // On Android 12+ (API 31) this can throw
                    // ForegroundServiceStartNotAllowedException when the app is
                    // in a restricted background state.
                    Log.w(TAG, "Failed to start tracking from background: ${e.message}")
                }
            }
        }
    }
}
