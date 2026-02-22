package com.trackjourney.ui.screens.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.bluetooth.WearableConnectionState
import com.trackjourney.data.bluetooth.WearableDevice
import com.trackjourney.data.bluetooth.WearableReading
import com.trackjourney.data.model.*
import com.trackjourney.data.repository.TrackRepository
import com.trackjourney.service.TrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val wearableState: WearableConnectionState = WearableConnectionState.Disconnected,
    val wearableReading: WearableReading? = null,
    val discoveredDevices: List<WearableDevice> = emptyList(),
    val settings: TrackingSettings = TrackingSettings(),
    val pointCount: Int = 0,
    val distanceKm: Double = 0.0,
    val elapsedTime: String = "00:00",
    val currentLatitude: Double? = null,
    val currentLongitude: Double? = null
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val app: Application,
    private val repository: TrackRepository
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        observeActiveTrack()
        observeWearable()
        observeSettings()
        fetchCurrentLocation()
    }

    private fun fetchCurrentLocation() {
        viewModelScope.launch {
            try {
                val location = repository.getCurrentLocation()
                location?.let { loc ->
                    _uiState.update {
                        it.copy(
                            currentLatitude = loc.latitude,
                            currentLongitude = loc.longitude
                        )
                    }
                }
            } catch (_: Exception) {
                // Location may not be available yet
            }
        }
    }

    private fun observeActiveTrack() {
        viewModelScope.launch {
            repository.observeActiveTrack().collect { track ->
                _uiState.update {
                    it.copy(
                        currentTrack = track,
                        isTracking = track?.isActive == true
                    )
                }

                // Observe points for active track
                track?.id?.let { trackId ->
                    repository.getPointsForTrack(trackId).collect { points ->
                        val lastPoint = points.lastOrNull()
                        _uiState.update {
                            it.copy(
                                trackPoints = points,
                                pointCount = points.size,
                                currentSpeed = lastPoint?.speedKmh ?: 0f,
                                currentActivity = lastPoint?.activityType ?: ActivityType.UNKNOWN,
                                distanceKm = (track.distanceMeters) / 1000.0
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observeWearable() {
        viewModelScope.launch {
            repository.wearableConnectionState.collect { state ->
                _uiState.update { it.copy(wearableState = state) }
            }
        }
        viewModelScope.launch {
            repository.wearableReading.collect { reading ->
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
        // Send pause intent to service
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

    // ─── WEARABLE ───────────────────────────────────────

    fun scanForWearables() {
        viewModelScope.launch {
            _uiState.update { it.copy(discoveredDevices = emptyList()) }
            repository.scanForWearables().collect { device ->
                _uiState.update { state ->
                    val devices = state.discoveredDevices.toMutableList()
                    if (devices.none { it.address == device.address }) {
                        devices.add(device)
                    }
                    state.copy(discoveredDevices = devices)
                }
            }
        }
    }

    fun connectWearable(device: WearableDevice) {
        repository.connectWearable(device)
    }

    fun disconnectWearable() {
        repository.disconnectWearable()
    }
}
