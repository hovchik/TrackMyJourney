package com.trackmyjourney.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackmyjourney.data.bluetooth.WearableConnectionState
import com.trackmyjourney.data.bluetooth.WearableManager
import com.trackmyjourney.data.bluetooth.WearableReading
import com.trackmyjourney.data.ai.models.AiExecutionMode
import com.trackmyjourney.data.ai.models.AiPreferences
import com.trackmyjourney.data.ai.models.LocalAiModel
import com.trackmyjourney.data.ai.models.LocalModelManager
import com.trackmyjourney.data.local.SettingsDataStore
import com.trackmyjourney.data.location.MotionSensorManager
import com.trackmyjourney.data.location.SatelliteInfo
import com.trackmyjourney.data.model.*
import com.trackmyjourney.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val wearableManager: WearableManager,
    private val settingsDataStore: SettingsDataStore,
    private val localModelManager: LocalModelManager,
    private val aiPreferences: AiPreferences
) : ViewModel() {

    val settings: StateFlow<TrackingSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrackingSettings())

    val installedModels: StateFlow<List<LocalAiModel>> = localModelManager.observeInstalledModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteLocalModel(modelId: String) {
        viewModelScope.launch {
            localModelManager.deleteModel(modelId)
        }
    }

    val subscriptionStatus: StateFlow<SubscriptionStatus> = settingsDataStore.subscriptionStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SubscriptionStatus())

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

    fun updateAutoStartTracking(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { updateAutoStartTracking(enabled) }
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

    fun updateCurrency(currency: String) {
        viewModelScope.launch {
            repository.updateSettings { updateCurrency(currency) }
        }
    }

    fun updateAiMode(mode: AiMode) {
        viewModelScope.launch {
            repository.updateSettings { updateAiMode(mode) }
            // Sync with AiPreferences so AiProviderSelector picks the right provider
            val executionMode = when (mode) {
                AiMode.LOCAL_MODEL -> AiExecutionMode.CUSTOM_LOCAL
                AiMode.CLOUD_AI -> AiExecutionMode.CLOUD
                else -> AiExecutionMode.CUSTOM_LOCAL
            }
            aiPreferences.setSelectedMode(executionMode)
        }
    }

    // ─── Cloud AI Configuration ─────────────────────────

    private val _showCloudAiWizard = MutableStateFlow(false)
    val showCloudAiWizard: StateFlow<Boolean> = _showCloudAiWizard.asStateFlow()

    fun openCloudAiWizard() { _showCloudAiWizard.value = true }
    fun dismissCloudAiWizard() { _showCloudAiWizard.value = false }

    fun saveCloudAiConfig(provider: CloudAiProvider, apiKey: String, endpoint: String, model: String) {
        viewModelScope.launch {
            repository.updateSettings { updateCloudAiProvider(provider) }
            repository.updateSettings { updateCloudAiApiKey(apiKey) }
            repository.updateSettings { updateCloudAiEndpoint(endpoint) }
            repository.updateSettings { updateCloudAiModel(model) }
            repository.updateSettings { updateAiMode(AiMode.CLOUD_AI) }
            // Sync with AiPreferences so AiProviderSelector uses Cloud
            aiPreferences.setSelectedMode(AiExecutionMode.CLOUD)
            aiPreferences.setCloudApiKey(apiKey)
            // Map CloudAiProvider to CloudProviderType
            val providerType = when (provider) {
                CloudAiProvider.ANTHROPIC -> "CLAUDE"
                CloudAiProvider.OPENAI -> "OPENAI"
                else -> provider.name
            }
            aiPreferences.setCloudProviderType(providerType)
            _showCloudAiWizard.value = false
        }
    }

    // ─── Webhook Settings ──────────────────────────────

    fun updateWebhookEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { updateWebhookEnabled(enabled) }
        }
    }

    fun updateWebhookUrl(url: String) {
        viewModelScope.launch {
            repository.updateSettings { updateWebhookUrl(url) }
        }
    }

    fun updateWebhookKey(key: String) {
        viewModelScope.launch {
            repository.updateSettings { updateWebhookKey(key) }
        }
    }

    fun updateWebhookIntervalMs(intervalMs: Long) {
        viewModelScope.launch {
            repository.updateSettings { updateWebhookIntervalMs(intervalMs) }
        }
    }

    // ─── Local AI Model Reachability ─────────────────────

    private val _localModelReachable = MutableStateFlow(false)
    val localModelReachable: StateFlow<Boolean> = _localModelReachable.asStateFlow()

    private val _localModelChecking = MutableStateFlow(false)
    val localModelChecking: StateFlow<Boolean> = _localModelChecking.asStateFlow()

    init {
        checkLocalModelReachability()
    }

    fun checkLocalModelReachability() {
        viewModelScope.launch {
            _localModelChecking.value = true
            _localModelReachable.value = withContext(Dispatchers.IO) {
                try {
                    // Check if Android NNAPI is available (API 27+)
                    val nnApiAvailable = android.os.Build.VERSION.SDK_INT >= 27
                    // Check if the device has a local ML runtime accessible via system service
                    if (nnApiAvailable) {
                        // Probe the NNAPI by loading the native library
                        System.loadLibrary("neuralnetworks")
                        true
                    } else {
                        false
                    }
                } catch (_: UnsatisfiedLinkError) {
                    // NNAPI native lib not bundled but NNAPI may still work via TFLite delegation
                    android.os.Build.VERSION.SDK_INT >= 27
                } catch (_: Exception) {
                    false
                }
            }
            _localModelChecking.value = false
        }
    }

    // ─── Activity Configs ──────────────────────────────────

    fun updateActivityConfigs(configs: List<ActivityConfig>) {
        viewModelScope.launch {
            repository.updateSettings { updateActivityConfigs(configs) }
        }
    }

    fun toggleActivityConfig(activityType: String, isActive: Boolean) {
        viewModelScope.launch {
            val current = settings.value.activityConfigs.toMutableList()
            val idx = current.indexOfFirst { it.activityType == activityType }
            if (idx >= 0) {
                current[idx] = current[idx].copy(isActive = isActive)
                repository.updateSettings { updateActivityConfigs(current) }
            }
        }
    }

    fun addActivityConfig(config: ActivityConfig) {
        viewModelScope.launch {
            val current = settings.value.activityConfigs.toMutableList()
            current.add(config)
            repository.updateSettings { updateActivityConfigs(current) }
        }
    }

    fun updateActivitySpeedRange(activityType: String, minSpeed: Float, maxSpeed: Float) {
        viewModelScope.launch {
            val current = settings.value.activityConfigs.toMutableList()
            val idx = current.indexOfFirst { it.activityType == activityType }
            if (idx >= 0) {
                current[idx] = current[idx].copy(minSpeedKmh = minSpeed, maxSpeedKmh = maxSpeed)
                repository.updateSettings { updateActivityConfigs(current) }
            }
        }
    }

    fun removeActivityConfig(activityType: String) {
        viewModelScope.launch {
            val current = settings.value.activityConfigs.filter { it.activityType != activityType }
            repository.updateSettings { updateActivityConfigs(current) }
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
                val count = repository.getTrackCount()
                if (count == 0) {
                    _exportError.value = "Nothing to export"
                    return@launch
                }
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
