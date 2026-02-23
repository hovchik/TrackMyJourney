package com.trackjourney.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.bluetooth.WearableConnectionState
import com.trackjourney.data.bluetooth.WearableManager
import com.trackjourney.data.bluetooth.WearableReading
import com.trackjourney.data.location.MotionSensorManager
import com.trackjourney.data.location.SatelliteInfo
import com.trackjourney.data.model.ExportFormat
import com.trackjourney.data.model.TrackingSettings
import com.trackjourney.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val wearableManager: WearableManager
) : ViewModel() {

    val settings: StateFlow<TrackingSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrackingSettings())

    val satelliteInfo: StateFlow<SatelliteInfo> = repository.satelliteInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SatelliteInfo())

    val wearableState: StateFlow<WearableConnectionState> = wearableManager.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WearableConnectionState.Disconnected)

    val wearableReading: StateFlow<WearableReading?> = wearableManager.latestReading
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val motionState: StateFlow<MotionSensorManager.MotionState> = repository.motionSensorManager.motionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MotionSensorManager.MotionState())

    fun updateRecordInterval(intervalMs: Long) {
        viewModelScope.launch {
            repository.updateSettings { updateRecordInterval(intervalMs) }
        }
    }

    fun updateMinDistance(meters: Float) {
        viewModelScope.launch {
            repository.updateSettings { updateMinDistance(meters) }
        }
    }

    fun updateAutoDetect(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { updateAutoDetectActivity(enabled) }
        }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { updateKeepScreenOn(enabled) }
        }
    }

    fun updateExportFormat(format: ExportFormat) {
        viewModelScope.launch {
            repository.updateSettings { updateExportFormat(format) }
        }
    }
}
