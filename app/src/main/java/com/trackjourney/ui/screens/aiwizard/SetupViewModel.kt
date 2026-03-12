package com.trackjourney.ui.screens.aiwizard

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.ai.models.*
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
    val isComplete: Boolean = false
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val detector: DeviceAiCapabilityDetector,
    private val validator: ModelCompatibilityValidator,
    private val modelInstaller: ModelInstaller,
    private val modelManager: LocalModelManager,
    private val benchmarkRunner: LocalAiBenchmarkRunner,
    private val aiPreferences: AiPreferences
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
        _state.update { it.copy(selectedModel = model, currentStep = SetupStep.MODEL_DOWNLOAD, error = null) }
        viewModelScope.launch {
            val result = modelInstaller.downloadModel(model, url)
            result.onSuccess { installed ->
                modelManager.setActiveModel(installed.modelId)
                _state.update { it.copy(currentStep = SetupStep.READY) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun importModel(model: LocalAiModel, uri: Uri) {
        _state.update { it.copy(selectedModel = model, error = null) }
        viewModelScope.launch {
            val result = modelInstaller.importFromUri(model, uri)
            result.onSuccess { installed ->
                modelManager.setActiveModel(installed.modelId)
                _state.update { it.copy(currentStep = SetupStep.READY) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
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

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
