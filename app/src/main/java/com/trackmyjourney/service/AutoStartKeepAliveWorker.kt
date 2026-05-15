package com.trackmyjourney.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.trackmyjourney.receiver.BootReceiver
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager worker that ensures [AutoStartMonitorService] stays
 * alive even when aggressive OEM battery optimization kills it.
 *
 * WorkManager is the most reliable scheduling mechanism on Android —
 * it survives app death, device restarts, and Doze mode.  This worker
 * simply checks the cached auto-start preference and restarts the
 * service if it should be running.
 *
 * Scheduled every 15 minutes (the minimum periodic interval).
 */
class AutoStartKeepAliveWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    companion object {
        private const val TAG = "AutoStartKeepAlive"
        internal const val WORK_NAME = "auto_start_keep_alive"

        /**
         * Enqueue a unique periodic work request.  Safe to call multiple times —
         * [ExistingPeriodicWorkPolicy.KEEP] ensures only one instance runs.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)  // Must run even on low battery
                .build()

            val request = PeriodicWorkRequestBuilder<AutoStartKeepAliveWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            Log.i(TAG, "Keep-alive worker scheduled")
        }

        /** Cancel the periodic work when auto-start is disabled. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Keep-alive worker cancelled")
        }
    }

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(
            BootReceiver.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val autoStartEnabled = prefs.getBoolean(BootReceiver.KEY_AUTO_START, false)

        if (autoStartEnabled) {
            Log.i(TAG, "Auto-start is enabled — ensuring monitor service is running")
            try {
                AutoStartMonitorService.start(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start monitor service: ${e.message}")
            }
        } else {
            Log.d(TAG, "Auto-start is disabled — nothing to do")
        }

        return Result.success()
    }
}

