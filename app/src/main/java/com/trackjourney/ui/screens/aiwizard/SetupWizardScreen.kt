package com.trackjourney.ui.screens.aiwizard

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackjourney.data.ai.models.*

@Composable
fun SetupWizardScreen(
    onComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val progress by viewModel.installProgress.collectAsState()

    LaunchedEffect(state.isComplete) {
        if (state.isComplete) onComplete()
    }

    AnimatedContent(targetState = state.currentStep, label = "wizard_step") { step ->
        when (step) {
            SetupStep.INTRO -> IntroScreen(
                onNext = {
                    viewModel.detectCapabilities()
                    viewModel.goToStep(SetupStep.DEVICE_COMPATIBILITY)
                }
            )
            SetupStep.DEVICE_COMPATIBILITY -> DeviceCompatibilityScreen(
                state = state,
                onNext = { viewModel.goToStep(SetupStep.RECOMMENDED_MODE) },
                onBack = { viewModel.goToStep(SetupStep.INTRO) }
            )
            SetupStep.RECOMMENDED_MODE -> RecommendedAiModeScreen(
                state = state,
                onModeSelected = { viewModel.selectMode(it) },
                onNext = {
                    val mode = state.selectedMode ?: state.recommendedMode
                    when (mode) {
                        AiExecutionMode.CUSTOM_LOCAL -> viewModel.goToStep(SetupStep.LOCAL_MODEL_CONFIG)
                        AiExecutionMode.CLOUD -> viewModel.goToStep(SetupStep.CLOUD_CONFIG)
                        else -> viewModel.goToStep(SetupStep.READY)
                    }
                },
                onBack = { viewModel.goToStep(SetupStep.DEVICE_COMPATIBILITY) }
            )
            SetupStep.CLOUD_CONFIG -> CloudConfigScreen(
                state = state,
                onApiKeyChanged = { viewModel.setCloudApiKey(it) },
                onProviderTypeChanged = { viewModel.setCloudProviderType(it) },
                onSave = { viewModel.saveCloudConfig() },
                onBack = { viewModel.goToStep(SetupStep.RECOMMENDED_MODE) }
            )
            SetupStep.LOCAL_MODEL_CONFIG -> LocalModelConfigScreen(
                state = state,
                progress = progress,
                onDownload = { viewModel.downloadModel(it) },
                onCancelDownload = { viewModel.cancelDownload() },
                onSetActive = { viewModel.setModelActive(it) },
                onImport = { model, uri -> viewModel.importModel(model, uri) },
                onScanDevice = { viewModel.scanForModels() },
                onSkip = { viewModel.goToStep(SetupStep.READY) },
                onBack = { viewModel.goToStep(SetupStep.RECOMMENDED_MODE) }
            )
            SetupStep.MODEL_INSTALL_OPTIONS -> ModelInstallOptionsScreen(
                state = state,
                onDownload = { viewModel.downloadModel(it) },
                onImport = { viewModel.goToStep(SetupStep.IMPORT_MODEL) },
                onBack = { viewModel.goToStep(SetupStep.LOCAL_MODEL_CONFIG) }
            )
            SetupStep.MODEL_DOWNLOAD -> ModelDownloadScreen(
                state = state,
                progress = progress,
                onCancel = { viewModel.goToStep(SetupStep.LOCAL_MODEL_CONFIG) }
            )
            SetupStep.IMPORT_MODEL -> ImportModelScreen(
                state = state,
                onModelImported = { model, uri -> viewModel.importModel(model, uri) },
                onBack = { viewModel.goToStep(SetupStep.LOCAL_MODEL_CONFIG) }
            )
            SetupStep.READY -> ReadyScreen(
                state = state,
                onBenchmark = { viewModel.runBenchmark() },
                onDone = { viewModel.completeSetup() },
                onBack = {
                    val mode = state.selectedMode ?: state.recommendedMode
                    when (mode) {
                        AiExecutionMode.CLOUD -> viewModel.goToStep(SetupStep.CLOUD_CONFIG)
                        AiExecutionMode.CUSTOM_LOCAL -> viewModel.goToStep(SetupStep.LOCAL_MODEL_CONFIG)
                        else -> viewModel.goToStep(SetupStep.RECOMMENDED_MODE)
                    }
                }
            )
        }
    }
}

