package com.trackmyjourney.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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

    /**
     * Partial WakeLock to keep the CPU active for sensor callbacks.
     * Without this, sensor events (step detector, accelerometer) may not
     * be delivered reliably when the screen is off, preventing auto-start
     * from detecting motion.
     */
    private var wakeLock: PowerManager.WakeLock? = null

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

        // Acquire a WakeLock so motion sensor callbacks keep firing
        // even when the screen is off and the device enters doze.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TrackMyJourney::AutoStartMonitorWakeLock"
        ).apply {
            acquire()
        }

        Log.i(TAG, "Auto-start monitor service started")
        // attach() is idempotent — safe to call even if the app is also open
        autoTrackDetector.attach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY ensures Android restarts the service if it is killed due to low memory
        return START_STICKY
    }

    /**
     * Called when the user swipes the app away from the recent-apps screen.
     * Many OEMs also kill services at this point.  Schedule a quick restart
     * via AlarmManager so the monitoring resumes without waiting for the
     * 15-minute WorkManager cadence.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed (app swiped away) — scheduling restart")
        scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        // If auto-start is still enabled, schedule a fast restart so the service
        // recovers quickly even if START_STICKY fails (aggressive OEM battery optimization).
        val prefs = getSharedPreferences(
            com.trackmyjourney.receiver.BootReceiver.PREFS_NAME,
            MODE_PRIVATE
        )
        if (prefs.getBoolean(com.trackmyjourney.receiver.BootReceiver.KEY_AUTO_START, false)) {
            Log.i(TAG, "Auto-start monitor service destroyed while enabled — scheduling restart")
            scheduleRestart()
        }
        Log.i(TAG, "Auto-start monitor service stopped")
        super.onDestroy()
    }

    /**
     * Schedules a restart of this service via AlarmManager.
     * Uses a short delay (5 seconds) so the monitoring resumes quickly
     * after the OS or the user kills the service.
     */
    private fun scheduleRestart() {
        try {
            val restartIntent = Intent(this, AutoStartMonitorService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 0, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 5_000L,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule restart alarm: ${e.message}")
        }
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

