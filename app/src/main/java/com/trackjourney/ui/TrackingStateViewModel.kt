package com.trackjourney.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.location.SatelliteInfo
import com.trackjourney.data.model.ActivityType
import com.trackjourney.data.repository.TrackRepository
import com.trackjourney.service.TrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrackingBarState(
    val isTracking: Boolean = false,
    val trackName: String = "",
    val pointCount: Int = 0,
    val distanceKm: Double = 0.0,
    val currentSpeedKmh: Float = 0f,
    val satelliteInfo: SatelliteInfo = SatelliteInfo(),
    val activityType: ActivityType = ActivityType.UNKNOWN,
    val startTime: Long = 0L,
    val gpsAccuracy: Float? = null
)

@HiltViewModel
class TrackingStateViewModel @Inject constructor(
    private val app: Application,
    private val repository: TrackRepository
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(TrackingBarState())
    val state: StateFlow<TrackingBarState> = _state.asStateFlow()

    private var pointsJob: Job? = null
    private var satelliteJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeActiveTrack().collect { track ->
                val isNowTracking = track?.isActive == true

                _state.update {
                    it.copy(
                        isTracking = isNowTracking,
                        trackName = track?.name ?: "",
                        distanceKm = (track?.distanceMeters ?: 0.0) / 1000.0,
                        activityType = track?.activityType ?: ActivityType.UNKNOWN,
                        startTime = track?.startTime ?: 0L
                    )
                }

                pointsJob?.cancel()
                satelliteJob?.cancel()

                if (isNowTracking) {
                    // Only observe points and satellites while tracking
                    track?.id?.let { trackId ->
                        pointsJob = launch {
                            repository.getPointsForTrack(trackId).collect { points ->
                                val lastPt = points.lastOrNull()
                                _state.update {
                                    it.copy(
                                        pointCount = points.size,
                                        currentSpeedKmh = lastPt?.speedKmh ?: 0f,
                                        activityType = lastPt?.activityType ?: ActivityType.UNKNOWN,
                                        gpsAccuracy = lastPt?.accuracy
                                    )
                                }
                            }
                        }
                    }
                    satelliteJob = launch {
                        repository.satelliteInfo.collect { satInfo ->
                            _state.update { it.copy(satelliteInfo = satInfo) }
                        }
                    }
                } else {
                    // Clear satellite info when not tracking
                    _state.update {
                        it.copy(
                            pointCount = 0,
                            currentSpeedKmh = 0f,
                            satelliteInfo = SatelliteInfo()
                        )
                    }
                }
            }
        }
    }

    fun startTracking(name: String = "") {
        TrackingService.startTracking(app.applicationContext, name)
    }

    fun stopTracking() {
        TrackingService.stopTracking(app.applicationContext)
    }
}
