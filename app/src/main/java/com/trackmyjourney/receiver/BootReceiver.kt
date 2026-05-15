package com.trackmyjourney.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.trackmyjourney.service.AutoStartKeepAliveWorker
import com.trackmyjourney.service.AutoStartMonitorService

/**
 * Receives BOOT_COMPLETED and MY_PACKAGE_REPLACED broadcasts so the
 * auto-start monitor service can be restarted after a device reboot or
 * an app update without requiring the user to open the app manually.
 *
 * The setting is cached in a plain SharedPreferences file by
 * [AutoTrackDetector] so it can be read synchronously here without
 * depending on DataStore (which cannot be read from a BroadcastReceiver).
 *
 * NOTE: If the user has force-stopped the app, Android prevents broadcasts
 * from restarting it until they open it manually — this is an OS-level
 * restriction that cannot be circumvented.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        /** SharedPreferences name — must match the one written by AutoTrackDetector. */
        const val PREFS_NAME = "auto_start_cache"
        const val KEY_AUTO_START = "auto_start_tracking"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        Log.i(TAG, "Received: ${intent.action}")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStartEnabled = prefs.getBoolean(KEY_AUTO_START, false)

        if (autoStartEnabled) {
            Log.i(TAG, "Auto-start is enabled — starting monitor service")
            AutoStartMonitorService.start(context)
            // Re-schedule the keep-alive worker after boot so it survives reboots
            AutoStartKeepAliveWorker.schedule(context)
        } else {
            Log.i(TAG, "Auto-start is disabled — nothing to do")
        }
    }
}

