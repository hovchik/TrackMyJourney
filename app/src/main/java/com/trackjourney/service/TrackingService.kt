package com.trackjourney.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.trackjourney.R
import com.trackjourney.data.local.SettingsDataStore
import com.trackjourney.data.location.GpsSatelliteTracker
import com.trackjourney.data.location.LocationTracker
import com.trackjourney.data.model.TrackingSettings
import com.trackjourney.data.repository.TrackRepository
import com.trackjourney.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@AndroidEntryPoint
class TrackingService : Service() {

    companion object {
        private const val TAG = "TrackingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tracking_channel"

        const val ACTION_START = "com.trackjourney.ACTION_START"
        const val ACTION_STOP = "com.trackjourney.ACTION_STOP"
        const val ACTION_PAUSE = "com.trackjourney.ACTION_PAUSE"
        const val ACTION_RESUME = "com.trackjourney.ACTION_RESUME"
        const val EXTRA_TRACK_NAME = "track_name"

        fun startTracking(context: Context, trackName: String = "") {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TRACK_NAME, trackName)
            }
            context.startForegroundService(intent)
        }

        fun stopTracking(context: Context) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    @Inject lateinit var repository: TrackRepository
    @Inject lateinit var locationTracker: LocationTracker
    @Inject lateinit var satelliteTracker: GpsSatelliteTracker
    @Inject lateinit var settingsDataStore: SettingsDataStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trackingJob: Job? = null
    private var currentTrackId: String? = null
    private var isPaused = false
    private var pointCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val trackName = intent.getStringExtra(EXTRA_TRACK_NAME) ?: ""
                startTracking(trackName)
            }
            ACTION_STOP -> stopTracking()
            ACTION_PAUSE -> pauseTracking()
            ACTION_RESUME -> resumeTracking()
        }
        return START_STICKY
    }

    private fun startTracking(trackName: String) {
        if (trackingJob?.isActive == true) return

        val notification = buildNotification("Starting tracking...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Start satellite monitoring
        satelliteTracker.startMonitoring()

        trackingJob = serviceScope.launch {
            try {
                // Create new track session
                val track = repository.startNewTrack(trackName)
                currentTrackId = track.id
                pointCount = 0

                Log.i(TAG, "Tracking started: ${track.id}")

                // Observe settings to react to interval changes
                settingsDataStore.settings.collectLatest { settings ->
                    trackWithSettings(track.id, settings)
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Tracking cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Tracking error: ${e.message}")
            }
        }
    }

    private suspend fun trackWithSettings(trackId: String, settings: TrackingSettings) {
        locationTracker.locationUpdates(settings).collect { location ->
            if (!isPaused) {
                repository.addTrackPoint(trackId, location, null)
                pointCount++

                val satInfo = satelliteTracker.satelliteInfo.value
                val speedKmh = LocationTracker.msToKmh(location.speed)
                updateNotification(
                    "Recording | ${pointCount} pts | ${String.format("%.1f", speedKmh)} km/h | SAT ${satInfo.usedInFix}/${satInfo.totalVisible}"
                )
            }
        }
    }

    private fun pauseTracking() {
        isPaused = true
        updateNotification("Tracking paused")
    }

    private fun resumeTracking() {
        isPaused = false
        updateNotification("Tracking resumed")
    }

    private fun stopTracking() {
        serviceScope.launch {
            currentTrackId?.let { trackId ->
                repository.endTrack(trackId)
                Log.i(TAG, "Track ended: $trackId | $pointCount points recorded")
            }
            trackingJob?.cancel()
            trackingJob = null
            currentTrackId = null
            locationTracker.stopTracking()
            satelliteTracker.stopMonitoring()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ─── NOTIFICATIONS ──────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Location Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while tracking your journey"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TrackMyJourney")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        locationTracker.stopTracking()
        satelliteTracker.stopMonitoring()
        super.onDestroy()
    }
}
