package com.trackjourney.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trackjourney.data.bluetooth.WearableConnectionState
import com.trackjourney.data.model.ExportFormat
import com.trackjourney.ui.components.LoadingIndicator
import com.trackjourney.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) }
            )
        }

        // ── PERMISSIONS ────────────────────────────────
        item {
            Text(
                "Permissions",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item { PermissionsSection() }

        // ── RECORDING SETTINGS ──────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Recording",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        // Record interval
        item {
            SettingsCard {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Record Interval", fontWeight = FontWeight.Medium)
                            Text(
                                formatInterval(settings.recordIntervalMs),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = settings.recordIntervalMs.toFloat(),
                        onValueChange = { viewModel.updateRecordInterval(it.toLong()) },
                        valueRange = 1000f..30000f,
                        steps = 28
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("30s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Min distance
        item {
            SettingsCard {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Straighten, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Minimum Distance", fontWeight = FontWeight.Medium)
                            Text(
                                "${settings.minDistanceMeters.toInt()} meters",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = settings.minDistanceMeters,
                        onValueChange = { viewModel.updateMinDistance(it) },
                        valueRange = 1f..50f,
                        steps = 48
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1m", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("50m", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ── WEARABLE SETTINGS ───────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Wearable",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item { WearableConnectionSection(viewModel) }

        item {
            SettingsCard {
                SettingsSwitch(
                    icon = Icons.Filled.FavoriteBorder,
                    title = "Heart Rate Monitoring",
                    subtitle = "Record heart rate from connected watch",
                    checked = settings.enableHeartRate,
                    onCheckedChange = { viewModel.updateHeartRate(it) }
                )
            }
        }

        item {
            SettingsCard {
                SettingsSwitch(
                    icon = Icons.Filled.Air,
                    title = "SpO2 Monitoring",
                    subtitle = "Record blood oxygen saturation",
                    checked = settings.enableSpO2,
                    onCheckedChange = { viewModel.updateSpO2(it) }
                )
            }
        }

        // ── AI SETTINGS ─────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "AI & Detection",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            SettingsCard {
                SettingsSwitch(
                    icon = Icons.Filled.AutoAwesome,
                    title = "Auto Activity Detection",
                    subtitle = "Classify walking, driving, flying in real-time",
                    checked = settings.autoDetectActivity,
                    onCheckedChange = { viewModel.updateAutoDetect(it) }
                )
            }
        }

        // ── GENERAL ─────────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "General",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            SettingsCard {
                SettingsSwitch(
                    icon = Icons.Filled.ScreenLockPortrait,
                    title = "Keep Screen On",
                    subtitle = "Prevent screen from turning off while tracking",
                    checked = settings.keepScreenOn,
                    onCheckedChange = { viewModel.updateKeepScreenOn(it) }
                )
            }
        }

        item {
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.FilePresent, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Export Format", fontWeight = FontWeight.Medium)
                        Text(
                            settings.exportFormat.name,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        ExportFormat.entries.forEach { format ->
                            FilterChip(
                                selected = settings.exportFormat == format,
                                onClick = { viewModel.updateExportFormat(format) },
                                label = { Text(format.name, fontSize = 13.sp) },
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── ABOUT ───────────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("TrackMyJourney", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Version 1.0.0", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "GPS tracking with OpenStreetMap, smartwatch integration, and on-device AI analysis. All data stored locally.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun WearableConnectionSection(viewModel: SettingsViewModel) {
    val wearableState by viewModel.wearableState.collectAsState()
    val wearableReading by viewModel.wearableReading.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()

    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (val state = wearableState) {
                is WearableConnectionState.Connected -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = PrimaryLight.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Watch,
                                    contentDescription = null,
                                    tint = PrimaryLight,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.device.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Connected",
                                fontSize = 13.sp,
                                color = PrimaryLight
                            )
                            wearableReading?.let { reading ->
                                Text(
                                    buildString {
                                        reading.heartRate?.let { append("HR: $it bpm") }
                                        reading.spO2?.let {
                                            if (isNotEmpty()) append(" | ")
                                            append("SpO2: $it%")
                                        }
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        TextButton(onClick = { viewModel.disconnectWearable() }) {
                            Text("Disconnect", color = Error, fontSize = 13.sp)
                        }
                    }
                }

                is WearableConnectionState.Connecting -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        LoadingIndicator(size = 20.dp, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Connecting...", fontSize = 14.sp)
                    }
                }

                else -> {
                    // Disconnected / Scanning / Error
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Watch,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Smartwatch", fontWeight = FontWeight.Medium)
                            Text(
                                if (state is WearableConnectionState.Error) state.message
                                else "No device connected",
                                fontSize = 13.sp,
                                color = if (state is WearableConnectionState.Error) Error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { viewModel.scanForWearables() },
                            enabled = wearableState !is WearableConnectionState.Scanning,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            if (wearableState is WearableConnectionState.Scanning) {
                                LoadingIndicator(
                                    size = 16.dp,
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scanning", fontSize = 13.sp)
                            } else {
                                Icon(
                                    Icons.Filled.BluetoothSearching,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scan", fontSize = 13.sp)
                            }
                        }
                    }

                    // Discovered devices list
                    if (discoveredDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        @Suppress("DEPRECATION")
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Found Devices",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        discoveredDevices.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.connectWearable(device) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Bluetooth,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = Secondary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${device.type.name} | ${device.rssi} dBm",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = "Connect",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class PermissionItem(
    val permission: String,
    val label: String,
    val icon: ImageVector,
    val minSdk: Int = 0
)

private val requiredPermissions = buildList {
    add(PermissionItem(
        Manifest.permission.ACCESS_FINE_LOCATION,
        "Location",
        Icons.Filled.LocationOn
    ))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(PermissionItem(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            "Background Location",
            Icons.Filled.MyLocation,
            minSdk = Build.VERSION_CODES.Q
        ))
        add(PermissionItem(
            Manifest.permission.ACTIVITY_RECOGNITION,
            "Activity Recognition",
            Icons.Filled.DirectionsRun,
            minSdk = Build.VERSION_CODES.Q
        ))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(PermissionItem(
            Manifest.permission.BLUETOOTH_SCAN,
            "Bluetooth Scan",
            Icons.Filled.BluetoothSearching,
            minSdk = Build.VERSION_CODES.S
        ))
        add(PermissionItem(
            Manifest.permission.BLUETOOTH_CONNECT,
            "Bluetooth Connect",
            Icons.Filled.Bluetooth,
            minSdk = Build.VERSION_CODES.S
        ))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(PermissionItem(
            Manifest.permission.POST_NOTIFICATIONS,
            "Notifications",
            Icons.Filled.Notifications,
            minSdk = Build.VERSION_CODES.TIRAMISU
        ))
    }
}

@Composable
private fun PermissionsSection() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check permissions when returning from app settings
    var refreshKey by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionStates = remember(refreshKey) {
        requiredPermissions.map { item ->
            item to (ContextCompat.checkSelfPermission(context, item.permission)
                    == PackageManager.PERMISSION_GRANTED)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshKey++ }

    val grantedColor = Color(0xFF2E7D32)
    val deniedColor = Color(0xFFC62828)

    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            permissionStates.forEachIndexed { index, (item, granted) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!granted) {
                                // Background location must be requested separately after location is granted
                                if (item.permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION) {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                } else {
                                    permissionLauncher.launch(item.permission)
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (granted) grantedColor.copy(alpha = 0.15f) else deniedColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                item.icon,
                                contentDescription = null,
                                tint = if (granted) grantedColor else deniedColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            if (granted) "Granted" else "Tap to grant",
                            fontSize = 12.sp,
                            color = if (granted) grantedColor else deniedColor
                        )
                    }
                    Surface(
                        color = if (granted) grantedColor else deniedColor,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            if (granted) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = if (granted) "Granted" else "Denied",
                            tint = Color.White,
                            modifier = Modifier
                                .padding(4.dp)
                                .size(16.dp)
                        )
                    }
                }
                if (index < permissionStates.lastIndex) {
                    @Suppress("DEPRECATION")
                    Divider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatInterval(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}s"
    else -> "${ms / 60_000}min ${(ms % 60_000) / 1000}s"
}
