package com.trackjourney.ui.screens.aiwizard

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.ai.models.*
import com.trackjourney.data.ai.provider.CloudProvider
import com.trackjourney.data.local.SettingsDataStore
import com.trackjourney.service.ModelDownloadService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val scanResultMessage: String? = null,
    val isPremium: Boolean = false
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val detector: DeviceAiCapabilityDetector,
    private val validator: ModelCompatibilityValidator,
    private val modelInstaller: ModelInstaller,
    private val modelManager: LocalModelManager,
    private val benchmarkRunner: LocalAiBenchmarkRunner,
    private val aiPreferences: AiPreferences,
    private val cloudProvider: CloudProvider,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _state = MutableStateFlow(LocalAiSetupState())
    val state: StateFlow<LocalAiSetupState> = _state.asStateFlow()

    val installProgress: StateFlow<InstallProgress?> = modelInstaller.installProgress

    init {
        viewModelScope.launch {
            val isPremium = settingsDataStore.subscriptionStatus.first().isActive
            _state.update { it.copy(isPremium = isPremium) }
        }
    }

    fun goToStep(step: SetupStep) {
        _state.update { it.copy(currentStep = step) }
    }

    fun detectCapabilities() {
        viewModelScope.launch {
            _state.update { it.copy(isDetecting = true, error = null) }
            try {
                val capability = detector.detect()
                val catalog = mergedCatalog()
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

    /**
     * Merges the static model catalog with the database so already-installed
     * models reflect their real install state, path, and active flag.
     * Also appends scanned/imported models that aren't in the catalog.
     */
    private suspend fun mergedCatalog(): List<LocalAiModel> {
        val installedModels = modelManager.getInstalledModels()
        val installedMap = installedModels.associateBy { it.modelId }
        val catalogIds = ModelCatalog.availableModels.map { it.modelId }.toSet()

        // Merge catalog with DB state
        val merged = ModelCatalog.availableModels.map { catalogModel ->
            installedMap[catalogModel.modelId] ?: catalogModel
        }.toMutableList()

        // Append scanned/imported models not in catalog
        installedModels.filter { it.modelId !in catalogIds }.forEach { model ->
            merged.add(model)
        }

        return merged
    }

    /**
     * Refreshes the catalog models in the wizard state from the database so
     * that install state, active flag, etc. are up-to-date.
     */
    private fun refreshCatalogModels() {
        viewModelScope.launch {
            _state.update { it.copy(catalogModels = mergedCatalog()) }
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

        // Launch via foreground service with WakeLock so the download
        // survives the phone being locked / screen turning off.
        ModelDownloadService.startDownload(appContext, model.modelId, url)

        // Observe the installer's progress flow to update UI state
        viewModelScope.launch {
            modelInstaller.installProgress.collect { progress ->
                if (progress != null && progress.modelId == model.modelId) {
                    when (progress.state) {
                        ModelInstallState.INSTALLED -> {
                            // Fetch the real model from DB (has localPath, checksum, etc.)
                            val installed = modelManager.getModel(model.modelId) ?: model.copy(
                                installState = ModelInstallState.INSTALLED
                            )
                            modelManager.setActiveModel(model.modelId)
                            _state.update {
                                it.copy(
                                    selectedModel = installed,
                                    isDownloading = false,
                                    downloadingModelId = null,
                                    currentStep = SetupStep.READY
                                )
                            }
                            refreshCatalogModels()
                            return@collect
                        }
                        ModelInstallState.FAILED -> {
                            _state.update {
                                it.copy(
                                    isDownloading = false,
                                    downloadingModelId = null,
                                    error = progress.errorMessage ?: "Download failed"
                                )
                            }
                            return@collect
                        }
                        else -> { /* downloading / installing — handled by notification */ }
                    }
                }
            }
        }
    }

    fun cancelDownload() {
        ModelDownloadService.cancelDownload(appContext)
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
                val newCount = modelInstaller.scanForModels()
                // Refresh catalog to reflect any newly found or already-installed models
                val updatedCatalog = mergedCatalog()
                val installedCount = modelManager.getInstalledModels().size
                val message = when {
                    newCount > 0 -> "Found $newCount new model(s) on device"
                    installedCount > 0 -> "$installedCount model(s) already installed"
                    else -> "No models found on device"
                }
                _state.update {
                    it.copy(
                        isScanning = false,
                        catalogModels = updatedCatalog,
                        scanResultMessage = message
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

    fun setModelActive(model: LocalAiModel) {
        viewModelScope.launch {
            modelManager.setActiveModel(model.modelId)
            val updated = modelManager.getModel(model.modelId) ?: model
            _state.update { it.copy(selectedModel = updated) }
            refreshCatalogModels()
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
