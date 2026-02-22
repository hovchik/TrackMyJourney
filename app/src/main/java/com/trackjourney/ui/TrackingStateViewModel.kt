package com.trackjourney.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.location.SatelliteInfo
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
    val satelliteInfo: SatelliteInfo = SatelliteInfo()
)

@HiltViewModel
class TrackingStateViewModel @Inject constructor(
    private val app: Application,
    private val repository: TrackRepository
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(TrackingBarState())
    val state: StateFlow<TrackingBarState> = _state.asStateFlow()

    private var pointsJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeActiveTrack().collect { track ->
                _state.update {
                    it.copy(
                        isTracking = track?.isActive == true,
                        trackName = track?.name ?: "",
                        distanceKm = (track?.distanceMeters ?: 0.0) / 1000.0
                    )
                }

                pointsJob?.cancel()
                track?.id?.let { trackId ->
                    pointsJob = launch {
                        repository.getPointsForTrack(trackId).collect { points ->
                            _state.update {
                                it.copy(
                                    pointCount = points.size,
                                    currentSpeedKmh = points.lastOrNull()?.speedKmh ?: 0f
                                )
                            }
                        }
                    }
                }
            }
        }

        // Observe satellite info
        viewModelScope.launch {
            repository.satelliteInfo.collect { satInfo ->
                _state.update { it.copy(satelliteInfo = satInfo) }
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
