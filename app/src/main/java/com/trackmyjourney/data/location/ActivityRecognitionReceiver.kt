package com.trackmyjourney.data.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.trackmyjourney.service.TrackingService

/**
 * Receives ActivityRecognition transition broadcasts and starts
 * [TrackingService] when a walking activity is detected.  Manifest-registered
 * so transitions are delivered even when the app process is not running.
 *
 * Only walking-type ENTER transitions are subscribed (by [AutoTrackDetector]).
 * Auto-stop is NOT handled here — it is driven by the sensor-fusion logic in
 * [AutoTrackDetector] which cross-validates step counter, accelerometer, and
 * gyroscope data for much higher reliability than Play Services transitions.
 */
class ActivityRecognitionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AutoTrackReceiver"
        const val ACTION = "com.trackmyjourney.ACTION_ACTIVITY_TRANSITION"

        // Walking-only: vehicle and bicycle motion must not trigger a track.
        private fun isWalkingActivity(type: Int): Boolean = when (type) {
            DetectedActivity.ON_FOOT,
            DetectedActivity.RUNNING,
            DetectedActivity.WALKING -> true
            else -> false
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        for (event in result.transitionEvents) {
            if (event.transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) continue

            if (isWalkingActivity(event.activityType) && !TrackingService.isRunning.value) {
                Log.i(TAG, "Walking transition (type=${event.activityType}) — starting tracking")
                try {
                    TrackingService.startTracking(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start tracking from background: ${e.message}")
                }
            }
        }
    }
}
