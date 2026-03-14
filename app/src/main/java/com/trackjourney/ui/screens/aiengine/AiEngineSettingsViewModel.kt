package com.trackjourney.ui.screens.aiengine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.ai.models.*
import com.trackjourney.data.ai.provider.SystemAiProvider
import com.trackjourney.data.ai.runtime.SystemAiRuntimeAdapter
import com.trackjourney.data.local.SettingsDataStore
import com.trackjourney.data.model.SubscriptionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiEngineSettingsUiState(
    val selectedMode: AiExecutionMode = AiExecutionMode.AUTO,
    val activeModel: LocalAiModel? = null,
    val installedModels: List<LocalAiModel> = emptyList(),
    val storageUsedMb: Long = 0,
    val performanceNote: String? = null,
    val systemAiStatus: String = "Checking...",
    val systemAiAvailable: Boolean = false,
    val isScanning: Boolean = false,
    val scanResultMessage: String? = null,
    val isPremium: Boolean = false
)

@HiltViewModel
class AiEngineSettingsViewModel @Inject constructor(
    private val aiPreferences: AiPreferences,
    private val localModelManager: LocalModelManager,
    private val modelInstaller: ModelInstaller,
    private val systemAiRuntime: SystemAiRuntimeAdapter,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _scanState = MutableStateFlow(false)
    private val _scanResult = MutableStateFlow<String?>(null)
    private val _storageUsed = MutableStateFlow(0L)

    val state: StateFlow<AiEngineSettingsUiState> = combine(
        aiPreferences.observeSelectedMode(),
        localModelManager.observeActiveModel(),
        localModelManager.observeInstalledModels(),
        combine(_scanState, _scanResult, settingsDataStore.subscriptionStatus) { scanning, scanMsg, sub ->
            Triple(scanning, scanMsg, sub)
        }
    ) { mode, active, installed, (scanning, scanMsg, subscriptionStatus) ->
        AiEngineSettingsUiState(
            selectedMode = mode,
            activeModel = active,
            installedModels = installed,
            storageUsedMb = _storageUsed.value,
            performanceNote = active?.let { "RAM: ${it.requiredRamMb}+ MB required" },
            systemAiStatus = systemAiRuntime.getStatusMessage(),
            systemAiAvailable = systemAiRuntime.isAvailable(),
            isScanning = scanning,
            scanResultMessage = scanMsg,
            isPremium = (subscriptionStatus as SubscriptionStatus).isActive
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiEngineSettingsUiState())

    init {
        viewModelScope.launch {
            _storageUsed.value = localModelManager.getTotalStorageUsedMb()
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
