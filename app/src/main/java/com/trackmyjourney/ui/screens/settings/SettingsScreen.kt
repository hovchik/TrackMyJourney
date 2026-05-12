package com.trackmyjourney.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.trackmyjourney.data.bluetooth.WearableConnectionState
import com.trackmyjourney.data.model.AiMode
import com.trackmyjourney.data.model.ActivityConfig
import com.trackmyjourney.data.model.CarProfile
import com.trackmyjourney.data.model.CloudAiProvider
import com.trackmyjourney.data.model.ExportFormat
import com.trackmyjourney.data.model.FuelType
import com.trackmyjourney.data.model.SubscriptionStatus
import com.trackmyjourney.data.model.TrackingMode
import com.trackmyjourney.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToSubscription: () -> Unit = {},
    onNavigateToAiEngine: () -> Unit = {},
    onNavigateToLocalAiWizard: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val satelliteInfo by viewModel.satelliteInfo.collectAsState()
    val motionState by viewModel.motionState.collectAsState()
    val wearableState by viewModel.wearableState.collectAsState()
    val wearableReading by viewModel.wearableReading.collectAsState()
    val exportedBackupFile by viewModel.exportedBackupFile.collectAsState()
    val exportError by viewModel.exportError.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val importError by viewModel.importError.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val allCars by viewModel.allCars.collectAsState()
    val subscriptionStatus by viewModel.subscriptionStatus.collectAsState()
    val isPremium = subscriptionStatus.isActive
    var showAddCarDialog by remember { mutableStateOf(false) }
    var editingCar by remember { mutableStateOf<CarProfile?>(null) }
    var showAddActivityDialog by remember { mutableStateOf(false) }
    val showCloudAiWizard by viewModel.showCloudAiWizard.collectAsState()

    // Cloud AI setup wizard
    if (showCloudAiWizard) {
        CloudAiWizardDialog(
            currentProvider = settings.cloudAiProvider,
            currentApiKey = settings.cloudAiApiKey,
            currentEndpoint = settings.cloudAiEndpoint,
            currentModel = settings.cloudAiModel,
            onDismiss = { viewModel.dismissCloudAiWizard() },
            onSave = { provider, apiKey, endpoint, model ->
                viewModel.saveCloudAiConfig(provider, apiKey, endpoint, model)
            }
        )
    }

    // File picker for importing backup
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importAllData(it) }
    }

    // Share exported backup file
    LaunchedEffect(exportedBackupFile) {
        exportedBackupFile?.let { file ->
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share backup"))
            } catch (e: Exception) {
                viewModel.setExportError("Failed to share backup: ${e.message}")
            } finally {
                viewModel.clearExportedBackupFile()
            }
        }
    }

    // Snackbar for status messages
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(exportError) {
        exportError?.let {
            snackbarHostState.showSnackbar("Export failed: $it")
            viewModel.clearExportError()
        }
    }
    LaunchedEffect(importError) {
        importError?.let {
            snackbarHostState.showSnackbar("Import failed: $it")
            viewModel.clearImportError()
        }
    }
    LaunchedEffect(importResult) {
        importResult?.let {
            snackbarHostState.showSnackbar("Imported ${it.tracksImported} tracks (${it.totalPoints} points)")
            viewModel.clearImportResult()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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

        // ── SUBSCRIPTION BANNER / PLAN INFO ──────────────
        item {
            if (isPremium) {
                // Show current subscription plan details
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Primary.copy(alpha = 0.06f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = Accent.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.WorkspacePremium,
                                        contentDescription = null,
                                        tint = Accent,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (subscriptionStatus.isTrialActive) "Free Trial" else "Premium",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = Primary
                                )
                                if (subscriptionStatus.isTrialActive) {
                                    Text(
                                        "${subscriptionStatus.trialDaysRemaining} days remaining",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    subscriptionStatus.plan?.let { plan ->
                                        Text(
                                            "${plan.label} \u2022 ${plan.price}${plan.periodLabel}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Surface(
                                color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "Active",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF2E7D32),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                        if (subscriptionStatus.isTrialActive && !subscriptionStatus.isSubscribed) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = Primary.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToSubscription() }
                            ) {
                                Text(
                                    "Subscribe to keep Premium after trial ends",
                                    fontSize = 13.sp,
                                    color = Primary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Primary.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSubscription() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Accent.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.WorkspacePremium,
                                    contentDescription = null,
                                    tint = Accent,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Upgrade to Premium",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Primary
                            )
                            Text(
                                "Unlock cars, webhooks, track playback & more",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // ── PERMISSIONS (collapsible) ────────────────────
        item {
            var permissionsExpanded by remember { mutableStateOf(false) }
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { permissionsExpanded = !permissionsExpanded }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Permissions",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (permissionsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (permissionsExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                AnimatedVisibility(visible = permissionsExpanded) {
                    PermissionsSection()
                }
            }
        }

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

        // ── MOTION SENSORS ───────────────────────────────
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

        // ── Fusion summary card ──
        item {
            val hasSensorData = motionState.accelerationMagnitude > 0f || motionState.rotationRate > 0f
            val confidencePct = (motionState.motionConfidence * 100).toInt()
            val confidenceColor = when {
                confidencePct > 70 -> PrimaryLight
                confidencePct > 35 -> Accent
                else -> Stationary
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (hasSensorData) {
                                if (motionState.isDeviceMoving) PrimaryLight.copy(alpha = 0.15f)
                                else Stationary.copy(alpha = 0.15f)
                            } else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (motionState.isDeviceMoving) Icons.Filled.DirectionsWalk
                                    else if (hasSensorData) Icons.Filled.PauseCircle
                                    else Icons.Filled.SensorsOff,
                                    contentDescription = null,
                                    tint = if (hasSensorData) {
                                        if (motionState.isDeviceMoving) PrimaryLight else Stationary
                                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (hasSensorData) {
                                    if (motionState.isDeviceMoving) "Motion Detected" else "Stationary"
                                } else "Sensors Inactive",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            if (hasSensorData) {
                                Text(
                                    "Fusion confidence: $confidencePct%",
                                    fontSize = 13.sp,
                                    color = confidenceColor,
                                    fontWeight = FontWeight.Medium
                                )
                            } else {
                                Text(
                                    "Start tracking to see live sensor data",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (hasSensorData) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (motionState.gpsNeeded) PrimaryLight.copy(alpha = 0.15f)
                                    else Stationary.copy(alpha = 0.12f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (motionState.gpsNeeded) Icons.Filled.GpsFixed else Icons.Filled.GpsOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (motionState.gpsNeeded) PrimaryLight else Stationary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        if (motionState.gpsNeeded) "GPS ON" else "GPS OFF",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (motionState.gpsNeeded) PrimaryLight else Stationary
                                    )
                                }
                            }
                            // Vehicle motion badge
                            if (motionState.vehicleMotionDetected) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Driving.copy(alpha = 0.15f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Filled.DirectionsCar,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = Driving
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "VEHICLE",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Driving
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── Fusion algorithm bar ──
                    if (hasSensorData) {
                        Spacer(modifier = Modifier.height(14.dp))
                        // Confidence bar
                        LinearProgressIndicator(
                            progress = { motionState.motionConfidence },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = confidenceColor,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (motionState.stepPermissionGranted)
                                    "Steps 55%  •  Accel 25%  •  Gyro 20%"
                                else
                                    "Accel 70%  •  Gyro 30%  (steps: no permission)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        // ── Individual sensor cards (2-column grid) ──
        item {
            val hasSensorData = motionState.accelerationMagnitude > 0f || motionState.rotationRate > 0f

            if (hasSensorData) {
                Spacer(modifier = Modifier.height(10.dp))

                // Row 1: Accelerometer + Gyroscope
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SensorCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Vibration,
                        title = "Accelerometer",
                        value = String.format(java.util.Locale.US, "%.2f", motionState.accelerationMagnitude),
                        unit = "m/s\u00B2",
                        isActive = motionState.accelerationMagnitude > 0.4f,
                        activeColor = Accent
                    )
                    SensorCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.RotateRight,
                        title = "Gyroscope",
                        value = String.format(java.util.Locale.US, "%.3f", motionState.rotationRate),
                        unit = "rad/s",
                        isActive = motionState.rotationRate > 0.08f,
                        activeColor = Secondary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Row 2: Step Counter + Magnetometer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SensorCard(
                        modifier = Modifier.weight(1f),
                        icon = if (motionState.stepPermissionGranted) Icons.Filled.DirectionsWalk
                               else Icons.Filled.Lock,
                        title = "Step Counter",
                        value = if (motionState.stepPermissionGranted) "${motionState.steps}" else "--",
                        unit = if (motionState.stepPermissionGranted) "steps" else "",
                        subtitle = if (!motionState.stepPermissionGranted) "no permission"
                                   else if (motionState.stepDetected) "stepping now"
                                   else "idle",
                        isActive = motionState.stepPermissionGranted && motionState.stepDetected,
                        activeColor = if (motionState.stepPermissionGranted) Walking else Stationary
                    )
                    SensorCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Explore,
                        title = "Magnetometer",
                        value = String.format(java.util.Locale.US, "%.0f", motionState.headingDeg),
                        unit = "\u00B0 ${compassDirection(motionState.headingDeg)}",
                        isActive = true,
                        activeColor = Cycling
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Row 3: Dead Reckoning (full width)
                Card(
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Driving.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Timeline,
                                    contentDescription = null,
                                    tint = Driving,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Dead Reckoning",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                if (motionState.stepPermissionGranted)
                                    "Estimated displacement between GPS fixes"
                                else
                                    "Requires Activity Recognition permission",
                                fontSize = 11.sp,
                                color = if (motionState.stepPermissionGranted)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else Error
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                if (!motionState.stepPermissionGranted) "--"
                                else if (motionState.displacementMeters < 1000) {
                                    String.format(java.util.Locale.US, "%.1f m", motionState.displacementMeters)
                                } else {
                                    String.format(java.util.Locale.US, "%.2f km", motionState.displacementMeters / 1000)
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Driving
                            )
                            Text(
                                "heading ${String.format(java.util.Locale.US, "%.0f", motionState.headingDeg)}\u00B0",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ── SMARTWATCH ───────────────────────────────────
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

        // Connection status card
        item {
            val isConnected = wearableState is WearableConnectionState.Connected
            val isScanning = wearableState is WearableConnectionState.Scanning
            val isConnecting = wearableState is WearableConnectionState.Connecting

            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = when {
                                isConnected -> PrimaryLight.copy(alpha = 0.15f)
                                isScanning || isConnecting -> Accent.copy(alpha = 0.12f)
                                wearableState is WearableConnectionState.Error -> Error.copy(alpha = 0.12f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    when {
                                        isConnected -> Icons.Filled.Watch
                                        isScanning -> Icons.Filled.BluetoothSearching
                                        isConnecting -> Icons.Filled.BluetoothConnected
                                        wearableState is WearableConnectionState.Error -> Icons.Filled.WatchOff
                                        else -> Icons.Filled.Watch
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        isConnected -> PrimaryLight
                                        isScanning || isConnecting -> Accent
                                        wearableState is WearableConnectionState.Error -> Error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            when (val state = wearableState) {
                                is WearableConnectionState.Connected -> {
                                    Text(state.device.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = PrimaryLight,
                                            modifier = Modifier.size(8.dp)
                                        ) {}
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "Connected",
                                            fontSize = 13.sp,
                                            color = PrimaryLight,
                                            fontWeight = FontWeight.Medium
                                        )
                                        wearableReading?.manufacturerName?.let { mfr ->
                                            Text(
                                                "  •  $mfr",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    wearableReading?.modelNumber?.let { model ->
                                        Text(
                                            model,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                is WearableConnectionState.Scanning -> {
                                    Text("Scanning...", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(
                                        "Looking for BLE heart rate devices",
                                        fontSize = 13.sp,
                                        color = Accent
                                    )
                                }
                                is WearableConnectionState.Connecting -> {
                                    Text("Connecting...", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(
                                        "Establishing BLE connection",
                                        fontSize = 13.sp,
                                        color = Accent
                                    )
                                }
                                is WearableConnectionState.Error -> {
                                    Text("Connection Error", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(state.message, fontSize = 13.sp, color = Error)
                                }
                                is WearableConnectionState.Disconnected -> {
                                    Text("No Watch Connected", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(
                                        "Auto-connects when tracking starts",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (isScanning || isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Accent
                            )
                        }
                    }
                }
            }
        }

        // Live data cards (visible when connected)
        item {
            val isConnected = wearableState is WearableConnectionState.Connected
            val reading = wearableReading

            if (isConnected && reading != null) {
                Spacer(modifier = Modifier.height(10.dp))

                // Cadence + Watch Battery
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SensorCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Speed,
                        title = "Cadence",
                        value = reading.cadence?.toString() ?: "--",
                        unit = if (reading.cadence != null) "spm" else "",
                        subtitle = when {
                            reading.cadence == null -> "waiting for data"
                            reading.cadence == 0 -> "stopped"
                            reading.cadence < 100 -> "walking pace"
                            reading.cadence < 180 -> "running pace"
                            else -> "sprinting"
                        },
                        isActive = reading.cadence != null && reading.cadence > 0,
                        activeColor = Cycling
                    )

                    SensorCard(
                        modifier = Modifier.weight(1f),
                        icon = when {
                            (reading.batteryLevel ?: 0) > 80 -> Icons.Filled.BatteryFull
                            (reading.batteryLevel ?: 0) > 30 -> Icons.Filled.Battery4Bar
                            (reading.batteryLevel ?: 0) > 10 -> Icons.Filled.Battery2Bar
                            else -> Icons.Filled.Battery0Bar
                        },
                        title = "Watch Battery",
                        value = reading.batteryLevel?.toString() ?: "--",
                        unit = if (reading.batteryLevel != null) "%" else "",
                        subtitle = when {
                            reading.batteryLevel == null -> "waiting for data"
                            reading.batteryLevel > 80 -> "fully charged"
                            reading.batteryLevel > 30 -> "good"
                            reading.batteryLevel > 10 -> "low"
                            else -> "critical"
                        },
                        isActive = reading.batteryLevel != null,
                        activeColor = when {
                            (reading.batteryLevel ?: 0) > 30 -> PrimaryLight
                            (reading.batteryLevel ?: 0) > 10 -> Accent
                            else -> Error
                        }
                    )
                }

                // Last updated timestamp
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Last updated: ${formatWearableTimestamp(reading.timestamp)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            }
        }

        // ── USER PROFILE ─────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            var profileExpanded by remember { mutableStateOf(false) }
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.animateContentSize()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header row with expand/collapse
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { profileExpanded = !profileExpanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = Primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "User Profile",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                buildString {
                                    if (settings.userName.isNotEmpty()) append(settings.userName)
                                    else append("Not set")
                                    append("  \u2022  ${settings.userWeightKg.toInt()} kg  \u2022  ${settings.userHeightCm.toInt()} cm")
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { profileExpanded = !profileExpanded }) {
                            Icon(
                                if (profileExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (profileExpanded) "Collapse" else "Expand"
                            )
                        }
                    }

                    // Expandable content
                    if (profileExpanded) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // User Name
                            var nameText by remember(settings.userName) { mutableStateOf(settings.userName) }
                            OutlinedTextField(
                                value = nameText,
                                onValueChange = {
                                    nameText = it
                                    viewModel.updateUserName(it)
                                },
                                label = { Text("Name") },
                                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )

                            // Weight
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.MonitorWeight, contentDescription = null, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Weight", fontWeight = FontWeight.Medium)
                                    Text(
                                        "${settings.userWeightKg.toInt()} kg",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Slider(
                                value = settings.userWeightKg,
                                onValueChange = { viewModel.updateUserWeightKg(it) },
                                valueRange = 30f..200f,
                                steps = 169
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("30 kg", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("200 kg", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            // Height
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Height, contentDescription = null, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Height", fontWeight = FontWeight.Medium)
                                    Text(
                                        "${settings.userHeightCm.toInt()} cm",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Slider(
                                value = settings.userHeightCm,
                                onValueChange = { viewModel.updateUserHeightCm(it) },
                                valueRange = 100f..220f,
                                steps = 119
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("100 cm", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("220 cm", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        // ── CAR PROFILES ─────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "My Cars",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .weight(1f)
                )
                if (isPremium) {
                    TextButton(onClick = { showAddCarDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Car")
                    }
                } else {
                    TextButton(onClick = onNavigateToSubscription) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = Accent)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Premium", color = Accent)
                    }
                }
            }
        }

        if (!isPremium) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.clickable { onNavigateToSubscription() }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = Accent.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Premium Feature",
                            fontWeight = FontWeight.Medium,
                            color = Accent
                        )
                        Text(
                            "Upgrade to add cars and track ride costs",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } else if (allCars.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.DirectionsCar,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No cars added yet",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Add a car to track ride costs",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showAddCarDialog = true },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Your First Car")
                        }
                    }
                }
            }
        } else {
            items(allCars.size) { index ->
                val car = allCars[index]
                val isSelected = settings.selectedCarId == car.id
                CarProfileCard(
                    car = car,
                    isSelected = isSelected,
                    currency = settings.currency,
                    onSelect = { viewModel.selectCar(if (isSelected) null else car.id) },
                    onEdit = { editingCar = car },
                    onDelete = { viewModel.deleteCar(car.id) }
                )
            }
        }

        // ── CURRENCY ─────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Currency",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .weight(1f)
                )
                if (!isPremium) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = "Premium",
                        tint = Accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = if (!isPremium) Modifier.clickable { onNavigateToSubscription() } else Modifier
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Symbol shown with ride costs",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("$", "\u20AC", "\u00A3", "\u00A5", "\u20BD", "\u058F").forEach { symbol ->
                            FilterChip(
                                selected = settings.currency == symbol,
                                onClick = {
                                    if (isPremium) viewModel.updateCurrency(symbol)
                                    else onNavigateToSubscription()
                                },
                                label = { Text(symbol, fontSize = 14.sp) },
                                enabled = isPremium
                            )
                        }
                    }
                }
            }
        }

        // ── ACTIVITY TYPES (collapsible) ─────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            var activitySectionExpanded by remember { mutableStateOf(false) }
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.animateContentSize()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Section header with expand/collapse
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isPremium) activitySectionExpanded = !activitySectionExpanded
                                else onNavigateToSubscription()
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.DirectionsRun,
                                    contentDescription = null,
                                    tint = Primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Activity Types",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val activeCount = settings.activityConfigs.count { it.isActive }
                            Text(
                                "$activeCount of ${settings.activityConfigs.size} active",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!isPremium) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "Premium",
                                tint = Accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = {
                            if (isPremium) activitySectionExpanded = !activitySectionExpanded
                            else onNavigateToSubscription()
                        }) {
                            Icon(
                                if (activitySectionExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (activitySectionExpanded) "Collapse" else "Expand"
                            )
                        }
                    }

                    if (activitySectionExpanded && isPremium) {
                        Spacer(modifier = Modifier.height(12.dp))

                        settings.activityConfigs.forEachIndexed { _, cfg ->
                            var expanded by remember { mutableStateOf(false) }
                            var editMinText by remember(cfg.minSpeedKmh) {
                                mutableStateOf(cfg.minSpeedKmh.toInt().toString())
                            }
                            var editMaxText by remember(cfg.maxSpeedKmh) {
                                mutableStateOf(cfg.maxSpeedKmh.toInt().toString())
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .animateContentSize()
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            activityIconForSettings(cfg.iconName),
                                            contentDescription = null,
                                            tint = Color(cfg.colorHex),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { expanded = !expanded }
                                        ) {
                                            Text(cfg.displayName, fontWeight = FontWeight.Medium)
                                            Text(
                                                "${cfg.minSpeedKmh.toInt()}-${cfg.maxSpeedKmh.toInt()} km/h",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = { expanded = !expanded },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                contentDescription = if (expanded) "Collapse" else "Edit speed range",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Switch(
                                            checked = cfg.isActive,
                                            onCheckedChange = { active ->
                                                viewModel.toggleActivityConfig(cfg.activityType, active)
                                            }
                                        )
                                    }

                                    // Expandable speed range editor with text fields
                                    if (expanded) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = editMinText,
                                                onValueChange = { value ->
                                                    editMinText = value.filter { it.isDigit() }
                                                },
                                                label = { Text("Min km/h") },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                keyboardActions = KeyboardActions(onDone = {
                                                    val min = editMinText.toFloatOrNull() ?: cfg.minSpeedKmh
                                                    val max = editMaxText.toFloatOrNull() ?: cfg.maxSpeedKmh
                                                    if (min < max) {
                                                        viewModel.updateActivitySpeedRange(cfg.activityType, min, max)
                                                    }
                                                }),
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(10.dp),
                                                textStyle = TextStyle(
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(cfg.colorHex)
                                                )
                                            )
                                            Text(
                                                "\u2192",
                                                fontSize = 16.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            OutlinedTextField(
                                                value = editMaxText,
                                                onValueChange = { value ->
                                                    editMaxText = value.filter { it.isDigit() }
                                                },
                                                label = { Text("Max km/h") },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                keyboardActions = KeyboardActions(onDone = {
                                                    val min = editMinText.toFloatOrNull() ?: cfg.minSpeedKmh
                                                    val max = editMaxText.toFloatOrNull() ?: cfg.maxSpeedKmh
                                                    if (min < max) {
                                                        viewModel.updateActivitySpeedRange(cfg.activityType, min, max)
                                                    }
                                                }),
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(10.dp),
                                                textStyle = TextStyle(
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(cfg.colorHex)
                                                )
                                            )
                                            FilledIconButton(
                                                onClick = {
                                                    val min = editMinText.toFloatOrNull() ?: cfg.minSpeedKmh
                                                    val max = editMaxText.toFloatOrNull() ?: cfg.maxSpeedKmh
                                                    if (min < max) {
                                                        viewModel.updateActivitySpeedRange(cfg.activityType, min, max)
                                                    }
                                                },
                                                modifier = Modifier.size(40.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = IconButtonDefaults.filledIconButtonColors(
                                                    containerColor = Color(cfg.colorHex).copy(alpha = 0.15f),
                                                    contentColor = Color(cfg.colorHex)
                                                )
                                            ) {
                                                Icon(Icons.Filled.Check, contentDescription = "Apply", modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showAddActivityDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Activity Type")
                        }
                    }
                }
            }
        }

        // ── TRACKING MODE ─────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Tracking Mode",
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
                    TrackingMode.entries.forEach { mode ->
                        val (icon, description) = when (mode) {
                            TrackingMode.HIGH_ACCURACY -> Icons.Filled.GpsFixed to "Best precision, uses configured interval"
                            TrackingMode.ENERGY_EFFICIENCY -> Icons.Filled.BatterySaver to "Fixed 26s interval, saves battery"
                            TrackingMode.AI_BATTERY_SAVER -> Icons.Filled.AutoAwesome to "AI adjusts interval by speed & charging"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.updateTrackingMode(mode) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.trackingMode == mode,
                                onClick = { viewModel.updateTrackingMode(mode) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mode.label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(
                                    description,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (settings.trackingMode == TrackingMode.AI_BATTERY_SAVER) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                "Charging = 3s | Highway = 15s | City = 5s | Walking = 3s | Stationary = 10s",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                lineHeight = 16.sp
                            )
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

        // Record interval (only show when High Accuracy is selected)
        if (settings.trackingMode == TrackingMode.HIGH_ACCURACY) {
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
        }

        // ── AI SETTINGS ─────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            var aiSectionExpanded by remember { mutableStateOf(false) }
            val localModelReachable by viewModel.localModelReachable.collectAsState()
            val localModelChecking by viewModel.localModelChecking.collectAsState()
            val installedLocalModels by viewModel.installedModels.collectAsState()
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.animateContentSize()) {
                    // Section header with expand/collapse
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { aiSectionExpanded = !aiSectionExpanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "AI & Detection",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                when (settings.aiMode) {
                                    AiMode.CLOUD_AI -> "Cloud: ${settings.cloudAiProvider.label}"
                                    AiMode.OFF -> "AI Off"
                                    else -> "Local Model"
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { aiSectionExpanded = !aiSectionExpanded }) {
                            Icon(
                                if (aiSectionExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (aiSectionExpanded) "Collapse" else "Expand"
                            )
                        }
                    }

                    if (aiSectionExpanded) {
                        @Suppress("DEPRECATION")
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Active AI Provider display
                            val activeProviderText = when (settings.aiMode) {
                                AiMode.LOCAL_MODEL -> {
                                    val activeModel = installedLocalModels.find { it.isActive }
                                    if (activeModel != null) "Local: ${activeModel.displayName}"
                                    else "Local Model (none downloaded)"
                                }
                                AiMode.CLOUD_AI -> "Cloud: ${settings.cloudAiProvider.label}"
                                AiMode.OFF -> "Off"
                                else -> "Local Model"
                            }
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        when (settings.aiMode) {
                                            AiMode.CLOUD_AI -> Icons.Filled.Cloud
                                            AiMode.OFF -> Icons.Filled.PowerSettingsNew
                                            else -> Icons.Filled.Memory
                                        },
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Active Provider", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(activeProviderText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // AI Mode selection — Local Model
                            Text("Select AI Provider", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.updateAiMode(AiMode.LOCAL_MODEL) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = settings.aiMode == AiMode.LOCAL_MODEL,
                                    onClick = { viewModel.updateAiMode(AiMode.LOCAL_MODEL) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(Icons.Filled.Memory, null, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Local Model", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Text("On-device AI, private, no internet needed", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            // AI Mode selection — Cloud AI
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.updateAiMode(AiMode.CLOUD_AI)
                                        viewModel.openCloudAiWizard()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = settings.aiMode == AiMode.CLOUD_AI,
                                    onClick = {
                                        viewModel.updateAiMode(AiMode.CLOUD_AI)
                                        viewModel.openCloudAiWizard()
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(Icons.Filled.Cloud, null, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Cloud AI", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Text("ChatGPT, DeepSeek, Gemini, Claude", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (settings.aiMode == AiMode.CLOUD_AI && settings.cloudAiApiKey.isNotBlank()) {
                                        Text(
                                            "${settings.cloudAiProvider.label} configured",
                                            fontSize = 11.sp, color = Accent
                                        )
                                    }
                                }
                            }

                            // AI Mode selection — Off
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.updateAiMode(AiMode.OFF) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = settings.aiMode == AiMode.OFF,
                                    onClick = { viewModel.updateAiMode(AiMode.OFF) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(Icons.Filled.PowerSettingsNew, null, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Off", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Text("Disable AI analysis", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Local Model section — download/manage
                            if (settings.aiMode == AiMode.LOCAL_MODEL) {
                                OutlinedButton(
                                    onClick = onNavigateToLocalAiWizard,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (installedLocalModels.isEmpty()) "Download Local Model" else "Download Another Model", fontSize = 13.sp)
                                }

                                // Installed models list with delete
                                if (installedLocalModels.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Downloaded Models", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    installedLocalModels.forEach { model ->
                                        var showDeleteConfirm by remember { mutableStateOf(false) }
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                if (model.isActive) Icons.Filled.CheckCircle else Icons.Filled.Memory,
                                                null,
                                                tint = if (model.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(model.displayName, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                                Text("${model.sizeMb} MB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(32.dp)) {
                                                Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        if (showDeleteConfirm) {
                                            AlertDialog(
                                                onDismissRequest = { showDeleteConfirm = false },
                                                title = { Text("Remove Model") },
                                                text = { Text("Remove ${model.displayName}? This will delete ${model.sizeMb} MB from your device.") },
                                                confirmButton = { TextButton(onClick = { viewModel.deleteLocalModel(model.modelId); showDeleteConfirm = false }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
                                                dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
                                            )
                                        }
                                    }
                                }
                            }

                            // Cloud AI section — configure
                            if (settings.aiMode == AiMode.CLOUD_AI) {
                                OutlinedButton(
                                    onClick = { viewModel.openCloudAiWizard() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Settings, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Configure Cloud AI", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
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
                    icon = Icons.Filled.DirectionsRun,
                    title = "Auto-Start Tracking",
                    subtitle = "Start a track when walking is detected, stop after a minute of no steps",
                    checked = settings.autoStartTracking,
                    onCheckedChange = { viewModel.updateAutoStartTracking(it) }
                )
            }
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

        // ── WEBHOOK ──────────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            var webhookExpanded by remember { mutableStateOf(false) }
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.animateContentSize()) {
                    // Section header with expand/collapse
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isPremium) webhookExpanded = !webhookExpanded
                                else onNavigateToSubscription()
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Webhook",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                if (settings.webhookEnabled) "Sending to ${settings.webhookUrl.ifBlank { "no URL" }}"
                                else "Disabled",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        if (!isPremium) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "Premium",
                                tint = Accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = {
                            if (isPremium) webhookExpanded = !webhookExpanded
                            else onNavigateToSubscription()
                        }) {
                            Icon(
                                if (webhookExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (webhookExpanded) "Collapse" else "Expand"
                            )
                        }
                    }

                    if (webhookExpanded && isPremium) {
                        @Suppress("DEPRECATION")
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Enable switch
                            SettingsSwitch(
                                icon = Icons.Filled.PowerSettingsNew,
                                title = "Enable Webhook",
                                subtitle = "POST track data to an external URL",
                                checked = settings.webhookEnabled,
                                onCheckedChange = { viewModel.updateWebhookEnabled(it) }
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // URL field
                            var webhookUrlInput by remember(settings.webhookUrl) {
                                mutableStateOf(settings.webhookUrl)
                            }
                            OutlinedTextField(
                                value = webhookUrlInput,
                                onValueChange = {
                                    webhookUrlInput = it
                                    viewModel.updateWebhookUrl(it)
                                },
                                label = { Text("Webhook URL") },
                                placeholder = { Text("https://pathwise.art") },
                                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Unique key (read-only display + regenerate)
                            OutlinedTextField(
                                value = settings.webhookKey,
                                onValueChange = {},
                                label = { Text("Unique Key") },
                                leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                                readOnly = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                trailingIcon = {
                                    IconButton(onClick = {
                                        viewModel.updateWebhookKey(
                                            java.util.UUID.randomUUID().toString().replace("-", "").take(16)
                                        )
                                    }) {
                                        Icon(Icons.Filled.Refresh, contentDescription = "Regenerate key")
                                    }
                                }
                            )
                            Text(
                                "Sent as X-Webhook-Key header for authentication",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Share tracking URL button
                            val trackingUrl = remember(settings.webhookUrl, settings.webhookKey) {
                                if (settings.webhookUrl.isNotBlank()) {
                                    val encodedKey = java.net.URLEncoder.encode(settings.webhookKey, "UTF-8")
                                    settings.webhookUrl.trimEnd('/') + "/api/hook?key=$encodedKey"
                                } else ""
                            }
                            OutlinedButton(
                                onClick = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "Pathwise – Live Tracking URL")
                                        putExtra(Intent.EXTRA_TEXT, trackingUrl)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share tracking URL"))
                                },
                                enabled = settings.webhookUrl.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Share Tracking URL")
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Hook interval
                            Text("Hook Interval", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Send data every ${formatInterval(settings.webhookIntervalMs)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            val intervalOptions = listOf(5000L, 10000L, 30000L, 60000L)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                intervalOptions.forEach { interval ->
                                    FilterChip(
                                        selected = settings.webhookIntervalMs == interval,
                                        onClick = { viewModel.updateWebhookIntervalMs(interval) },
                                        label = { Text(formatInterval(interval), fontSize = 13.sp) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Payload preview
                            Text("Payload Preview", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    """
{
  "key": "${settings.webhookKey}",
  "timestamp": 1700000000000,
  "lat": 40.7128,
  "lng": -74.0060,
  "alt": 10.0,
  "speed_kmh": 5.2,
  "bearing": 180.0,
  "accuracy_m": 4.5,
  "activity": "WALKING",
  "battery": 85
}
                                    """.trimIndent(),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── DATA MANAGEMENT ─────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Data Management",
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
                    Text(
                        "Backup & Restore",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Export or import all tracks, GPS points, health data, and AI analyses as a single backup file.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Export button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { viewModel.exportAllData() },
                            enabled = !isExporting && !isImporting,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            if (isExporting) {
                                Icon(
                                    Icons.Filled.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color.White
                                )
                            } else {
                                Icon(
                                    Icons.Filled.FileDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isExporting) "Exporting..." else "Export All")
                        }

                        // Import button
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(arrayOf(
                                    "application/json",
                                    "*/*"
                                ))
                            },
                            enabled = !isExporting && !isImporting,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isImporting) {
                                Icon(
                                    Icons.Filled.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Filled.FileUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isImporting) "Importing..." else "Import All")
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val versionName = remember {
                        try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
                        } catch (_: Exception) { "1.0.0" }
                    }
                    val versionCode = remember {
                        try {
                            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
                        } catch (_: Exception) { 1L }
                    }
                    Text("Pathwise", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Version $versionName ($versionCode)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Pathwise is a privacy-first GPS tracking and activity analysis app for " +
                            "walkers, runners, cyclists, hikers, and commuters. It counts your steps, " +
                            "predicts calories burned using MET-based calculations, and automatically " +
                            "detects your activity type using on-device AI — no data leaves your device. " +
                            "Export routes as GPX, CSV, or JSON, connect a Bluetooth wearable for live " +
                            "cadence data, and track vehicle fuel costs across multiple car profiles.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Not a Medical Device",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Pathwise is not a medical device and is not intended to diagnose, treat, " +
                            "cure, or prevent any disease or medical condition. Fitness data " +
                            "(step counts, calorie estimates) is for general fitness and " +
                            "informational purposes only. Always consult a qualified healthcare " +
                            "professional for medical advice.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        lineHeight = 16.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "\u00a9 ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} Pathwise. All rights reserved.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    // Snackbar
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    } // end Box

    // Add Car Dialog
    if (showAddCarDialog) {
        CarProfileDialog(
            car = null,
            currency = settings.currency,
            onDismiss = { showAddCarDialog = false },
            onSave = { car ->
                viewModel.addCar(car)
                showAddCarDialog = false
            }
        )
    }

    // Edit Car Dialog
    editingCar?.let { car ->
        CarProfileDialog(
            car = car,
            currency = settings.currency,
            onDismiss = { editingCar = null },
            onSave = { updated ->
                viewModel.updateCar(updated)
                editingCar = null
            }
        )
    }

    // Add Activity Type Dialog
    if (showAddActivityDialog) {
        AddActivityDialog(
            onDismiss = { showAddActivityDialog = false },
            onSave = { config ->
                viewModel.addActivityConfig(config)
                showAddActivityDialog = false
            }
        )
    }
}

@Composable
private fun CarProfileCard(
    car: CarProfile,
    isSelected: Boolean,
    currency: String = "$",
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 3.dp else 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Car icon with selection indicator
                Surface(
                    color = if (isSelected) Primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (car.isElectric) Icons.Filled.ElectricCar else Icons.Filled.DirectionsCar,
                            contentDescription = null,
                            tint = if (isSelected) Primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        car.name.ifEmpty { car.carModel.ifEmpty { "Unnamed Car" } },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        buildString {
                            if (car.carModel.isNotEmpty()) append(car.carModel)
                            if (car.carYear > 0) {
                                if (isNotEmpty()) append(" ")
                                append("(${car.carYear})")
                            }
                        }.ifEmpty { "No details" },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isSelected) {
                    Surface(
                        color = Primary,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "Active",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Car details summary
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (car.isElectric) {
                    DetailChip(Icons.Filled.Bolt, "${String.format("%.0f", car.batteryCapacityKwh)} kWh")
                    DetailChip(Icons.Filled.Speed, "${String.format("%.1f", car.electricConsumption)} kWh/100km")
                    if (car.fuelPricePerLiter > 0) {
                        DetailChip(Icons.Filled.Sell, "$currency${String.format("%.2f", car.fuelPricePerLiter)}/kWh")
                    }
                } else {
                    if (car.engineSize > 0) {
                        DetailChip(Icons.Filled.Speed, "${String.format("%.1f", car.engineSize)}L ${car.fuelType.label}")
                    }
                    DetailChip(Icons.Filled.WaterDrop, "${String.format("%.1f", car.fuelConsumption)} L/100km")
                    if (car.fuelPricePerLiter > 0) {
                        DetailChip(Icons.Filled.Sell, "$currency${String.format("%.2f", car.fuelPricePerLiter)}/L")
                    }
                }
            }

            // Action buttons
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit", fontSize = 13.sp)
                }
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = Error)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", fontSize = 13.sp)
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Car") },
            text = { Text("Delete \"${car.name.ifEmpty { car.carModel }}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DetailChip(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarProfileDialog(
    car: CarProfile?,
    currency: String = "$",
    onDismiss: () -> Unit,
    onSave: (CarProfile) -> Unit
) {
    val isEditing = car != null
    var name by remember { mutableStateOf(car?.name ?: "") }
    var carModel by remember { mutableStateOf(car?.carModel ?: "") }
    var carYear by remember { mutableStateOf(car?.carYear?.toFloat() ?: 2024f) }
    var engineSize by remember { mutableStateOf(car?.engineSize?.let { if (it > 0) String.format("%.1f", it) else "" } ?: "") }
    var isElectric by remember { mutableStateOf(car?.isElectric ?: false) }
    var fuelType by remember { mutableStateOf(car?.fuelType ?: FuelType.PETROL) }
    var fuelPricePerLiter by remember { mutableStateOf(car?.fuelPricePerLiter?.let { if (it > 0) String.format("%.2f", it) else "" } ?: "") }
    var fuelConsumption by remember { mutableStateOf(car?.fuelConsumption?.let { String.format("%.1f", it) } ?: "8.0") }
    var batteryCapacityKwh by remember { mutableStateOf(car?.batteryCapacityKwh?.let { String.format("%.0f", it) } ?: "60") }
    var electricConsumption by remember { mutableStateOf(car?.electricConsumption?.let { String.format("%.1f", it) } ?: "15.0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit Car" else "Add Car") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    placeholder = { Text("e.g. My Daily Driver") },
                    leadingIcon = { Icon(Icons.Filled.Label, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = carModel,
                    onValueChange = { carModel = it },
                    label = { Text("Car Model") },
                    placeholder = { Text("e.g. Toyota Camry") },
                    leadingIcon = { Icon(Icons.Filled.DirectionsCar, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Year: ${carYear.toInt()}", fontWeight = FontWeight.Medium)
                }
                Slider(
                    value = carYear,
                    onValueChange = { carYear = it },
                    valueRange = 1990f..2026f,
                    steps = 35
                )

                OutlinedTextField(
                    value = engineSize,
                    onValueChange = { engineSize = it },
                    label = { Text("Engine Size (L)") },
                    placeholder = { Text("e.g. 2.0") },
                    leadingIcon = { Icon(Icons.Filled.Speed, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isElectric) Icons.Filled.ElectricCar else Icons.Filled.DirectionsCar,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Electric Vehicle", fontWeight = FontWeight.Medium)
                        Text(
                            if (isElectric) "Battery powered" else "Fuel powered",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = isElectric, onCheckedChange = { isElectric = it })
                }

                if (isElectric) {
                    OutlinedTextField(
                        value = batteryCapacityKwh,
                        onValueChange = { batteryCapacityKwh = it },
                        label = { Text("Battery Capacity (kWh)") },
                        placeholder = { Text("e.g. 60") },
                        leadingIcon = { Icon(Icons.Filled.BatteryChargingFull, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = electricConsumption,
                        onValueChange = { electricConsumption = it },
                        label = { Text("Avg Consumption (kWh/100km)") },
                        placeholder = { Text("e.g. 15") },
                        leadingIcon = { Icon(Icons.Filled.Bolt, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = fuelPricePerLiter,
                        onValueChange = { fuelPricePerLiter = it },
                        label = { Text("Price per kWh") },
                        placeholder = { Text("e.g. 0.15") },
                        leadingIcon = { Text(currency, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.LocalGasStation, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Fuel Type", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    }
                    Row {
                        FuelType.entries.forEach { type ->
                            FilterChip(
                                selected = fuelType == type,
                                onClick = { fuelType = type },
                                label = { Text(type.label, fontSize = 12.sp) },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = fuelConsumption,
                        onValueChange = { fuelConsumption = it },
                        label = { Text("Fuel Consumption (L/100km)") },
                        placeholder = { Text("e.g. 8.0") },
                        leadingIcon = { Icon(Icons.Filled.WaterDrop, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = fuelPricePerLiter,
                        onValueChange = { fuelPricePerLiter = it },
                        label = { Text("Price per Liter") },
                        placeholder = { Text("e.g. 1.50") },
                        leadingIcon = { Text(currency, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val profile = (car ?: CarProfile()).copy(
                        name = name,
                        carModel = carModel,
                        carYear = carYear.toInt(),
                        engineSize = engineSize.toFloatOrNull() ?: 0f,
                        isElectric = isElectric,
                        fuelType = fuelType,
                        fuelPricePerLiter = fuelPricePerLiter.toFloatOrNull() ?: 0f,
                        fuelConsumption = fuelConsumption.toFloatOrNull() ?: 8f,
                        batteryCapacityKwh = batteryCapacityKwh.toFloatOrNull() ?: 60f,
                        electricConsumption = electricConsumption.toFloatOrNull() ?: 15f
                    )
                    onSave(profile)
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isEditing) "Save" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
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
                            if (granted && item.permission == Manifest.permission.POST_NOTIFICATIONS) {
                                // Open app notification settings so the user can turn off notifications
                                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                } else {
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                }
                                context.startActivity(intent)
                            } else if (!granted) {
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
                            when {
                                granted && item.permission == Manifest.permission.POST_NOTIFICATIONS -> "Tap to manage"
                                granted -> "Granted"
                                else -> "Tap to grant"
                            },
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

// ═══════════════════════════════════════════════════════════
//  ACTIVITY TYPE HELPERS
// ═══════════════════════════════════════════════════════════

private val activityIcons = listOf(
    "DirectionsWalk" to Icons.Filled.DirectionsWalk,
    "DirectionsRun" to Icons.Filled.DirectionsRun,
    "DirectionsBike" to Icons.Filled.DirectionsBike,
    "DirectionsCar" to Icons.Filled.DirectionsCar,
    "Flight" to Icons.Filled.Flight,
    "Terrain" to Icons.Filled.Terrain,
    "PauseCircle" to Icons.Filled.PauseCircle,
    "Pool" to Icons.Filled.Pool,
    "FitnessCenter" to Icons.Filled.FitnessCenter,
    "Snowboarding" to Icons.Filled.Snowboarding,
    "Skateboarding" to Icons.Filled.Skateboarding,
    "Sailing" to Icons.Filled.Sailing,
)

private fun activityIconForSettings(name: String): ImageVector =
    activityIcons.firstOrNull { it.first == name }?.second ?: Icons.Filled.Circle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddActivityDialog(
    onDismiss: () -> Unit,
    onSave: (ActivityConfig) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedIcon by remember { mutableStateOf("Terrain") }
    var minSpeed by remember { mutableStateOf("") }
    var maxSpeed by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Activity Type", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Text("Icon", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    activityIcons.forEach { (iconName, icon) ->
                        FilterChip(
                            selected = selectedIcon == iconName,
                            onClick = { selectedIcon = iconName },
                            label = {
                                Icon(icon, contentDescription = iconName, modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minSpeed,
                        onValueChange = { minSpeed = it },
                        label = { Text("Min km/h") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = maxSpeed,
                        onValueChange = { maxSpeed = it },
                        label = { Text("Max km/h") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val min = minSpeed.toFloatOrNull() ?: 0f
                    val max = maxSpeed.toFloatOrNull() ?: 0f
                    if (name.isNotBlank() && max > min) {
                        onSave(
                            ActivityConfig(
                                activityType = name.uppercase().replace(" ", "_"),
                                displayName = name,
                                iconName = selectedIcon,
                                minSpeedKmh = min,
                                maxSpeedKmh = max,
                                colorHex = 0xFF4CAF50,
                                metValue = 3.0,
                                isActive = true
                            )
                        )
                    }
                },
                enabled = name.isNotBlank() &&
                        (minSpeed.toFloatOrNull() ?: -1f) >= 0f &&
                        (maxSpeed.toFloatOrNull() ?: 0f) > (minSpeed.toFloatOrNull() ?: 0f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

// ─── Sensor Cards ──────────────────────────────────────

@Composable
private fun SensorCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    value: String,
    unit: String,
    subtitle: String? = null,
    isActive: Boolean = false,
    activeColor: Color = PrimaryLight
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (isActive) activeColor.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (isActive) activeColor
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    value,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) activeColor
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    unit,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = if (isActive) activeColor.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

private fun formatWearableTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

private fun compassDirection(degrees: Float): String {
    val normalized = ((degrees % 360f) + 360f) % 360f
    return when {
        normalized < 22.5f  -> "N"
        normalized < 67.5f  -> "NE"
        normalized < 112.5f -> "E"
        normalized < 157.5f -> "SE"
        normalized < 202.5f -> "S"
        normalized < 247.5f -> "SW"
        normalized < 292.5f -> "W"
        normalized < 337.5f -> "NW"
        else                -> "N"
    }
}

// ── Cloud AI Setup Wizard ──────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudAiWizardDialog(
    currentProvider: CloudAiProvider,
    currentApiKey: String,
    currentEndpoint: String,
    currentModel: String,
    onDismiss: () -> Unit,
    onSave: (CloudAiProvider, String, String, String) -> Unit
) {
    var wizardStep by remember { mutableStateOf(0) }
    var provider by remember { mutableStateOf(currentProvider) }
    var apiKey by remember { mutableStateOf(currentApiKey) }
    var endpoint by remember { mutableStateOf(currentEndpoint) }
    var model by remember { mutableStateOf(currentModel) }

    // DeepSeek ships with a built-in key — skip the key-entry step entirely.
    val skipKeyStep = provider == CloudAiProvider.DEEPSEEK
    val totalSteps = if (skipKeyStep) 2 else 3

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    when (wizardStep) {
                        0 -> "Choose Provider"
                        1 -> "API Key"
                        else -> "Model Settings"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column {
                // Step indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    // When the key step is skipped, the second logical step
                    // (model) maps to dot index 1.
                    val currentDot = if (skipKeyStep && wizardStep == 2) 1 else wizardStep
                    repeat(totalSteps) { step ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (step == currentDot) 10.dp else 8.dp)
                                .then(
                                    Modifier
                                        .padding(0.dp) // placeholder for background
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (step <= currentDot)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(if (step == currentDot) 10.dp else 8.dp)
                            ) {}
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                when (wizardStep) {
                    // Step 1: Provider selection
                    0 -> {
                        Text(
                            "Select your cloud AI provider",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        CloudAiProvider.entries.forEach { p ->
                            val providerIcon = when (p) {
                                CloudAiProvider.OPENAI -> Icons.Filled.AutoAwesome
                                CloudAiProvider.ANTHROPIC -> Icons.Filled.Psychology
                                CloudAiProvider.GEMINI -> Icons.Filled.Lightbulb
                                CloudAiProvider.DEEPSEEK -> Icons.Filled.Explore
                                CloudAiProvider.CUSTOM -> Icons.Filled.Dns
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { provider = p }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = provider == p,
                                    onClick = { provider = p }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(providerIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(p.label, fontSize = 14.sp)
                            }
                        }
                    }
                    // Step 2: API Key
                    1 -> {
                        Text(
                            "Enter your ${provider.label} API key",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API Key") },
                            placeholder = { Text("sk-...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                        if (provider == CloudAiProvider.CUSTOM) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = endpoint,
                                onValueChange = { endpoint = it },
                                label = { Text("Endpoint URL") },
                                placeholder = { Text("https://api.example.com/v1") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )
                        }
                    }
                    // Step 3: Model
                    2 -> {
                        Text(
                            "Choose the model to use for activity analysis",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        val suggestedModels = when (provider) {
                            CloudAiProvider.OPENAI -> listOf("gpt-4o-mini", "gpt-4o")
                            CloudAiProvider.ANTHROPIC -> listOf("claude-haiku-4-5-20251001", "claude-sonnet-4-5-20250514")
                            CloudAiProvider.GEMINI -> listOf("gemini-2.0-flash", "gemini-2.5-pro-preview-06-05")
                            CloudAiProvider.DEEPSEEK -> listOf("deepseek-chat", "deepseek-reasoner")
                            CloudAiProvider.CUSTOM -> emptyList()
                        }
                        if (suggestedModels.isNotEmpty()) {
                            Text(
                                "Suggested models",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                suggestedModels.forEach { suggested ->
                                    FilterChip(
                                        selected = model == suggested,
                                        onClick = { model = suggested },
                                        label = { Text(suggested, fontSize = 12.sp) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text("Model name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (wizardStep < 2) {
                Button(
                    onClick = {
                        // Skip the key step for DeepSeek (bundled key).
                        wizardStep = if (wizardStep == 0 && skipKeyStep) 2 else wizardStep + 1
                    },
                    enabled = when (wizardStep) {
                        1 -> apiKey.isNotBlank()
                        else -> true
                    }
                ) {
                    Text("Next")
                }
            } else {
                Button(
                    onClick = {
                        val resolvedEndpoint = when (provider) {
                            CloudAiProvider.OPENAI -> endpoint.ifBlank { "https://api.openai.com/v1" }
                            CloudAiProvider.ANTHROPIC -> endpoint.ifBlank { "https://api.anthropic.com/v1" }
                            CloudAiProvider.GEMINI -> endpoint.ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
                            CloudAiProvider.DEEPSEEK -> endpoint.ifBlank { "https://api.deepseek.com" }
                            CloudAiProvider.CUSTOM -> endpoint
                        }
                        // For DeepSeek the provider falls back to the bundled
                        // key, so we don't need a user-entered value here.
                        onSave(provider, apiKey, resolvedEndpoint, model)
                    },
                    enabled = (skipKeyStep || apiKey.isNotBlank()) && model.isNotBlank()
                ) {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            if (wizardStep > 0) {
                TextButton(onClick = {
                    // Mirror the forward skip on the way back.
                    wizardStep = if (wizardStep == 2 && skipKeyStep) 0 else wizardStep - 1
                }) {
                    Text("Back")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
