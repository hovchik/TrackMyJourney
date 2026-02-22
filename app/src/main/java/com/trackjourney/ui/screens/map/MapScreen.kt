package com.trackjourney.ui.screens.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackjourney.data.model.ActivityType
import com.trackjourney.ui.components.OsmMapView
import com.trackjourney.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
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

    Column(modifier = Modifier.fillMaxSize()) {
        // Top app bar
        TopAppBar(
            title = { Text("Map", fontWeight = FontWeight.Bold) }
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
                showActivityColors = true
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

            // GPS Satellite chip — top end, only visible during tracking
            if (uiState.isTracking) {
                GpsSatelliteChip(
                    totalVisible = uiState.satelliteInfo.totalVisible,
                    usedInFix = uiState.satelliteInfo.usedInFix,
                    accuracy = uiState.gpsAccuracy,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 60.dp, end = 16.dp)
                )
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
            state.gpsAccuracy?.let { acc ->
                StatItem(
                    value = String.format("%.1f", acc),
                    unit = "m",
                    label = "Accuracy",
                    valueColor = when {
                        acc <= 5f -> PrimaryLight
                        acc <= 15f -> Accent
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
