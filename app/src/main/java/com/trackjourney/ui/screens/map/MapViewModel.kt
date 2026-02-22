package com.trackjourney.ui.screens.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.bluetooth.WearableConnectionState
import com.trackjourney.data.bluetooth.WearableManager
import com.trackjourney.data.bluetooth.WearableReading
import com.trackjourney.data.location.SatelliteInfo
import com.trackjourney.data.model.*
import com.trackjourney.data.repository.TrackRepository
import com.trackjourney.service.TrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val isTracking: Boolean = false,
    val isPaused: Boolean = false,
    val currentTrack: TrackSession? = null,
    val trackPoints: List<TrackPoint> = emptyList(),
    val currentSpeed: Float = 0f,
    val currentActivity: ActivityType = ActivityType.UNKNOWN,
    val settings: TrackingSettings = TrackingSettings(),
    val pointCount: Int = 0,
    val distanceKm: Double = 0.0,
    val elapsedTime: String = "00:00",
    val currentLatitude: Double? = null,
    val currentLongitude: Double? = null,
    val satelliteInfo: SatelliteInfo = SatelliteInfo(),
    val gpsAccuracy: Float? = null,
    val wearableState: WearableConnectionState = WearableConnectionState.Disconnected,
    val wearableReading: WearableReading? = null
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val app: Application,
    private val repository: TrackRepository,
    private val wearableManager: WearableManager
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var wearableJob: Job? = null
    private var wearableReadingJob: Job? = null

    init {
        observeActiveTrack()
        observeSettings()
    }

    private fun observeActiveTrack() {
        viewModelScope.launch {
            repository.observeActiveTrack().collect { track ->
                val wasTracking = _uiState.value.isTracking
                val isNowTracking = track?.isActive == true

                _uiState.update {
                    it.copy(
                        currentTrack = track,
                        isTracking = isNowTracking
                    )
                }

                // Only observe satellite info and wearable when actively tracking
                if (isNowTracking && !wasTracking) {
                    observeSatellites()
                    observeWearable()
                }

                // Clear satellite/wearable info when tracking stops
                if (!isNowTracking && wasTracking) {
                    wearableJob?.cancel()
                    wearableReadingJob?.cancel()
                    _uiState.update {
                        it.copy(
                            satelliteInfo = SatelliteInfo(),
                            gpsAccuracy = null,
                            wearableState = WearableConnectionState.Disconnected,
                            wearableReading = null
                        )
                    }
                }

                // Observe points only for active track
                track?.id?.let { trackId ->
                    repository.getPointsForTrack(trackId).collect { points ->
                        val lastPoint = points.lastOrNull()
                        _uiState.update {
                            it.copy(
                                trackPoints = points,
                                pointCount = points.size,
                                currentSpeed = lastPoint?.speedKmh ?: 0f,
                                currentActivity = lastPoint?.activityType ?: ActivityType.UNKNOWN,
                                distanceKm = (track.distanceMeters) / 1000.0,
                                gpsAccuracy = lastPoint?.accuracy
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observeSatellites() {
        viewModelScope.launch {
            repository.satelliteInfo.collect { satInfo ->
                _uiState.update { it.copy(satelliteInfo = satInfo) }
            }
        }
    }

    private fun observeWearable() {
        wearableJob = viewModelScope.launch {
            wearableManager.connectionState.collect { state ->
                _uiState.update { it.copy(wearableState = state) }
            }
        }
        wearableReadingJob = viewModelScope.launch {
            wearableManager.latestReading.collect { reading ->
                _uiState.update { it.copy(wearableReading = reading) }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    // ─── TRACKING CONTROLS ──────────────────────────────

    fun startTracking(name: String = "") {
        TrackingService.startTracking(app.applicationContext, name)
    }

    fun stopTracking() {
        TrackingService.stopTracking(app.applicationContext)
    }

    fun pauseTracking() {
        _uiState.update { it.copy(isPaused = true) }
        val intent = android.content.Intent(app, TrackingService::class.java).apply {
            action = TrackingService.ACTION_PAUSE
        }
        app.startService(intent)
    }

    fun resumeTracking() {
        _uiState.update { it.copy(isPaused = false) }
        val intent = android.content.Intent(app, TrackingService::class.java).apply {
            action = TrackingService.ACTION_RESUME
        }
        app.startService(intent)
    }
}
