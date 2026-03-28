package com.trackjourney.ui.screens.aiengine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.ai.models.*
import com.trackjourney.data.ai.models.CloudProviderType
import com.trackjourney.data.local.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiEngineSettingsUiState(
    val selectedMode: AiExecutionMode = AiExecutionMode.CUSTOM_LOCAL,
    val activeModel: LocalAiModel? = null,
    val installedModels: List<LocalAiModel> = emptyList(),
    val storageUsedMb: Long = 0,
    val performanceNote: String? = null,
    val isScanning: Boolean = false,
    val scanResultMessage: String? = null,
    val cloudProviderName: String = ""
)

@HiltViewModel
class AiEngineSettingsViewModel @Inject constructor(
    private val aiPreferences: AiPreferences,
    private val localModelManager: LocalModelManager,
    private val modelInstaller: ModelInstaller,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _scanState = MutableStateFlow(false)
    private val _scanResult = MutableStateFlow<String?>(null)
    private val _storageUsed = MutableStateFlow(0L)

    private val _cloudProviderName = MutableStateFlow("")

    val state: StateFlow<AiEngineSettingsUiState> = combine(
        aiPreferences.observeSelectedMode(),
        localModelManager.observeActiveModel(),
        localModelManager.observeInstalledModels(),
        combine(_scanState, _scanResult, _cloudProviderName) { scanning, scanMsg, cloud ->
            Triple(scanning, scanMsg, cloud)
        }
    ) { mode, active, installed, (scanning, scanMsg, cloudName) ->
        AiEngineSettingsUiState(
            selectedMode = mode,
            activeModel = active,
            installedModels = installed,
            storageUsedMb = _storageUsed.value,
            performanceNote = active?.let { "RAM: ${it.requiredRamMb}+ MB required" },
            isScanning = scanning,
            scanResultMessage = scanMsg,
            cloudProviderName = cloudName
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiEngineSettingsUiState())

    init {
        viewModelScope.launch {
            _storageUsed.value = localModelManager.getTotalStorageUsedMb()
            loadCloudProviderName()
        }
    }

    private suspend fun loadCloudProviderName() {
        val type = aiPreferences.getCloudProviderType()
        _cloudProviderName.value = try {
            CloudProviderType.valueOf(type).label
        } catch (_: Exception) {
            ""
        }
    }

    fun selectMode(mode: AiExecutionMode) {
        viewModelScope.launch {
            aiPreferences.setSelectedMode(mode)
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            localModelManager.deleteModel(modelId)
            _storageUsed.value = localModelManager.getTotalStorageUsedMb()
        }
    }

    fun scanForModels() {
        viewModelScope.launch {
            _scanState.value = true
            _scanResult.value = null
            try {
                val newCount = modelInstaller.scanForModels()
                val installedCount = localModelManager.getInstalledModels().size
                _scanResult.value = when {
                    newCount > 0 -> "Found $newCount new model(s)"
                    installedCount > 0 -> "$installedCount model(s) already installed"
                    else -> "No models found on device"
                }
                _storageUsed.value = localModelManager.getTotalStorageUsedMb()
            } catch (e: Exception) {
                _scanResult.value = "Scan failed: ${e.message}"
            }
            _scanState.value = false
        }
    }
}
