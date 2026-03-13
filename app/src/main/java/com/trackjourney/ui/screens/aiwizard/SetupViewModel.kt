package com.trackjourney.ui.screens.aiwizard

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.ai.models.*
import com.trackjourney.data.ai.provider.CloudProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SetupStep {
    INTRO,
    DEVICE_COMPATIBILITY,
    RECOMMENDED_MODE,
    CLOUD_CONFIG,
    LOCAL_MODEL_CONFIG,
    MODEL_INSTALL_OPTIONS,
    MODEL_DOWNLOAD,
    IMPORT_MODEL,
    READY
}

data class LocalAiSetupState(
    val currentStep: SetupStep = SetupStep.INTRO,
    val deviceCapability: DeviceCapabilityResult? = null,
    val recommendedMode: AiExecutionMode = AiExecutionMode.AUTO,
    val selectedMode: AiExecutionMode? = null,
    val catalogModels: List<LocalAiModel> = emptyList(),
    val compatibility: Map<String, CompatibilityReport> = emptyMap(),
    val selectedModel: LocalAiModel? = null,
    val benchmarkResult: BenchmarkResult? = null,
    val isDetecting: Boolean = false,
    val isBenchmarking: Boolean = false,
    val error: String? = null,
    val isComplete: Boolean = false,
    val cloudApiKey: String = "",
    val cloudProviderType: CloudProviderType = CloudProviderType.CLAUDE,
    val isValidatingApiKey: Boolean = false,
    val isDownloading: Boolean = false,
    val isImporting: Boolean = false,
    val downloadingModelId: String? = null,
    val isScanning: Boolean = false,
    val scanResultMessage: String? = null
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val detector: DeviceAiCapabilityDetector,
    private val validator: ModelCompatibilityValidator,
    private val modelInstaller: ModelInstaller,
    private val modelManager: LocalModelManager,
    private val benchmarkRunner: LocalAiBenchmarkRunner,
    private val aiPreferences: AiPreferences,
    private val cloudProvider: CloudProvider
) : ViewModel() {

    private val _state = MutableStateFlow(LocalAiSetupState())
    val state: StateFlow<LocalAiSetupState> = _state.asStateFlow()

    val installProgress: StateFlow<InstallProgress?> = modelInstaller.installProgress

    fun goToStep(step: SetupStep) {
        _state.update { it.copy(currentStep = step) }
    }

    fun detectCapabilities() {
        viewModelScope.launch {
            _state.update { it.copy(isDetecting = true, error = null) }
            try {
                val capability = detector.detect()
                val catalog = ModelCatalog.availableModels
                val compatMap = catalog.associate { model ->
                    model.modelId to validator.validate(model)
                }
                _state.update {
                    it.copy(
                        isDetecting = false,
                        deviceCapability = capability,
                        recommendedMode = capability.recommendedMode,
                        catalogModels = catalog,
                        compatibility = compatMap
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isDetecting = false, error = e.message) }
            }
        }
    }

    fun selectMode(mode: AiExecutionMode) {
        _state.update { it.copy(selectedMode = mode) }
    }

    fun selectModel(model: LocalAiModel) {
        _state.update { it.copy(selectedModel = model) }
    }

    fun downloadModel(model: LocalAiModel) {
        val url = model.downloadUrl ?: return
        _state.update {
            it.copy(
                selectedModel = model,
                isDownloading = true,
                downloadingModelId = model.modelId,
                error = null
            )
        }
        viewModelScope.launch {
            val result = modelInstaller.downloadModel(model, url)
            result.onSuccess { installed ->
                modelManager.setActiveModel(installed.modelId)
                _state.update {
                    it.copy(
                        selectedModel = installed,
                        isDownloading = false,
                        downloadingModelId = null,
                        currentStep = SetupStep.READY
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        isDownloading = false,
                        downloadingModelId = null,
                        error = e.message
                    )
                }
            }
        }
    }

    fun cancelDownload() {
        _state.update {
            it.copy(
                isDownloading = false,
                downloadingModelId = null,
                error = null
            )
        }
    }

    fun importModel(model: LocalAiModel, uri: Uri) {
        _state.update { it.copy(selectedModel = model, isImporting = true, error = null) }
        viewModelScope.launch {
            val result = modelInstaller.importFromUri(model, uri)
            result.onSuccess { installed ->
                modelManager.setActiveModel(installed.modelId)
                _state.update {
                    it.copy(
                        selectedModel = installed,
                        isImporting = false,
                        currentStep = SetupStep.READY
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(isImporting = false, error = e.message) }
            }
        }
    }

    fun runBenchmark() {
        viewModelScope.launch {
            _state.update { it.copy(isBenchmarking = true) }
            val result = benchmarkRunner.runBenchmark()
            _state.update { it.copy(isBenchmarking = false, benchmarkResult = result) }
        }
    }

    fun completeSetup() {
        viewModelScope.launch {
            val mode = _state.value.selectedMode ?: _state.value.recommendedMode
            aiPreferences.setSelectedMode(mode)
            aiPreferences.setSetupCompleted(true)
            _state.update { it.copy(isComplete = true) }
        }
    }

    fun setCloudApiKey(key: String) {
        _state.update { it.copy(cloudApiKey = key) }
    }

    fun setCloudProviderType(type: CloudProviderType) {
        _state.update { it.copy(cloudProviderType = type) }
    }

    fun saveCloudConfig() {
        val currentState = _state.value
        if (currentState.cloudApiKey.isBlank()) {
            _state.update { it.copy(error = "API key is required") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isValidatingApiKey = true, error = null) }
            try {
                cloudProvider.setApiKey(currentState.cloudApiKey)
                aiPreferences.setCloudApiKey(currentState.cloudApiKey)
                aiPreferences.setCloudProviderType(currentState.cloudProviderType.name)
                _state.update { it.copy(isValidatingApiKey = false, currentStep = SetupStep.READY) }
            } catch (e: Exception) {
                _state.update { it.copy(isValidatingApiKey = false, error = e.message) }
            }
        }
    }

    fun scanForModels() {
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true, scanResultMessage = null, error = null) }
            try {
                val count = modelInstaller.scanForModels()
                _state.update {
                    it.copy(
                        isScanning = false,
                        scanResultMessage = if (count > 0) "Found $count model(s) on device"
                        else "No models found on device"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isScanning = false,
                        scanResultMessage = null,
                        error = "Scan failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
