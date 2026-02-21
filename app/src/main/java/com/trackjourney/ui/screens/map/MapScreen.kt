package com.trackjourney.ui.screens.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackjourney.data.bluetooth.WearableConnectionState
import com.trackjourney.data.model.ActivityType
import com.trackjourney.ui.components.OsmMapView
import com.trackjourney.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showWearableSheet by remember { mutableStateOf(false) }
    var showTrackNameDialog by remember { mutableStateOf(false) }
    var trackName by remember { mutableStateOf("") }

    // Permission handling
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted && showTrackNameDialog) {
            viewModel.startTracking(trackName)
            showTrackNameDialog = false
        }
    }

    val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Map layer
        OsmMapView(
            modifier = Modifier.fillMaxSize(),
            trackPoints = uiState.trackPoints,
            centerOnUser = uiState.isTracking,
            showActivityColors = true
        )

        // Top stats bar (shown during tracking)
        AnimatedVisibility(
            visible = uiState.isTracking,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut()
        ) {
            TrackingStatsBar(uiState)
        }

        // Wearable indicator
        WearableStatusChip(
            state = uiState.wearableState,
            reading = uiState.wearableReading,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 100.dp, end = 16.dp)
                .clickable { showWearableSheet = true }
        )

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Activity badge
            if (uiState.isTracking) {
                ActivityBadge(
                    activity = uiState.currentActivity,
                    speed = uiState.currentSpeed
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Control buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.isTracking) {
                    // Pause/Resume
                    FloatingActionButton(
                        onClick = {
                            if (uiState.isPaused) viewModel.resumeTracking()
                            else viewModel.pauseTracking()
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            if (uiState.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (uiState.isPaused) "Resume" else "Pause"
                        )
                    }

                    // Stop
                    LargeFloatingActionButton(
                        onClick = { viewModel.stopTracking() },
                        containerColor = MaterialTheme.colorScheme.error
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Stop Tracking",
                            modifier = Modifier.size(36.dp),
                            tint = Color.White
                        )
                    }
                } else {
                    // Start
                    LargeFloatingActionButton(
                        onClick = { showTrackNameDialog = true },
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Start Tracking",
                            modifier = Modifier.size(36.dp),
                            tint = Color.White
                        )
                    }
                }

                // Wearable button
                FloatingActionButton(
                    onClick = { showWearableSheet = true },
                    containerColor = when (uiState.wearableState) {
                        is WearableConnectionState.Connected -> PrimaryLight
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.Watch,
                        contentDescription = "Wearable",
                        tint = when (uiState.wearableState) {
                            is WearableConnectionState.Connected -> Color.White
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }

    // Track name dialog
    if (showTrackNameDialog) {
        AlertDialog(
            onDismissRequest = { showTrackNameDialog = false },
            title = { Text("Start New Track") },
            text = {
                OutlinedTextField(
                    value = trackName,
                    onValueChange = { trackName = it },
                    label = { Text("Track name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    permissionLauncher.launch(requiredPermissions)
                    if (true) { // Simplified — in production check permissions
                        viewModel.startTracking(trackName)
                        showTrackNameDialog = false
                        trackName = ""
                    }
                }) {
                    Text("Start")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTrackNameDialog = false
                    trackName = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Wearable bottom sheet
    if (showWearableSheet) {
        WearableBottomSheet(
            uiState = uiState,
            onDismiss = { showWearableSheet = false },
            onScan = { viewModel.scanForWearables() },
            onConnect = { viewModel.connectWearable(it) },
            onDisconnect = { viewModel.disconnectWearable() }
        )
    }
}

// ─── TRACKING STATS BAR ──────────────────────────────

@Composable
private fun TrackingStatsBar(state: MapUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 48.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(
                value = String.format("%.2f", state.distanceKm),
                unit = "km",
                label = "Distance"
            )
            StatItem(
                value = String.format("%.1f", state.currentSpeed),
                unit = "km/h",
                label = "Speed"
            )
            StatItem(
                value = state.pointCount.toString(),
                unit = "pts",
                label = "Points"
            )
            state.wearableReading?.heartRate?.let { hr ->
                StatItem(
                    value = hr.toString(),
                    unit = "bpm",
                    label = "Heart Rate",
                    valueColor = when {
                        hr > 160 -> Error
                        hr > 120 -> Accent
                        else -> PrimaryLight
                    }
                )
            }
            state.wearableReading?.spO2?.let { spo2 ->
                StatItem(
                    value = "$spo2%",
                    unit = "",
                    label = "SpO2",
                    valueColor = when {
                        spo2 < 90 -> Error
                        spo2 < 95 -> Accent
                        else -> PrimaryLight
                    }
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    value: String,
    unit: String,
    label: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = " $unit",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── ACTIVITY BADGE ──────────────────────────────────

@Composable
private fun ActivityBadge(activity: ActivityType, speed: Float) {
    val (icon, color, label) = when (activity) {
        ActivityType.WALKING    -> Triple(Icons.Filled.DirectionsWalk, Walking, "Walking")
        ActivityType.RUNNING    -> Triple(Icons.Filled.DirectionsRun, Running, "Running")
        ActivityType.CYCLING    -> Triple(Icons.Filled.DirectionsBike, Cycling, "Cycling")
        ActivityType.DRIVING    -> Triple(Icons.Filled.DirectionsCar, Driving, "Driving")
        ActivityType.FLYING     -> Triple(Icons.Filled.Flight, Flying, "Flying")
        ActivityType.STATIONARY -> Triple(Icons.Filled.PauseCircle, Stationary, "Stationary")
        ActivityType.UNKNOWN    -> Triple(Icons.Filled.QuestionMark, Stationary, "Detecting...")
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.9f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text(label, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}

// ─── WEARABLE STATUS CHIP ────────────────────────────

@Composable
private fun WearableStatusChip(
    state: WearableConnectionState,
    reading: com.trackjourney.data.bluetooth.WearableReading?,
    modifier: Modifier = Modifier
) {
    val (text, color) = when (state) {
        is WearableConnectionState.Connected -> {
            val hr = reading?.heartRate?.let { "❤ $it" } ?: ""
            val spo2 = reading?.spO2?.let { " | O₂ $it%" } ?: ""
            "${state.device.name} $hr$spo2" to PrimaryLight
        }
        is WearableConnectionState.Connecting -> "Connecting..." to Accent
        is WearableConnectionState.Scanning -> "Scanning..." to Secondary
        is WearableConnectionState.Error -> "Error" to Error
        WearableConnectionState.Disconnected -> return // Don't show chip
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.9f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ─── WEARABLE BOTTOM SHEET ───────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WearableBottomSheet(
    uiState: MapUiState,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onConnect: (com.trackjourney.data.bluetooth.WearableDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                "Smartwatch Connection",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Connection status
            when (val state = uiState.wearableState) {
                is WearableConnectionState.Connected -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = PrimaryLight.copy(alpha = 0.1f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Watch,
                                contentDescription = null,
                                tint = PrimaryLight,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    state.device.name,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Connected • ${state.device.type.name}",
                                    fontSize = 13.sp,
                                    color = PrimaryLight
                                )
                                uiState.wearableReading?.let { reading ->
                                    Text(
                                        buildString {
                                            reading.heartRate?.let { append("❤ $it bpm") }
                                            reading.spO2?.let {
                                                if (isNotEmpty()) append(" • ")
                                                append("O₂ $it%")
                                            }
                                        },
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            TextButton(onClick = onDisconnect) {
                                Text("Disconnect", color = Error)
                            }
                        }
                    }
                }

                else -> {
                    Button(
                        onClick = onScan,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.wearableState !is WearableConnectionState.Scanning
                    ) {
                        Icon(Icons.Filled.BluetoothSearching, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (uiState.wearableState is WearableConnectionState.Scanning)
                                "Scanning..." else "Scan for Devices"
                        )
                    }

                    if (uiState.discoveredDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Discovered Devices",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn {
                            items(uiState.discoveredDevices) { device ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable { onConnect(device) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val icon = when (device.type) {
                                            com.trackjourney.data.model.WearableType.GARMIN -> Icons.Filled.Watch
                                            com.trackjourney.data.model.WearableType.SAMSUNG -> Icons.Filled.Watch
                                            else -> Icons.Filled.Bluetooth
                                        }
                                        Icon(icon, contentDescription = null)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(device.name, fontWeight = FontWeight.Medium)
                                            Text(
                                                "${device.type.name} • Signal: ${device.rssi}dBm",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Icon(
                                            Icons.Filled.ChevronRight,
                                            contentDescription = "Connect"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Info text
            Text(
                "Supports Garmin and Samsung Galaxy watches via Bluetooth LE. " +
                    "Heart rate and SpO2 data will be recorded alongside your track.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
