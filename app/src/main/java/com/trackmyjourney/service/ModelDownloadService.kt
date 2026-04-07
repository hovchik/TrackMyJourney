package com.trackmyjourney.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.trackmyjourney.R
import com.trackmyjourney.data.ai.models.InstallProgress
import com.trackmyjourney.data.ai.models.ModelCatalog
import com.trackmyjourney.data.ai.models.ModelInstallState
import com.trackmyjourney.data.ai.models.ModelInstaller
import com.trackmyjourney.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@AndroidEntryPoint
class ModelDownloadService : Service() {

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "model_download_channel"
        private const val WAKELOCK_TAG = "TrackMyJourney:ModelDownload"

        const val ACTION_START_DOWNLOAD = "com.trackmyjourney.ACTION_START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.trackmyjourney.ACTION_CANCEL_DOWNLOAD"
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_DOWNLOAD_URL = "download_url"

        private val _isDownloading = MutableStateFlow(false)
        val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

        fun startDownload(context: Context, modelId: String, downloadUrl: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_MODEL_ID, modelId)
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
            }
            context.startForegroundService(intent)
        }

        fun cancelDownload(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            }
            context.startService(intent)
        }
    }

    @Inject lateinit var modelInstaller: ModelInstaller

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var progressObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return START_NOT_STICKY
                val url = intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: return START_NOT_STICKY
                startModelDownload(modelId, url)
            }
            ACTION_CANCEL_DOWNLOAD -> {
                cancelAndStop()
            }
        }
        return START_NOT_STICKY
    }

    private fun startModelDownload(modelId: String, url: String) {
        if (downloadJob?.isActive == true) {
            Log.w(TAG, "Download already in progress, ignoring request")
            return
        }

        val model = ModelCatalog.findById(modelId)
        if (model == null) {
            Log.e(TAG, "Model not found in catalog: $modelId")
            stopSelf()
            return
        }

        // Acquire WakeLock to keep CPU running during download
        acquireWakeLock()

        // Start foreground with initial notification
        val notification = buildNotification("Downloading ${model.displayName}...", 0)
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        _isDownloading.value = true

        // Observe install progress to update notification
        progressObserverJob = serviceScope.launch {
            modelInstaller.installProgress.filterNotNull().collect { progress ->
                if (progress.modelId == modelId) {
                    when (progress.state) {
                        ModelInstallState.DOWNLOADING -> {
                            updateNotification(
                                "Downloading ${model.displayName}... ${progress.progressPercent}%",
                                progress.progressPercent
                            )
                        }
                        ModelInstallState.INSTALLING -> {
                            updateNotification("Verifying ${model.displayName}...", -1)
                        }
                        ModelInstallState.INSTALLED -> {
                            updateNotification("${model.displayName} installed", 100)
                            delay(1500)
                            finishAndStop()
                        }
                        ModelInstallState.FAILED -> {
                            updateNotification(
                                "Download failed: ${progress.errorMessage ?: "Unknown error"}",
                                -1
                            )
                            delay(3000)
                            finishAndStop()
                        }
                        else -> {}
                    }
                }
            }
        }

        // Start the actual download
        downloadJob = serviceScope.launch {
            val result = modelInstaller.downloadModel(model, url)
            result.onFailure { e ->
                Log.e(TAG, "Download failed: ${e.message}")
            }
            result.onSuccess {
                Log.i(TAG, "Download completed: $modelId")
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        ).apply {
            // Max 2 hours to prevent indefinite lock
            acquire(2 * 60 * 60 * 1000L)
        }
        Log.i(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun cancelAndStop() {
        downloadJob?.cancel()
        downloadJob = null
        modelInstaller.clearProgress()
        finishAndStop()
    }

    private fun finishAndStop() {
        _isDownloading.value = false
        progressObserverJob?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ─── NOTIFICATIONS ──────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows AI model download progress"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ModelDownloadService::class.java).apply { action = ACTION_CANCEL_DOWNLOAD },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pathwise — Model Download")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Cancel", cancelIntent)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                when {
                    progress in 0..99 -> setProgress(100, progress, false)
                    progress < 0 -> setProgress(0, 0, true) // indeterminate
                    // progress == 100 -> no progress bar
                }
            }
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val notification = buildNotification(text, progress)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        _isDownloading.value = false
        downloadJob?.cancel()
        progressObserverJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}
