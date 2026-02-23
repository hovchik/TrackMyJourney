package com.trackjourney.ui.screens.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackjourney.data.bluetooth.WearableConnectionState
import com.trackjourney.data.model.ActivityType
import com.trackjourney.data.model.TrackSession
import com.trackjourney.ui.components.OsmMapView
import com.trackjourney.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel(),
    onBackFromTrack: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    var showTrackNameDialog by remember { mutableStateOf(false) }
    var trackName by remember { mutableStateOf("") }
    var showTrackPicker by remember { mutableStateOf(false) }

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

    Column(modifier = Modifier.fillMaxSize()) {
        // Top app bar
        TopAppBar(
            title = {
                if (uiState.isViewingSavedTrack) {
                    Column {
                        Text(uiState.viewedTrackName, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            "${uiState.pointCount} points | ${String.format("%.2f", uiState.distanceKm)} km",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text("Map", fontWeight = FontWeight.Bold)
                }
            },
            navigationIcon = {
                if (uiState.isViewingSavedTrack) {
                    IconButton(onClick = {
                        viewModel.clearSavedTrack()
                        onBackFromTrack?.invoke()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                if (!uiState.isTracking && !uiState.isViewingSavedTrack && allTracks.isNotEmpty()) {
                    IconButton(onClick = { showTrackPicker = true }) {
                        Icon(Icons.Filled.Route, contentDescription = "View Tracks")
                    }
                }
            }
        )

        // Map area — takes remaining space
        Box(modifier = Modifier.weight(1f)) {
            // Map
            OsmMapView(
                modifier = Modifier.fillMaxSize(),
                trackPoints = uiState.trackPoints,
                currentLatitude = uiState.currentLatitude,
                currentLongitude = uiState.currentLongitude,
                centerOnUser = uiState.isTracking,
                showActivityColors = !uiState.isViewingSavedTrack,
                showSpeedColors = uiState.isViewingSavedTrack,
                showDirectionArrows = uiState.isViewingSavedTrack
            )

            // Top stats bar (shown during tracking)
            androidx.compose.animation.AnimatedVisibility(
                visible = uiState.isTracking,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                TrackingStatsBar(uiState)
            }

            // Right side chips — GPS + Wearable, only visible during tracking
            if (uiState.isTracking) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 60.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GpsSatelliteChip(
                        totalVisible = uiState.satelliteInfo.totalVisible,
                        usedInFix = uiState.satelliteInfo.usedInFix,
                        accuracy = uiState.gpsAccuracy
                    )
                    WearableStatusChip(
                        connectionState = uiState.wearableState,
                        heartRate = uiState.wearableReading?.heartRate,
                        batteryLevel = uiState.wearableReading?.batteryLevel
                    )
                }
            }

            // Activity badge at bottom center
            if (uiState.isTracking) {
                ActivityBadge(
                    activity = uiState.currentActivity,
                    speed = uiState.currentSpeed,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                )
            }

            // Speed legend when viewing saved track
            if (uiState.isViewingSavedTrack) {
                SpeedLegend(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 16.dp)
                )
            }

            // Control buttons — bottom end
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (uiState.isTracking) {
                    // Pause/Resume
                    SmallFloatingActionButton(
                        onClick = {
                            if (uiState.isPaused) viewModel.resumeTracking()
                            else viewModel.pauseTracking()
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            if (uiState.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (uiState.isPaused) "Resume" else "Pause",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Stop
                    FloatingActionButton(
                        onClick = { viewModel.stopTracking() },
                        containerColor = MaterialTheme.colorScheme.error,
                        shape = CircleShape,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Stop",
                            modifier = Modifier.size(22.dp),
                            tint = Color.White
                        )
                    }
                } else {
                    // Start
                    FloatingActionButton(
                        onClick = { showTrackNameDialog = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Start",
                            modifier = Modifier.size(24.dp),
                            tint = Color.White
                        )
                    }
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
                    viewModel.startTracking(trackName)
                    showTrackNameDialog = false
                    trackName = ""
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

    // Track completion illustration
    uiState.completedTrack?.let { completed ->
        TrackCompletionDialog(
            info = completed,
            onDismiss = { viewModel.dismissCompletedTrack() },
            onViewOnMap = {
                viewModel.dismissCompletedTrack()
                viewModel.loadSavedTrack(completed.trackId)
            }
        )
    }

    // Track picker bottom sheet dialog
    if (showTrackPicker) {
        TrackPickerDialog(
            tracks = allTracks,
            onSelect = { track ->
                showTrackPicker = false
                viewModel.loadSavedTrack(track.id)
            },
            onDismiss = { showTrackPicker = false }
        )
    }
}

// --- TRACKING STATS BAR ---

@Composable
private fun TrackingStatsBar(state: MapUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
            StatItem(
                value = "${state.satelliteInfo.usedInFix}/${state.satelliteInfo.totalVisible}",
                unit = "",
                label = "Satellites",
                valueColor = when {
                    state.satelliteInfo.usedInFix >= 8 -> PrimaryLight
                    state.satelliteInfo.usedInFix >= 4 -> Accent
                    else -> Error
                }
            )
            // Show heart rate in stats bar if wearable connected
            state.wearableReading?.heartRate?.let { hr ->
                StatItem(
                    value = hr.toString(),
                    unit = "bpm",
                    label = "Heart Rate",
                    valueColor = when {
                        hr < 60 -> Accent
                        hr <= 140 -> PrimaryLight
                        else -> Error
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

// --- ACTIVITY BADGE ---

@Composable
private fun ActivityBadge(activity: ActivityType, speed: Float, modifier: Modifier = Modifier) {
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
        modifier = modifier,
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

// --- SPEED LEGEND ---

@Composable
private fun SpeedLegend(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text("Speed", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(color = Color(0xFF4CAF50), shape = CircleShape, modifier = Modifier.size(8.dp)) {}
                Text("Slow", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(color = Color(0xFFFFC107), shape = CircleShape, modifier = Modifier.size(8.dp)) {}
                Text("Medium", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(color = Color(0xFFFF5722), shape = CircleShape, modifier = Modifier.size(8.dp)) {}
                Text("Fast", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// --- GPS SATELLITE CHIP ---

@Composable
private fun GpsSatelliteChip(
    totalVisible: Int,
    usedInFix: Int,
    accuracy: Float?,
    modifier: Modifier = Modifier
) {
    if (totalVisible == 0 && usedInFix == 0) return

    val signalColor = when {
        usedInFix >= 8 -> PrimaryLight
        usedInFix >= 4 -> Accent
        else -> Error
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = signalColor.copy(alpha = 0.9f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Filled.SatelliteAlt,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = buildString {
                    append("$usedInFix/$totalVisible")
                    accuracy?.let { append(" | ${String.format("%.0f", it)}m") }
                },
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// --- WEARABLE STATUS CHIP ---

@Composable
private fun WearableStatusChip(
    connectionState: WearableConnectionState,
    heartRate: Int?,
    batteryLevel: Int?,
    modifier: Modifier = Modifier
) {
    val (chipColor, text, icon) = when (connectionState) {
        is WearableConnectionState.Connected -> {
            val displayText = buildString {
                heartRate?.let { append("$it bpm") }
                batteryLevel?.let {
                    if (isNotEmpty()) append(" | ")
                    append("$it%")
                }
                if (isEmpty()) append(connectionState.device.name)
            }
            Triple(PrimaryLight, displayText, Icons.Filled.Watch)
        }
        is WearableConnectionState.Connecting -> Triple(Accent, "Connecting...", Icons.Filled.Watch)
        is WearableConnectionState.Scanning -> Triple(Accent, "Scanning...", Icons.Filled.BluetoothSearching)
        is WearableConnectionState.Error -> return // Don't show chip on error
        is WearableConnectionState.Disconnected -> return // Don't show chip when disconnected
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = chipColor.copy(alpha = 0.9f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = text,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// --- TRACK COMPLETION DIALOG (Horizon Landscape Card) ---

@Composable
private fun TrackCompletionDialog(
    info: CompletedTrackInfo,
    onDismiss: () -> Unit,
    onViewOnMap: () -> Unit
) {
    val durationMin = info.durationMs / 60_000
    val hours = durationMin / 60
    val mins = durationMin % 60
    val durationText = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

    val (accentColor, activityLabel, activityIcon) = activityDisplayInfo(info.activityType)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column {
                // Landscape illustration
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    HorizonLandscape(
                        activityType = info.activityType,
                        accentColor = accentColor,
                        modifier = Modifier.fillMaxSize()
                    )

                    // "Journey Complete" overlay
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            color = Color.White.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    activityIcon,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    activityLabel,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Journey Complete",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Track name
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        info.name.ifEmpty { "Unnamed Track" },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Place names
                    if (info.startPlace != null || info.endPlace != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            buildString {
                                info.startPlace?.let { append(it) }
                                if (info.startPlace != null && info.endPlace != null
                                    && info.startPlace != info.endPlace
                                ) {
                                    append("  \u2192  ")
                                    append(info.endPlace)
                                }
                            },
                            fontSize = 13.sp,
                            color = accentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Stats row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CompletionStat(
                            value = String.format(Locale.US, "%.2f", info.distanceKm),
                            unit = "km",
                            label = "Distance",
                            color = accentColor
                        )
                        CompletionStat(
                            value = durationText,
                            unit = "",
                            label = "Duration",
                            color = Secondary
                        )
                        CompletionStat(
                            value = String.format(Locale.US, "%.1f", info.avgSpeedKmh),
                            unit = "km/h",
                            label = "Avg Speed",
                            color = Walking
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CompletionStat(
                            value = String.format(Locale.US, "%.1f", info.maxSpeedKmh),
                            unit = "km/h",
                            label = "Max Speed",
                            color = Accent
                        )
                        CompletionStat(
                            value = String.format(Locale.US, "%.0f", info.caloriesBurned),
                            unit = "kcal",
                            label = "Calories",
                            color = Running
                        )
                        CompletionStat(
                            value = info.pointCount.toString(),
                            unit = "pts",
                            label = "GPS Points",
                            color = Primary
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Battery usage
                    if (info.batteryStart != null && info.batteryEnd != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.BatteryStd,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Battery: ${info.batteryStart}% → ${info.batteryEnd}% (${info.batteryStart - info.batteryEnd}% used)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Close")
                        }
                        Button(
                            onClick = onViewOnMap,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                        ) {
                            Icon(
                                Icons.Filled.Map,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("View on Map")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletionStat(
    value: String,
    unit: String,
    label: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            if (unit.isNotEmpty()) {
                Text(
                    " $unit",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- HORIZON LANDSCAPE ILLUSTRATION ---

@Composable
private fun HorizonLandscape(
    activityType: ActivityType,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val skyTop = when (activityType) {
        ActivityType.WALKING    -> Color(0xFF1A237E) // deep indigo
        ActivityType.RUNNING    -> Color(0xFFE65100) // deep orange
        ActivityType.CYCLING    -> Color(0xFF0D47A1) // deep blue
        ActivityType.DRIVING    -> Color(0xFF4A148C) // deep purple
        ActivityType.FLYING     -> Color(0xFF880E4F) // deep pink
        ActivityType.STATIONARY -> Color(0xFF37474F) // blue-grey
        ActivityType.UNKNOWN    -> Color(0xFF37474F)
    }
    val skyBottom = when (activityType) {
        ActivityType.WALKING    -> Color(0xFF66BB6A) // green horizon
        ActivityType.RUNNING    -> Color(0xFFFFCC80) // warm horizon
        ActivityType.CYCLING    -> Color(0xFF42A5F5) // blue horizon
        ActivityType.DRIVING    -> Color(0xFFCE93D8) // purple horizon
        ActivityType.FLYING     -> Color(0xFFF48FB1) // pink horizon
        ActivityType.STATIONARY -> Color(0xFF78909C) // grey horizon
        ActivityType.UNKNOWN    -> Color(0xFF78909C)
    }
    val silhouetteColor = accentColor.copy(alpha = 0.35f)
    val silhouetteDark = Color(0xFF1A1A2E)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Sky gradient
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(skyTop, skyBottom),
                startY = 0f,
                endY = h * 0.85f
            )
        )

        // Ground strip
        drawRect(
            color = silhouetteDark,
            topLeft = Offset(0f, h * 0.78f),
            size = androidx.compose.ui.geometry.Size(w, h * 0.22f)
        )

        // Draw silhouette based on activity type
        when (activityType) {
            ActivityType.WALKING, ActivityType.RUNNING -> {
                // Mountain silhouette
                val mountainPath = Path().apply {
                    moveTo(0f, h * 0.78f)
                    // Mountain range
                    lineTo(w * 0.08f, h * 0.55f)
                    lineTo(w * 0.15f, h * 0.42f)
                    lineTo(w * 0.22f, h * 0.52f)
                    lineTo(w * 0.30f, h * 0.28f) // tallest peak
                    lineTo(w * 0.38f, h * 0.48f)
                    lineTo(w * 0.45f, h * 0.38f)
                    lineTo(w * 0.52f, h * 0.55f)
                    lineTo(w * 0.58f, h * 0.45f)
                    lineTo(w * 0.65f, h * 0.35f)
                    lineTo(w * 0.72f, h * 0.50f)
                    lineTo(w * 0.80f, h * 0.40f)
                    lineTo(w * 0.88f, h * 0.58f)
                    lineTo(w * 0.95f, h * 0.50f)
                    lineTo(w, h * 0.60f)
                    lineTo(w, h * 0.78f)
                    close()
                }
                drawPath(mountainPath, color = silhouetteColor, style = Fill)
                drawPath(mountainPath, color = silhouetteDark.copy(alpha = 0.5f), style = Fill)
            }
            ActivityType.DRIVING, ActivityType.CYCLING -> {
                // City skyline silhouette
                val cityPath = Path().apply {
                    moveTo(0f, h * 0.78f)
                    // Buildings of various heights
                    lineTo(0f, h * 0.55f)
                    lineTo(w * 0.04f, h * 0.55f)
                    lineTo(w * 0.04f, h * 0.68f)
                    lineTo(w * 0.08f, h * 0.68f)
                    lineTo(w * 0.08f, h * 0.40f)
                    lineTo(w * 0.14f, h * 0.40f)
                    lineTo(w * 0.14f, h * 0.62f)
                    lineTo(w * 0.18f, h * 0.62f)
                    lineTo(w * 0.18f, h * 0.48f)
                    lineTo(w * 0.22f, h * 0.48f)
                    lineTo(w * 0.22f, h * 0.32f) // tall tower
                    lineTo(w * 0.26f, h * 0.32f)
                    lineTo(w * 0.26f, h * 0.55f)
                    lineTo(w * 0.30f, h * 0.55f)
                    lineTo(w * 0.30f, h * 0.45f)
                    lineTo(w * 0.36f, h * 0.45f)
                    lineTo(w * 0.36f, h * 0.65f)
                    lineTo(w * 0.40f, h * 0.65f)
                    lineTo(w * 0.40f, h * 0.38f)
                    lineTo(w * 0.46f, h * 0.38f)
                    lineTo(w * 0.46f, h * 0.58f)
                    lineTo(w * 0.50f, h * 0.58f)
                    lineTo(w * 0.50f, h * 0.42f)
                    lineTo(w * 0.56f, h * 0.42f)
                    lineTo(w * 0.56f, h * 0.28f) // tallest building
                    lineTo(w * 0.62f, h * 0.28f)
                    lineTo(w * 0.62f, h * 0.50f)
                    lineTo(w * 0.66f, h * 0.50f)
                    lineTo(w * 0.66f, h * 0.60f)
                    lineTo(w * 0.70f, h * 0.60f)
                    lineTo(w * 0.70f, h * 0.44f)
                    lineTo(w * 0.76f, h * 0.44f)
                    lineTo(w * 0.76f, h * 0.55f)
                    lineTo(w * 0.80f, h * 0.55f)
                    lineTo(w * 0.80f, h * 0.35f)
                    lineTo(w * 0.86f, h * 0.35f)
                    lineTo(w * 0.86f, h * 0.62f)
                    lineTo(w * 0.90f, h * 0.62f)
                    lineTo(w * 0.90f, h * 0.50f)
                    lineTo(w * 0.95f, h * 0.50f)
                    lineTo(w * 0.95f, h * 0.68f)
                    lineTo(w, h * 0.68f)
                    lineTo(w, h * 0.78f)
                    close()
                }
                drawPath(cityPath, color = silhouetteColor, style = Fill)
                drawPath(cityPath, color = silhouetteDark.copy(alpha = 0.6f), style = Fill)
            }
            ActivityType.FLYING -> {
                // Cloud layers silhouette
                val cloudPath = Path().apply {
                    moveTo(0f, h * 0.78f)
                    lineTo(0f, h * 0.65f)
                    // Wavy cloud layers
                    cubicTo(w * 0.1f, h * 0.55f, w * 0.2f, h * 0.65f, w * 0.3f, h * 0.58f)
                    cubicTo(w * 0.4f, h * 0.50f, w * 0.5f, h * 0.62f, w * 0.6f, h * 0.52f)
                    cubicTo(w * 0.7f, h * 0.45f, w * 0.8f, h * 0.58f, w * 0.9f, h * 0.50f)
                    cubicTo(w * 0.95f, h * 0.46f, w, h * 0.55f, w, h * 0.60f)
                    lineTo(w, h * 0.78f)
                    close()
                }
                drawPath(cloudPath, color = silhouetteColor, style = Fill)
                drawPath(cloudPath, color = Color.White.copy(alpha = 0.1f), style = Fill)
            }
            else -> {
                // Gentle rolling hills
                val hillPath = Path().apply {
                    moveTo(0f, h * 0.78f)
                    cubicTo(w * 0.15f, h * 0.55f, w * 0.35f, h * 0.65f, w * 0.5f, h * 0.58f)
                    cubicTo(w * 0.65f, h * 0.50f, w * 0.85f, h * 0.62f, w, h * 0.55f)
                    lineTo(w, h * 0.78f)
                    close()
                }
                drawPath(hillPath, color = silhouetteColor, style = Fill)
                drawPath(hillPath, color = silhouetteDark.copy(alpha = 0.4f), style = Fill)
            }
        }

        // Sun/moon circle near horizon
        val sunCenter = Offset(w * 0.75f, h * 0.50f)
        val sunRadius = w * 0.06f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.9f),
                    skyBottom.copy(alpha = 0.3f)
                ),
                center = sunCenter,
                radius = sunRadius * 2.5f
            ),
            center = sunCenter,
            radius = sunRadius * 2.5f
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            center = sunCenter,
            radius = sunRadius
        )
    }
}

private fun activityDisplayInfo(type: ActivityType): Triple<Color, String, ImageVector> = when (type) {
    ActivityType.WALKING    -> Triple(Walking, "Walking", Icons.Filled.DirectionsWalk)
    ActivityType.RUNNING    -> Triple(Running, "Running", Icons.Filled.DirectionsRun)
    ActivityType.CYCLING    -> Triple(Cycling, "Cycling", Icons.Filled.DirectionsBike)
    ActivityType.DRIVING    -> Triple(Driving, "Driving", Icons.Filled.DirectionsCar)
    ActivityType.FLYING     -> Triple(Flying, "Flying", Icons.Filled.Flight)
    ActivityType.STATIONARY -> Triple(Stationary, "Stationary", Icons.Filled.PauseCircle)
    ActivityType.UNKNOWN    -> Triple(Stationary, "Unknown", Icons.Filled.QuestionMark)
}

// --- TRACK PICKER DIALOG ---

@Composable
private fun TrackPickerDialog(
    tracks: List<TrackSession>,
    onSelect: (TrackSession) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Route,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Select Track",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                // Track list
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tracks.size) { index ->
                        val track = tracks[index]
                        val (accentColor, _, actIcon) = activityDisplayInfo(track.activityType)
                        val distKm = track.distanceMeters / 1000.0
                        val durationMs = (track.endTime ?: track.startTime) - track.startTime
                        val durationMin = durationMs / 60_000

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(track) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = accentColor.copy(alpha = 0.06f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Activity icon circle
                                Surface(
                                    shape = CircleShape,
                                    color = accentColor.copy(alpha = 0.15f),
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            actIcon,
                                            contentDescription = null,
                                            tint = accentColor,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Track info
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        track.name.ifEmpty { "Unnamed Track" },
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        dateFormatter.format(Date(track.startTime)),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Stats
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        String.format(Locale.US, "%.2f km", distKm),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = accentColor
                                    )
                                    Text(
                                        if (durationMin >= 60) "${durationMin / 60}h ${durationMin % 60}m"
                                        else "${durationMin}m",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
