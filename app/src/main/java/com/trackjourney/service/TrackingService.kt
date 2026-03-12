package com.trackjourney.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.trackjourney.R
import com.trackjourney.data.bluetooth.WearableConnectionState
import com.trackjourney.data.bluetooth.WearableManager
import com.trackjourney.data.local.SettingsDataStore
import com.trackjourney.data.location.BatteryMonitor
import com.trackjourney.data.location.GpsSatelliteTracker
import com.trackjourney.data.location.LocationTracker
import com.trackjourney.data.location.MotionSensorManager
import com.trackjourney.data.location.SmartIntervalManager
import com.trackjourney.data.model.ActivityType
import com.trackjourney.data.model.TrackingMode
import com.trackjourney.data.model.TrackingSettings
import com.trackjourney.data.model.WebhookLocationPayload
import com.trackjourney.data.repository.TrackRepository
import com.trackjourney.data.webhook.WebhookSender
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

        /** Observable flag — true while the service is actively tracking. */
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

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
    @Inject lateinit var wearableManager: WearableManager
    @Inject lateinit var motionSensorManager: MotionSensorManager
    @Inject lateinit var batteryMonitor: BatteryMonitor
    @Inject lateinit var smartIntervalManager: SmartIntervalManager
    @Inject lateinit var webhookSender: WebhookSender

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var trackingJob: Job? = null
    private var wearableScanJob: Job? = null
    private var currentTrackId: String? = null
    private var isPaused = false
    private var pointCount = 0
    private var batteryAtStart: Int? = null
    @Volatile private var latestWebhookPayload: WebhookLocationPayload? = null
    /** Timestamp of the last recorded GPS point (used for stationary skip logic). */
    private var lastRecordedTime = 0L
    /** Max time (ms) between recorded points even when stationary, to keep the track alive. */
    private val stationaryMaxGapMs = 60_000L

    /** Stops tracking when the device location toggle is switched off. */
    private val locationProviderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                if (!gpsEnabled && trackingJob?.isActive == true) {
                    Log.i(TAG, "Location provider disabled — auto-stopping tracking")
                    stopTracking()
                }
            }
        }
    }

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

        // Listen for location toggle changes so we can auto-stop
        registerReceiver(
            locationProviderReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        )

        val notification = buildNotification("Starting tracking...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Start satellite monitoring
        satelliteTracker.startMonitoring()

        // Start physical motion sensor monitoring (accelerometer + gyroscope)
        motionSensorManager.startMonitoring()

        // Start battery monitoring (for AI battery saver charging detection)
        batteryMonitor.startMonitoring()

        // Auto-scan for Garmin/Samsung wearable if Bluetooth is available
        startWearableScan()

        trackingJob = serviceScope.launch {
            try {
                // Create new track session
                val track = repository.startNewTrack(trackName)
                currentTrackId = track.id
                pointCount = 0
                lastRecordedTime = 0L

                // Regenerate webhook key for each new tracking session
                val newKey = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
                settingsDataStore.updateWebhookKey(newKey)
                Log.i(TAG, "Webhook key regenerated for new session")

                // Log battery level at start
                batteryAtStart = getBatteryLevel()
                batteryAtStart?.let { battery ->
                    repository.updateTrackBatteryStart(track.id, battery)
                    Log.i(TAG, "Battery at start: $battery%")
                }

                Log.i(TAG, "Tracking started: ${track.id}")
                _isRunning.value = true

                // Observe settings — collectLatest cancels the old
                // trackWithSettings and restarts with new settings when
                // interval/distance changes in the Settings screen
                settingsDataStore.settings.collectLatest { settings ->
                    Log.i(TAG, "Applying settings: mode=${settings.trackingMode}, interval=${settings.recordIntervalMs}ms, minDist=${settings.minDistanceMeters}m")
                    trackWithSettings(track.id, settings)
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Tracking cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Tracking error: ${e.message}")
            }
        }
    }

    private fun startWearableScan() {
        if (!wearableManager.isBluetoothAvailable || !wearableManager.isBluetoothEnabled) {
            Log.i(TAG, "Bluetooth not available/enabled — skipping wearable scan")
            return
        }

        wearableScanJob = serviceScope.launch {
            try {
                Log.i(TAG, "Auto-scanning for wearable devices...")
                wearableManager.scanForDevices()
                    .take(1) // Connect to the first device found
                    .collect { device ->
                        Log.i(TAG, "Found wearable: ${device.name} (${device.type})")
                        wearableManager.connectToDevice(device)
                    }
            } catch (e: CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                Log.w(TAG, "Wearable scan failed: ${e.message}")
            }
        }

        // Cancel scan after timeout (15s) if no device found
        serviceScope.launch {
            delay(15_000L)
            if (wearableScanJob?.isActive == true) {
                wearableScanJob?.cancel()
                Log.i(TAG, "Wearable scan timed out — no device found")
            }
        }
    }

    private suspend fun trackWithSettings(trackId: String, settings: TrackingSettings) = coroutineScope {
        var lastNotificationTime = 0L

        // Launch an independent webhook ticker that sends at a fixed cadence,
        // regardless of how often GPS delivers fixes.
        if (settings.webhookEnabled && settings.webhookUrl.isNotBlank()) {
            launch { runWebhookTicker(settings) }
        }

        when (settings.trackingMode) {
            TrackingMode.HIGH_ACCURACY -> {
                // Use the standard fixed-interval flow
                locationTracker.locationUpdates(settings).collect { location ->
                    if (!isPaused) {
                        handleLocationUpdate(trackId, location, settings, lastNotificationTime).let {
                            lastNotificationTime = it
                        }
                    }
                }
            }

            TrackingMode.ENERGY_EFFICIENCY -> {
                // Fixed 26s interval with balanced priority
                val interval = smartIntervalManager.getInitialInterval(settings)
                locationTracker.locationUpdatesWithInterval(
                    intervalMs = interval,
                    minDistanceMeters = settings.minDistanceMeters
                ).collect { location ->
                    if (!isPaused) {
                        handleLocationUpdate(trackId, location, settings, lastNotificationTime).let {
                            lastNotificationTime = it
                        }
                    }
                }
            }

            TrackingMode.AI_BATTERY_SAVER -> {
                // Dynamic interval — re-evaluate after each location update.
                // We use a MutableStateFlow to track the current interval
                // and restart the location flow when it changes.
                var currentInterval = smartIntervalManager.getInitialInterval(settings)
                var lastSpeedKmh = 0f

                while (true) {
                    var intervalChanged = false

                    locationTracker.locationUpdatesWithInterval(
                        intervalMs = currentInterval,
                        minDistanceMeters = settings.minDistanceMeters
                    ).collect { location ->
                        if (!isPaused) {
                            lastSpeedKmh = LocationTracker.msToKmh(location.speed)
                            handleLocationUpdate(trackId, location, settings, lastNotificationTime).let {
                                lastNotificationTime = it
                            }

                            // Check if the interval should change
                            val newInterval = smartIntervalManager.computeInterval(settings, lastSpeedKmh)
                            if (newInterval != currentInterval) {
                                currentInterval = newInterval
                                intervalChanged = true
                                // Cancel current collection to restart with new interval
                                return@collect
                            }
                        }
                    }

                    // If interval didn't change, the flow completed normally (shouldn't happen)
                    if (!intervalChanged) break
                }
            }
        }
    }

    private suspend fun handleLocationUpdate(
        trackId: String,
        location: android.location.Location,
        settings: TrackingSettings,
        lastNotificationTime: Long
    ): Long {
        // ── STATIONARY SKIP ──
        // If the device sensors indicate the user is not moving, skip recording
        // to save battery and storage. Still record at least once per minute
        // so the track doesn't have large time gaps.
        val motionState = motionSensorManager.motionState.value
        val now = System.currentTimeMillis()
        val timeSinceLastRecord = now - lastRecordedTime
        if (!motionState.isDeviceMoving
            && motionState.motionConfidence < 0.2f
            && timeSinceLastRecord < stationaryMaxGapMs
            && lastRecordedTime > 0L
        ) {
            Log.d(TAG, "Skipping GPS point — device stationary (confidence=${motionState.motionConfidence})")
            // Still update webhook payload with latest position even if not recording
            if (settings.webhookEnabled && settings.webhookUrl.isNotBlank()) {
                updateWebhookPayload(location, settings)
            }
            return lastNotificationTime
        }

        val wearableReading = wearableManager.latestReading.value

        val point = repository.addTrackPoint(trackId, location, wearableReading)
        if (point != null) {
            pointCount++
            lastRecordedTime = now
        }

        // Build the latest webhook payload so the independent ticker always
        // has fresh data to send, regardless of GPS update cadence.
        if (settings.webhookEnabled && settings.webhookUrl.isNotBlank()) {
            updateWebhookPayload(location, settings, wearableReading?.heartRate)
        }

        // Throttle notification updates to at most once per 2 seconds
        val now = System.currentTimeMillis()
        if (now - lastNotificationTime >= 2000L) {
            val satInfo = satelliteTracker.satelliteInfo.value
            val speedKmh = LocationTracker.msToKmh(location.speed)
            val accuracyStr = if (point?.isAccurate == false) " [!]" else ""
            val hrStr = wearableReading?.heartRate?.let { " | HR $it" } ?: ""
            val modeStr = when (settings.trackingMode) {
                TrackingMode.HIGH_ACCURACY -> ""
                TrackingMode.ENERGY_EFFICIENCY -> " | ECO"
                TrackingMode.AI_BATTERY_SAVER -> " | AI"
            }
            updateNotification(
                "Recording | ${pointCount} pts | ${String.format("%.1f", speedKmh)} km/h | SAT ${satInfo.usedInFix}/${satInfo.totalVisible}$hrStr$modeStr$accuracyStr"
            )
            return now
        }
        return lastNotificationTime
    }

    private fun updateWebhookPayload(
        location: android.location.Location,
        settings: TrackingSettings,
        heartRate: Int? = null
    ) {
        val speedKmh = LocationTracker.msToKmh(location.speed)
        val activity = ActivityType.fromSpeed(speedKmh, settings.activityConfigs)
        latestWebhookPayload = WebhookLocationPayload(
            key = settings.webhookKey,
            timestamp = location.time / 1000,
            lat = location.latitude,
            lng = location.longitude,
            alt = if (location.hasAltitude()) location.altitude else null,
            speedKmh = speedKmh,
            bearing = if (location.hasBearing()) location.bearing else null,
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            activity = activity.name.lowercase(),
            heartRate = heartRate,
            battery = getBatteryLevel()
        )
    }

    /**
     * Independent ticker that sends the latest cached payload at a fixed
     * cadence.  Runs as a sibling coroutine inside [trackWithSettings]'s
     * [coroutineScope], so it is automatically cancelled when settings
     * change or tracking stops.
     */
    private suspend fun runWebhookTicker(settings: TrackingSettings) {
        Log.i(TAG, "Webhook ticker started (interval=${settings.webhookIntervalMs}ms)")
        while (true) {
            delay(settings.webhookIntervalMs)
            if (isPaused) continue
            val payload = latestWebhookPayload ?: continue
            val sent = webhookSender.send(settings.webhookUrl, settings.webhookKey, payload)
            if (!sent) {
                Log.w(TAG, "Webhook delivery failed — ${webhookSender.pendingCount} queued for retry")
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
                // Log battery level at end before ending track
                val batteryAtEnd = getBatteryLevel()
                batteryAtEnd?.let { battery ->
                    repository.updateTrackBatteryEnd(trackId, battery)
                    Log.i(TAG, "Battery at end: $battery% (started at ${batteryAtStart ?: "?"}%)")
                }
                repository.endTrack(trackId)
                Log.i(TAG, "Track ended: $trackId | $pointCount points recorded")
            }
            trackingJob?.cancel()
            trackingJob = null
            wearableScanJob?.cancel()
            wearableScanJob = null
            currentTrackId = null
            latestWebhookPayload = null
            _isRunning.value = false
            locationTracker.stopTracking()
            satelliteTracker.stopMonitoring()
            motionSensorManager.stopMonitoring()
            batteryMonitor.stopMonitoring()
            wearableManager.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun getBatteryLevel(): Int? {
        return try {
            val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read battery level: ${e.message}")
            null
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
            .setContentTitle("Pathwise")
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
        _isRunning.value = false
        try { unregisterReceiver(locationProviderReceiver) } catch (_: Exception) {}
        serviceScope.cancel()
        locationTracker.stopTracking()
        satelliteTracker.stopMonitoring()
        motionSensorManager.stopMonitoring()
        batteryMonitor.stopMonitoring()
        wearableManager.disconnect()
        super.onDestroy()
    }
}
