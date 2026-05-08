package com.trackmyjourney.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.trackmyjourney.R
import com.trackmyjourney.data.location.AutoTrackDetector
import com.trackmyjourney.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Minimal foreground service that keeps the process alive so [AutoTrackDetector]
 * can monitor motion sensors even when the user has closed the app.
 *
 * Lifecycle is driven by [AutoTrackDetector]:
 *   • Started when auto-start tracking is enabled (setting change or via [BootReceiver])
 *   • Stopped when auto-start tracking is disabled
 *
 * Uses IMPORTANCE_MIN so its notification is as unobtrusive as possible.
 */
@AndroidEntryPoint
class AutoStartMonitorService : Service() {

    companion object {
        private const val TAG = "AutoStartMonitorSvc"
        internal const val NOTIFICATION_ID = 1002
        internal const val CHANNEL_ID = "auto_start_monitor_channel"

        fun start(context: Context) {
            val intent = Intent(context, AutoStartMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoStartMonitorService::class.java))
        }
    }

    @Inject lateinit var autoTrackDetector: AutoTrackDetector

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        Log.i(TAG, "Auto-start monitor service started")
        // attach() is idempotent — safe to call even if the app is also open
        autoTrackDetector.attach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY ensures Android restarts the service if it is killed due to low memory
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Auto-start monitor service stopped")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Auto-Start Monitor",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Monitors motion to auto-start journey tracking"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto-Start Tracking")
            .setContentText("Watching for movement to start recording automatically")
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}

