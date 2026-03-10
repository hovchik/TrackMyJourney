package com.trackjourney.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.trackjourney.data.bluetooth.WearableConnectionState
import com.trackjourney.data.model.AiMode
import com.trackjourney.data.model.ActivityConfig
import com.trackjourney.data.model.CarProfile
import com.trackjourney.data.model.ExportFormat
import com.trackjourney.data.model.FuelType
import com.trackjourney.data.model.SubscriptionStatus
import com.trackjourney.data.model.TrackingMode
import com.trackjourney.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToSubscription: () -> Unit = {},
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

        // ── SUBSCRIPTION BANNER ────────────────────────
        if (!isPremium) {
            item {
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
            var aiSectionExpanded by remember { mutableStateOf(false) }
            val localModelReachable by viewModel.localModelReachable.collectAsState()
            val localModelChecking by viewModel.localModelChecking.collectAsState()
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
                                "Engine: ${settings.aiMode.label}",
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
                            // Auto detection switch
                            SettingsSwitch(
                                icon = Icons.Filled.Sensors,
                                title = "Auto Activity Detection",
                                subtitle = "Classify walking, driving, flying in real-time",
                                checked = settings.autoDetectActivity,
                                onCheckedChange = { viewModel.updateAutoDetect(it) }
                            )

                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "AI Engine",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Choose how activities are classified during tracking",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            AiMode.entries.forEach { mode ->
                                val icon = when (mode) {
                                    AiMode.RULE_BASED -> Icons.Filled.Speed
                                    AiMode.TFLITE_MODEL -> Icons.Filled.Psychology
                                    AiMode.LOCAL_MODEL -> Icons.Filled.PhoneAndroid
                                    AiMode.OFF -> Icons.Filled.PowerSettingsNew
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.updateAiMode(mode) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = settings.aiMode == mode,
                                        onClick = { viewModel.updateAiMode(mode) }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(mode.label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                        Text(
                                            mode.description,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        // Reachability status for Local Model
                                        if (mode == AiMode.LOCAL_MODEL) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (localModelChecking) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(12.dp),
                                                        strokeWidth = 1.5.dp
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        "Checking availability\u2026",
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                } else {
                                                    Icon(
                                                        if (localModelReachable) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp),
                                                        tint = if (localModelReachable) Accent else Error
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        if (localModelReachable) "Model available on device" else "Model not available",
                                                        fontSize = 11.sp,
                                                        color = if (localModelReachable) Accent else Error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Recheck button for local model
                            if (settings.aiMode == AiMode.LOCAL_MODEL) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { viewModel.checkLocalModelReachability() },
                                    enabled = !localModelChecking,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Re-check local model", fontSize = 13.sp)
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
                                        putExtra(Intent.EXTRA_SUBJECT, "Track My Journey – Live Tracking URL")
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
  "heart_rate": 72,
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
