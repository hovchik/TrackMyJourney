package com.trackjourney.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.bluetooth.WearableConnectionState
import com.trackjourney.data.bluetooth.WearableDevice
import com.trackjourney.data.bluetooth.WearableReading
import com.trackjourney.data.model.ExportFormat
import com.trackjourney.data.model.TrackingSettings
import com.trackjourney.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: TrackRepository
) : ViewModel() {

    val settings: StateFlow<TrackingSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrackingSettings())

    val wearableState: StateFlow<WearableConnectionState> = repository.wearableConnectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WearableConnectionState.Disconnected)

    val wearableReading: StateFlow<WearableReading?> = repository.wearableReading
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _discoveredDevices = MutableStateFlow<List<WearableDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<WearableDevice>> = _discoveredDevices.asStateFlow()

    fun scanForWearables() {
        viewModelScope.launch {
            _discoveredDevices.value = emptyList()
            repository.scanForWearables().collect { device ->
                _discoveredDevices.update { devices ->
                    if (devices.none { it.address == device.address }) devices + device else devices
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

    fun updateHeartRate(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { updateEnableHeartRate(enabled) }
        }
    }

    fun updateSpO2(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { updateEnableSpO2(enabled) }
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
