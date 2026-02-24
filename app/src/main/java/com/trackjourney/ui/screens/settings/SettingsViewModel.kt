package com.trackjourney.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.bluetooth.WearableConnectionState
import com.trackjourney.data.bluetooth.WearableManager
import com.trackjourney.data.bluetooth.WearableReading
import com.trackjourney.data.location.MotionSensorManager
import com.trackjourney.data.location.SatelliteInfo
import com.trackjourney.data.model.*
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

    fun updateUserName(name: String) {
        viewModelScope.launch {
            repository.updateSettings { updateUserName(name) }
        }
    }

    fun updateUserWeightKg(weight: Float) {
        viewModelScope.launch {
            repository.updateSettings { updateUserWeightKg(weight) }
        }
    }

    fun updateUserHeightCm(height: Float) {
        viewModelScope.launch {
            repository.updateSettings { updateUserHeightCm(height) }
        }
    }

    fun updateTrackingMode(mode: TrackingMode) {
        viewModelScope.launch {
            repository.updateSettings { updateTrackingMode(mode) }
        }
    }

    // ─── Car Profiles ────────────────────────────────────

    val allCars: StateFlow<List<CarProfile>> = repository.getAllCars()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addCar(car: CarProfile) {
        viewModelScope.launch { repository.addCar(car) }
    }

    fun updateCar(car: CarProfile) {
        viewModelScope.launch { repository.updateCar(car) }
    }

    fun deleteCar(carId: String) {
        viewModelScope.launch { repository.deleteCar(carId) }
    }

    fun selectCar(carId: String?) {
        viewModelScope.launch { repository.selectCar(carId) }
    }

    // ─── Full database export / import ──────────────────

    private val _exportedBackupFile = MutableStateFlow<java.io.File?>(null)
    val exportedBackupFile: StateFlow<java.io.File?> = _exportedBackupFile.asStateFlow()

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    private val _importResult = MutableStateFlow<TrackRepository.FullImportResult?>(null)
    val importResult: StateFlow<TrackRepository.FullImportResult?> = _importResult.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    fun exportAllData() {
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val file = repository.exportAllData()
                if (file != null) {
                    _exportedBackupFile.value = file
                } else {
                    _exportError.value = "Export failed"
                }
            } catch (e: Exception) {
                _exportError.value = e.message ?: "Export failed"
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun importAllData(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val result = repository.importAllData(uri)
                _importResult.value = result
            } catch (e: Exception) {
                _importError.value = e.message ?: "Import failed"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun clearExportedBackupFile() { _exportedBackupFile.value = null }
    fun setExportError(message: String) { _exportError.value = message }
    fun clearExportError() { _exportError.value = null }
    fun clearImportResult() { _importResult.value = null }
    fun clearImportError() { _importError.value = null }
}