@Composable
private fun IntroScreen(onNext: () -> Unit) {
    WizardPage(title = "AI Engine Setup") {
        Spacer(modifier = Modifier.height(24.dp))
        Icon(
            Icons.Filled.Psychology,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "Choose Your AI Engine",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "TrackMyJourney can analyze your tracks using different AI engines. " +
                    "You can use cloud-based AI, your device's built-in AI, " +
                    "or download a local AI model for fully private analysis.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "This wizard will check your device capabilities and help you choose the best option.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get Started")
        }
    }
}

@Composable
private fun DeviceCompatibilityScreen(
    state: LocalAiSetupState,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    WizardPage(title = "Device Compatibility") {
        if (state.isDetecting) {
            Spacer(modifier = Modifier.height(48.dp))
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Analyzing your device...")
        } else if (state.deviceCapability != null) {
            val cap = state.deviceCapability
            Spacer(modifier = Modifier.height(16.dp))
            CapabilityRow("RAM", "${cap.totalRamMb} MB (${cap.ramTier.name})")
            CapabilityRow("Storage Available", "${cap.availableStorageMb} MB")
            CapabilityRow("Android API", "${cap.sdkInt}")
            CapabilityRow("Architectures", cap.supportedAbis.joinToString())
            CapabilityRow("Performance Class", cap.performanceClass.name)
            CapabilityRow("AICore", if (cap.aiCoreAvailable) "Available (${cap.aiCoreVersion})" else "Not available")
            CapabilityRow("ML Kit GenAI", if (cap.mlKitGenAiAvailable) "Available" else "Not available")

            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Lightbulb, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Recommended Mode", fontWeight = FontWeight.SemiBold)
                        Text(
                            cap.recommendedMode.label,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        state.error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(
                onClick = onNext,
                enabled = state.deviceCapability != null,
                modifier = Modifier.weight(1f)
            ) { Text("Next") }
        }
    }
}

@Composable
private fun CapabilityRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RecommendedAiModeScreen(
    state: LocalAiSetupState,
    onModeSelected: (AiExecutionMode) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val selected = state.selectedMode ?: state.recommendedMode
    val systemAiAvailable = state.deviceCapability?.supportsSystemAi ?: false

    WizardPage(title = "Choose AI Mode") {
        Spacer(modifier = Modifier.height(8.dp))
        AiExecutionMode.entries.forEach { mode ->
            val isDisabled = mode == AiExecutionMode.SYSTEM_LOCAL && !systemAiAvailable
            val isSelected = selected == mode && !isDisabled
            val isRecommended = mode == state.recommendedMode
            Card(
                onClick = { if (!isDisabled) onModeSelected(mode) },
                enabled = !isDisabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isDisabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        isSelected -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surface
                    }
                ),
                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { if (!isDisabled) onModeSelected(mode) },
                        enabled = !isDisabled
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                mode.label,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDisabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                else Color.Unspecified
                            )
                            if (isRecommended && !isDisabled) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        "Recommended",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                        Text(
                            mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDisabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isDisabled) {
                            Text(
                                "Not available on this device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text("Next") }
        }
    }
}

@Composable
private fun CloudConfigScreen(
    state: LocalAiSetupState,
    onApiKeyChanged: (String) -> Unit,
    onProviderTypeChanged: (CloudProviderType) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    WizardPage(title = "Configure Cloud AI") {
        Spacer(modifier = Modifier.height(8.dp))
        Icon(
            Icons.Filled.Cloud,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Cloud AI sends summarized track data to an external AI service for analysis. " +
                    "Your raw GPS data stays on your device.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Provider type selection
        Text("AI Provider", fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        CloudProviderType.entries.forEach { provider ->
            val isSelected = state.cloudProviderType == provider
            Card(
                onClick = { onProviderTypeChanged(provider) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                ),
                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isSelected, onClick = { onProviderTypeChanged(provider) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(provider.label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(provider.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // API key input
        Text("API Key", fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.cloudApiKey,
            onValueChange = onApiKeyChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Enter your API key") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )

        state.error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(
                onClick = onSave,
                enabled = state.cloudApiKey.isNotBlank() && !state.isValidatingApiKey,
                modifier = Modifier.weight(1f)
            ) {
                if (state.isValidatingApiKey) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Save & Continue")
            }
        }
    }
}

@Composable
private fun LocalModelConfigScreen(
    state: LocalAiSetupState,
    progress: InstallProgress?,
    onDownload: (LocalAiModel) -> Unit,
    onCancelDownload: () -> Unit,
    onSetActive: (LocalAiModel) -> Unit,
    onImport: (LocalAiModel, Uri) -> Unit,
    onScanDevice: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit
) {
    var showImportPicker by remember { mutableStateOf(false) }

    val importModel = LocalAiModel(
        modelId = "imported-model",
        displayName = "Imported Model",
        runtimeType = "mediapipe_llm",
        fileFormat = "bin",
        quantization = null,
        requiredRamMb = 2048,
        recommendedRamMb = 4096,
        sizeMb = 0,
        localPath = null,
        installState = ModelInstallState.NOT_INSTALLED,
        checksum = null,
        version = "1.0",
        supportsStructuredJson = false,
        supportsStreaming = true,
        supportsTextGeneration = true
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onImport(importModel, uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            "Configure Local AI Model",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Device capability summary
        state.deviceCapability?.let { cap ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Memory, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            "RAM: ${cap.totalRamMb} MB (${cap.ramTier.name}) | Storage: ${cap.availableStorageMb} MB free",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "All analysis runs locally on your device. No data leaves your phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Active download progress
        if (state.isDownloading && progress != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            state.selectedModel?.displayName ?: "Model",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (progress.progressPercent) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${progress.state.label} — ${progress.progressPercent}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(
                            onClick = onCancelDownload,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Cancel", fontSize = 12.sp)
                        }
                    }
                    progress.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Importing indicator
        if (state.isImporting) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Importing model...", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Model catalog
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Separate catalog models from scanned/imported models
            val catalogIds = ModelCatalog.availableModels.map { it.modelId }.toSet()
            val catalogModels = state.catalogModels.filter { it.modelId in catalogIds }
            val scannedModels = state.catalogModels.filter { it.modelId !in catalogIds }

            val compatibleModels = catalogModels.filter { model ->
                state.compatibility[model.modelId]?.isCompatible != false
            }
            val incompatibleModels = catalogModels.filter { model ->
                state.compatibility[model.modelId]?.isCompatible == false
            }

            val smallModels = compatibleModels.filter { it.sizeMb < 1000 }
            val mediumModels = compatibleModels.filter { it.sizeMb in 1000..3000 }
            val largeModels = compatibleModels.filter { it.sizeMb > 3000 }

            if (mediumModels.isNotEmpty()) {
                item {
                    Text(
                        "Recommended (1-3 GB)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                items(mediumModels, key = { it.modelId }) { model ->
                    DownloadableModelCard(
                        model = model,
                        report = state.compatibility[model.modelId],
                        isDownloading = state.downloadingModelId == model.modelId,
                        onDownload = { onDownload(model) },
                        onSetActive = { onSetActive(model) },
                        enabled = !state.isDownloading && !state.isImporting
                    )
                }
            }

            if (smallModels.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Small (< 1 GB)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                items(smallModels, key = { it.modelId }) { model ->
                    DownloadableModelCard(
                        model = model,
                        report = state.compatibility[model.modelId],
                        isDownloading = state.downloadingModelId == model.modelId,
                        onDownload = { onDownload(model) },
                        onSetActive = { onSetActive(model) },
                        enabled = !state.isDownloading && !state.isImporting
                    )
                }
            }

            if (largeModels.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Large (3+ GB)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                items(largeModels, key = { it.modelId }) { model ->
                    DownloadableModelCard(
                        model = model,
                        report = state.compatibility[model.modelId],
                        isDownloading = state.downloadingModelId == model.modelId,
                        onDownload = { onDownload(model) },
                        onSetActive = { onSetActive(model) },
                        enabled = !state.isDownloading && !state.isImporting
                    )
                }
            }

            // Scanned/imported models not in catalog
            if (scannedModels.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Found on Device",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                items(scannedModels, key = { it.modelId }) { model ->
                    DownloadableModelCard(
                        model = model,
                        report = null,
                        isDownloading = false,
                        onDownload = null,
                        onSetActive = { onSetActive(model) },
                        enabled = !state.isDownloading && !state.isImporting
                    )
                }
            }

            if (incompatibleModels.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Incompatible with this device",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                items(incompatibleModels, key = { it.modelId }) { model ->
                    DownloadableModelCard(
                        model = model,
                        report = state.compatibility[model.modelId],
                        isDownloading = false,
                        onDownload = null,
                        onSetActive = null,
                        enabled = false
                    )
                }
            }

            // Scan device for existing models
            item {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onScanDevice,
                    enabled = !state.isDownloading && !state.isImporting && !state.isScanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scanning device...")
                    } else {
                        Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Device for Models")
                    }
                }
                state.scanResultMessage?.let { msg ->
                    val isPositive = msg.contains("model(s)") && !msg.startsWith("No")
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isPositive) Icons.Filled.CheckCircle else Icons.Filled.Info,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = if (isPositive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPositive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Import from device
            item {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    enabled = !state.isDownloading && !state.isImporting && !state.isScanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import Model from Device")
                }
                Text(
                    "Supports .bin, .gguf, and .tflite model files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        state.error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onBack,
                enabled = !state.isDownloading,
                modifier = Modifier.weight(1f)
            ) { Text("Back") }
            Button(
                onClick = onSkip,
                enabled = !state.isDownloading && !state.isImporting,
                modifier = Modifier.weight(1f)
            ) { Text("Skip for Now") }
        }
    }
}

@Composable
private fun DownloadableModelCard(
    model: LocalAiModel,
    report: CompatibilityReport?,
    isDownloading: Boolean,
    onDownload: (() -> Unit)?,
    onSetActive: (() -> Unit)? = null,
    enabled: Boolean
) {
    val isIncompatible = report?.isCompatible == false
    val isInstalled = model.installState == ModelInstallState.INSTALLED

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isIncompatible -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                isInstalled -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                isDownloading -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        if (isInstalled) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    if (model.isActive) "Active" else "Installed",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${model.sizeMb} MB",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            " | ${model.quantization ?: "N/A"} | ${model.runtimeType}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "RAM: ${model.requiredRamMb}+ MB required",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                if (isInstalled && model.isActive) {
                    Icon(
                        Icons.Filled.CheckCircle, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (isInstalled && onSetActive != null) {
                    FilledTonalButton(
                        onClick = onSetActive,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Set Active", fontSize = 12.sp)
                    }
                } else if (onDownload != null && !isDownloading) {
                    FilledTonalButton(
                        onClick = onDownload,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Download", fontSize = 12.sp)
                    }
                }
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            report?.warnings?.forEach { warning ->
                Text("Warning: $warning", fontSize = 11.sp, color = Color(0xFFF57C00))
            }
            report?.issues?.forEach { issue ->
                Text(issue, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ModelInstallOptionsScreen(
    state: LocalAiSetupState,
    onDownload: (LocalAiModel) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit
) {
    WizardPage(title = "Choose a Model") {
        Text(
            "Select a model to download, or import one from your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val compatibleModels = state.catalogModels.filter { model ->
                state.compatibility[model.modelId]?.isCompatible != false
            }
            val incompatibleModels = state.catalogModels.filter { model ->
                state.compatibility[model.modelId]?.isCompatible == false
            }

            if (compatibleModels.isNotEmpty()) {
                item {
                    Text("Compatible Models", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
                }
                items(compatibleModels, key = { it.modelId }) { model ->
                    ModelCatalogCard(
                        model = model,
                        report = state.compatibility[model.modelId],
                        onDownload = { onDownload(model) }
                    )
                }
            }

            if (incompatibleModels.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Incompatible Models", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                }
                items(incompatibleModels, key = { it.modelId }) { model ->
                    ModelCatalogCard(
                        model = model,
                        report = state.compatibility[model.modelId],
                        onDownload = null
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onImport,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Import from Device")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun ModelCatalogCard(
    model: LocalAiModel,
    report: CompatibilityReport?,
    onDownload: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (report?.isCompatible == false)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(
                        "${model.sizeMb} MB | ${model.quantization ?: "N/A"} | ${model.runtimeType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onDownload != null) {
                    FilledTonalButton(onClick = onDownload, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Download", fontSize = 12.sp)
                    }
                }
            }
            report?.warnings?.forEach { warning ->
                Text("Warning: $warning", fontSize = 11.sp, color = Color(0xFFF57C00))
            }
            report?.issues?.forEach { issue ->
                Text(issue, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ModelDownloadScreen(
    state: LocalAiSetupState,
    progress: InstallProgress?,
    onCancel: () -> Unit
) {
    WizardPage(title = "Downloading Model") {
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            state.selectedModel?.displayName ?: "Model",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))

        val percent = progress?.progressPercent ?: 0
        val progressState = progress?.state ?: ModelInstallState.DOWNLOADING

        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "${progressState.label} — $percent%",
            style = MaterialTheme.typography.bodySmall
        )

        progress?.errorMessage?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        state.error?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun ImportModelScreen(
    state: LocalAiSetupState,
    onModelImported: (LocalAiModel, Uri) -> Unit,
    onBack: () -> Unit
) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    // Default to a generic import model template
    val importModel = state.selectedModel ?: LocalAiModel(
        modelId = "imported-model",
        displayName = "Imported Model",
        runtimeType = "mediapipe_llm",
        fileFormat = "bin",
        quantization = null,
        requiredRamMb = 2048,
        recommendedRamMb = 4096,
        sizeMb = 0,
        localPath = null,
        installState = ModelInstallState.NOT_INSTALLED,
        checksum = null,
        version = "1.0",
        supportsStructuredJson = false,
        supportsStreaming = true,
        supportsTextGeneration = true
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedUri = uri
    }

    WizardPage(title = "Import Model") {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Select a .bin, .gguf, or .tflite model file from your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { filePickerLauncher.launch("*/*") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.FolderOpen, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Choose File")
        }

        selectedUri?.let { uri ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(uri.lastPathSegment ?: "Selected file", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onModelImported(importModel, uri) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Import Model") }
        }

        state.error?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun ReadyScreen(
    state: LocalAiSetupState,
    onBenchmark: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    WizardPage(title = "Setup Complete") {
        Spacer(modifier = Modifier.height(24.dp))
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "You're all set!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        val mode = state.selectedMode ?: state.recommendedMode
        Text(
            "AI Mode: ${mode.label}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )

        state.selectedModel?.let { model ->
            if (model.installState == ModelInstallState.INSTALLED) {
                Text(
                    "Active Model: ${model.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Benchmark button
        OutlinedButton(
            onClick = onBenchmark,
            enabled = !state.isBenchmarking,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isBenchmarking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Running Benchmark...")
            } else {
                Icon(Icons.Filled.Speed, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Run Benchmark")
            }
        }

        state.benchmarkResult?.let { result ->
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Benchmark Results", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Latency: ${result.latencyMs}ms", style = MaterialTheme.typography.bodySmall)
                    Text("Speed: ${"%.1f".format(result.tokensPerSecond)} tokens/sec", style = MaterialTheme.typography.bodySmall)
                    Text("Memory Delta: ${result.memoryDeltaMb} MB", style = MaterialTheme.typography.bodySmall)
                    Text("Rating: ${result.performanceRating}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
        }
    }
}

@Composable
private fun WizardPage(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}
