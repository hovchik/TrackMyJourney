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
import com.trackjourney.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val satelliteInfo by viewModel.satelliteInfo.collectAsState()
    val motionState by viewModel.motionState.collectAsState()
    val wearableState by viewModel.wearableState.collectAsState()
    val wearableReading by viewModel.wearableReading.collectAsState()

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

        // ── GPS STATUS ────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "GPS Status",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            val isActive = satelliteInfo.totalVisible > 0 || satelliteInfo.usedInFix > 0
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (isActive) when {
                                satelliteInfo.usedInFix >= 8 -> PrimaryLight.copy(alpha = 0.15f)
                                satelliteInfo.usedInFix >= 4 -> Accent.copy(alpha = 0.15f)
                                else -> Error.copy(alpha = 0.15f)
                            } else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.SatelliteAlt,
                                    contentDescription = null,
                                    tint = if (isActive) when {
                                        satelliteInfo.usedInFix >= 8 -> PrimaryLight
                                        satelliteInfo.usedInFix >= 4 -> Accent
                                        else -> Error
                                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("GPS Satellites", fontWeight = FontWeight.SemiBold)
                            if (isActive) {
                                Text(
                                    "Available: ${satelliteInfo.totalVisible}  |  Connected: ${satelliteInfo.usedInFix}",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    when {
                                        satelliteInfo.usedInFix >= 8 -> "Excellent signal"
                                        satelliteInfo.usedInFix >= 4 -> "Good signal"
                                        satelliteInfo.usedInFix >= 1 -> "Weak signal"
                                        else -> "No signal"
                                    },
                                    fontSize = 12.sp,
                                    color = when {
                                        satelliteInfo.usedInFix >= 8 -> PrimaryLight
                                        satelliteInfo.usedInFix >= 4 -> Accent
                                        else -> Error
                                    }
                                )
                            } else {
                                Text(
                                    "Start tracking to see GPS status",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── MOTION SENSOR STATUS ──────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Motion Sensors",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            val hasSensorData = motionState.accelerationMagnitude > 0f || motionState.rotationRate > 0f
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (hasSensorData) {
                                if (motionState.isDeviceMoving) PrimaryLight.copy(alpha = 0.15f)
                                else Secondary.copy(alpha = 0.15f)
                            } else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (motionState.isDeviceMoving) Icons.Filled.DirectionsWalk else Icons.Filled.PauseCircle,
                                    contentDescription = null,
                                    tint = if (hasSensorData) {
                                        if (motionState.isDeviceMoving) PrimaryLight else Secondary
                                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                if (hasSensorData) {
                                    if (motionState.isDeviceMoving) "Device Moving" else "Device Stationary"
                                } else "Sensors Inactive",
                                fontWeight = FontWeight.SemiBold
                            )
                            if (hasSensorData) {
                                Text(
                                    "Accel: ${String.format(java.util.Locale.US, "%.2f", motionState.accelerationMagnitude)} m/s\u00B2  |  Gyro: ${String.format(java.util.Locale.US, "%.2f", motionState.rotationRate)} rad/s",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Confidence: ${(motionState.motionConfidence * 100).toInt()}%${if (motionState.stepDetected) "  |  Steps detected" else ""}",
                                    fontSize = 12.sp,
                                    color = if (motionState.isDeviceMoving) PrimaryLight else Secondary
                                )
                            } else {
                                Text(
                                    "Start tracking to enable GPS drift filtering",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── WEARABLE STATUS ──────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Smartwatch",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isConnected = wearableState is WearableConnectionState.Connected
                        Surface(
                            color = if (isConnected) PrimaryLight.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Watch,
                                    contentDescription = null,
                                    tint = if (isConnected) PrimaryLight
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Garmin / Samsung Watch", fontWeight = FontWeight.SemiBold)
                            when (val state = wearableState) {
                                is WearableConnectionState.Connected -> {
                                    Text(
                                        "Connected: ${state.device.name}",
                                        fontSize = 13.sp,
                                        color = PrimaryLight
                                    )
                                    wearableReading?.let { reading ->
                                        val details = buildString {
                                            reading.heartRate?.let { append("HR: $it bpm") }
                                            reading.batteryLevel?.let {
                                                if (isNotEmpty()) append("  |  ")
                                                append("Battery: $it%")
                                            }
                                            reading.cadence?.let {
                                                if (isNotEmpty()) append("  |  ")
                                                append("Cadence: $it")
                                            }
                                        }
                                        if (details.isNotEmpty()) {
                                            Text(
                                                details,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                is WearableConnectionState.Scanning -> Text(
                                    "Scanning for devices...",
                                    fontSize = 13.sp,
                                    color = Accent
                                )
                                is WearableConnectionState.Connecting -> Text(
                                    "Connecting...",
                                    fontSize = 13.sp,
                                    color = Accent
                                )
                                is WearableConnectionState.Error -> Text(
                                    state.message,
                                    fontSize = 13.sp,
                                    color = Error
                                )
                                is WearableConnectionState.Disconnected -> Text(
                                    "Auto-connects when tracking starts",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

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
                        "GPS tracking with OpenStreetMap and on-device AI analysis. Maximum precision satellite positioning. All data stored locally.",
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

private data class PermissionItem(
    val permission: String,
    val label: String,
    val icon: ImageVector,
    val minSdk: Int = 0
)

private val requiredPermissions = buildList {
    add(PermissionItem(
        Manifest.permission.INTERNET,
        "Internet",
        Icons.Filled.Wifi
    ))
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(PermissionItem(
            Manifest.permission.POST_NOTIFICATIONS,
            "Notifications",
            Icons.Filled.Notifications,
            minSdk = Build.VERSION_CODES.TIRAMISU
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

    // Track last permission requested so we can open settings if denied
    var lastRequestedPermission by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && lastRequestedPermission != null) {
            // Permission was denied — open app settings so user can grant it manually
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(intent)
        }
        refreshKey++
    }

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
                                // Background location always needs app settings
                                if (item.permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION) {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                } else {
                                    lastRequestedPermission = item.permission
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
