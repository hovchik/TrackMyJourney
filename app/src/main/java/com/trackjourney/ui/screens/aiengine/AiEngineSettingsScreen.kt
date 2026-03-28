package com.trackjourney.ui.screens.aiengine

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackjourney.data.ai.models.AiExecutionMode
import com.trackjourney.data.ai.models.LocalAiModel
import com.trackjourney.data.ai.models.ModelInstallState

@Composable
fun AiEngineSettingsScreen(
    onNavigateToWizard: () -> Unit,
    onNavigateToSubscription: () -> Unit = {},
    viewModel: AiEngineSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val isLocalMode = state.selectedMode != AiExecutionMode.CLOUD

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text(
                "AI Engine",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Privacy callout for local mode
        if (isLocalMode) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "All analysis is performed locally. No data is sent to external servers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        // AI Mode selector — only Local and Cloud
        item {
            Text("AI Mode", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            AiModeCard(
                label = "Local Model",
                description = "Runs a downloaded AI model directly on your device.",
                icon = Icons.Filled.Memory,
                isSelected = isLocalMode,
                onClick = { viewModel.selectMode(AiExecutionMode.CUSTOM_LOCAL) }
            )
        }
        item {
            AiModeCard(
                label = "Cloud AI",
                description = "Sends summarized data to cloud AI for analysis.",
                icon = Icons.Filled.Cloud,
                isSelected = !isLocalMode,
                onClick = { viewModel.selectMode(AiExecutionMode.CLOUD) }
            )
        }

        // Status card
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Status", fontWeight = FontWeight.SemiBold)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    StatusRow("Active Mode", if (isLocalMode) "Local Model" else "Cloud AI")
                    state.activeModel?.let { model ->
                        StatusRow("Active Model", model.displayName)
                    }
                    StatusRow("Storage Used", "${state.storageUsedMb} MB")
                    state.performanceNote?.let { note ->
                        StatusRow("Performance", note)
                    }
                }
            }
        }

        // Installed models with delete
        if (state.installedModels.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Installed Models", fontWeight = FontWeight.SemiBold)
            }
            items(state.installedModels, key = { it.modelId }) { model ->
                InstalledModelRow(
                    model = model,
                    onDelete = { viewModel.deleteModel(model.modelId) }
                )
            }
        }

        // Setup / Download local model button
        if (isLocalMode) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onNavigateToWizard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (state.installedModels.isEmpty()) "Setup Local Model" else "Download Another Model")
                }
            }

            // Scan for models
            item {
                val scanContext = LocalContext.current

                var hasStorageAccess by remember {
                    mutableStateOf(
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
                    )
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasStorageAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                                    Environment.isExternalStorageManager()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (!hasStorageAccess) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${scanContext.packageName}")
                            )
                            scanContext.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Grant File Access to Scan Device")
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.scanForModels() },
                        enabled = !state.isScanning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scanning device...")
                        } else {
                            Icon(Icons.Filled.SearchOff, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan Device for Models")
                        }
                    }
                }

                state.scanResultMessage?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AiModeCard(
    label: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isSelected) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InstalledModelRow(
    model: LocalAiModel,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (model.isActive) Icons.Filled.CheckCircle else Icons.Filled.Memory,
                null,
                tint = if (model.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(model.displayName, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    if (model.isActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                "Active",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                Text(
                    "${model.sizeMb} MB | ${model.installState.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove Model") },
            text = { Text("Remove ${model.displayName}? This will delete the model files (${model.sizeMb} MB) from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
