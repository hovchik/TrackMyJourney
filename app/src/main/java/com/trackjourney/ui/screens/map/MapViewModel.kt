package com.trackjourney.ui.screens.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.bluetooth.WearableConnectionState
import com.trackjourney.data.bluetooth.WearableManager
import com.trackjourney.data.bluetooth.WearableReading
import com.trackjourney.data.local.SettingsDataStore
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
    val wearableReading: WearableReading? = null,
    val isViewingSavedTrack: Boolean = false,
    val viewedTrackName: String = "",
    val completedTrack: CompletedTrackInfo? = null,
    val activityDurations: Map<ActivityType, Long> = emptyMap(),
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val batteryStart: Int? = null,
    val batteryEnd: Int? = null,
    val rideCost: Double? = null
)

data class CompletedTrackInfo(
    val trackId: String,
    val name: String,
    val distanceKm: Double,
    val durationMs: Long,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val activityType: ActivityType,
    val startPlace: String?,
    val endPlace: String?,
    val pointCount: Int,
    val caloriesBurned: Double = 0.0,
    val batteryStart: Int? = null,
    val batteryEnd: Int? = null,
    val activityDurations: Map<ActivityType, Long> = emptyMap(),
    val rideCost: Double? = null
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val app: Application,
    private val repository: TrackRepository,
    private val wearableManager: WearableManager,
    private val settingsDataStore: SettingsDataStore,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    val subscriptionStatus: StateFlow<SubscriptionStatus> = settingsDataStore.subscriptionStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SubscriptionStatus())

    val allTracks: StateFlow<List<TrackSession>> = repository.getAllTracks()
        .map { tracks -> tracks.filter { !it.isActive && it.endTime != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var wearableJob: Job? = null
    private var wearableReadingJob: Job? = null
    private var savedTrackJob: Job? = null

    init {
        observeActiveTrack()
        observeSettings()
        observeServiceRunning()
        fetchInitialLocation()

        // Check if navigated with a trackId to view a saved track
        val trackId = savedStateHandle.get<String>("trackId")
        if (trackId != null) {
            loadSavedTrack(trackId)
        }
    }

    private fun fetchInitialLocation() {
        viewModelScope.launch {
            // Use cached last known location (no GPS activation)
            val location = repository.getCurrentLocation()
            if (location != null) {
                _uiState.update {
                    it.copy(
                        currentLatitude = location.latitude,
                        currentLongitude = location.longitude
                    )
                }
            }
        }
    }

    fun loadSavedTrack(trackId: String) {
        savedTrackJob?.cancel()
        savedTrackJob = viewModelScope.launch {
            val track = repository.getTrackById(trackId) ?: return@launch
            repository.getPointsForTrack(trackId).collect { points ->
                _uiState.update {
                    it.copy(
                        trackPoints = points,
                        pointCount = points.size,
                        distanceKm = track.distanceMeters / 1000.0,
                        isViewingSavedTrack = true,
                        viewedTrackName = track.name.ifEmpty { "Unnamed Track" },
                        currentActivity = track.activityType,
                        activityDurations = computeActivityDurations(points),
                        avgSpeedKmh = track.avgSpeedKmh,
                        maxSpeedKmh = track.maxSpeedKmh,
                        batteryStart = track.batteryStart,
                        batteryEnd = track.batteryEnd,
                        rideCost = track.rideCost
                    )
                }
            }
        }
    }

    fun clearSavedTrack() {
        savedTrackJob?.cancel()
        _uiState.update {
            it.copy(
                trackPoints = emptyList(),
                pointCount = 0,
                distanceKm = 0.0,
                isViewingSavedTrack = false,
                viewedTrackName = "",
                batteryStart = null,
                batteryEnd = null,
                rideCost = null
            )
        }
    }

    private fun observeActiveTrack() {
        viewModelScope.launch {
            repository.observeActiveTrack().collect { track ->
                val wasTracking = _uiState.value.isTracking
                val isNowTracking = track?.isActive == true

                // If live tracking starts, clear any saved track view
                if (isNowTracking && _uiState.value.isViewingSavedTrack) {
                    clearSavedTrack()
                }

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

                // Capture completed track info and clear satellite/wearable when tracking stops
                if (!isNowTracking && wasTracking) {
                    val prevTrack = _uiState.value.currentTrack
                    if (prevTrack != null) {
                        val endTime = prevTrack.endTime ?: System.currentTimeMillis()
                        _uiState.update {
                            it.copy(
                                completedTrack = CompletedTrackInfo(
                                    trackId = prevTrack.id,
                                    name = prevTrack.name,
                                    distanceKm = prevTrack.distanceMeters / 1000.0,
                                    durationMs = endTime - prevTrack.startTime,
                                    avgSpeedKmh = prevTrack.avgSpeedKmh,
                                    maxSpeedKmh = prevTrack.maxSpeedKmh,
                                    activityType = prevTrack.activityType,
                                    startPlace = prevTrack.startPlaceName,
                                    endPlace = prevTrack.endPlaceName,
                                    pointCount = _uiState.value.pointCount,
                                    caloriesBurned = prevTrack.caloriesBurned,
                                    batteryStart = prevTrack.batteryStart,
                                    batteryEnd = prevTrack.batteryEnd,
                                    activityDurations = it.activityDurations,
                                    rideCost = prevTrack.rideCost
                                )
                            )
                        }
                    }
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

                // Observe points only for active track (don't override saved track view)
                if (isNowTracking) {
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
                                    gpsAccuracy = lastPoint?.accuracy,
                                    activityDurations = computeActivityDurations(points),
                                    avgSpeedKmh = track.avgSpeedKmh,
                                    maxSpeedKmh = track.maxSpeedKmh
                                )
                            }
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

    /**
     * If the foreground service stops (e.g. location toggle turned off,
     * system kills it), automatically end the active track so the
     * Start button reappears.
     */
    private fun observeServiceRunning() {
        viewModelScope.launch {
            TrackingService.isRunning.collect { running ->
                if (!running && _uiState.value.isTracking) {
                    // Service died but DB still says active — end it
                    _uiState.value.currentTrack?.id?.let { trackId ->
                        repository.endTrack(trackId)
                    }
                }
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

    fun dismissCompletedTrack() {
        _uiState.update { it.copy(completedTrack = null) }
    }

    companion object {
        /**
         * Compute how many milliseconds were spent in each activity type,
         * by walking through consecutive track points and attributing the
         * time gap between two points to the first point's activity.
         */
        fun computeActivityDurations(points: List<TrackPoint>): Map<ActivityType, Long> {
            if (points.size < 2) return emptyMap()
            val durations = mutableMapOf<ActivityType, Long>()
            for (i in 0 until points.size - 1) {
                val dt = points[i + 1].timestamp - points[i].timestamp
                if (dt in 1..300_000) { // ignore gaps > 5 min (pauses)
                    val activity = points[i].activityType
                    durations[activity] = (durations[activity] ?: 0L) + dt
                }
            }
            return durations
        }
    }
}
