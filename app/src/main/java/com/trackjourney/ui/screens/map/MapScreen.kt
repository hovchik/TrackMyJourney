package com.trackjourney.ui.screens.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

private val OverlayDark = Color(0xFF1A1A2E)
private val OverlayCard = Color(0xFF1E1E30)

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
    var trackDropdownExpanded by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()) }

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

    // Full-screen map with floating overlays
    Box(modifier = Modifier.fillMaxSize()) {

        // ── Map (fills entire screen) ───────────────────────
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

        // ── Top overlay: track selector OR tracking info ────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp)
        ) {
            if (uiState.isTracking) {
                // Live tracking stats panel
                AnimatedVisibility(
                    visible = true,
                    enter = slideInVertically() + fadeIn(),
                    exit = slideOutVertically() + fadeOut()
                ) {
                    TrackingStatsBar(uiState)
                }
            } else if (allTracks.isNotEmpty()) {
                // Floating track selector
                FloatingTrackSelector(
                    tracks = allTracks,
                    isViewingSavedTrack = uiState.isViewingSavedTrack,
                    viewedTrackName = uiState.viewedTrackName,
                    expanded = trackDropdownExpanded,
                    onExpandedChange = { trackDropdownExpanded = it },
                    dateFormatter = dateFormatter,
                    onSelect = { track ->
                        trackDropdownExpanded = false
                        viewModel.loadSavedTrack(track.id)
                    },
                    onClear = {
                        viewModel.clearSavedTrack()
                        onBackFromTrack?.invoke()
                    }
                )
            }
        }

        // ── Right-side chips (GPS + Wearable) ───────────────
        AnimatedVisibility(
            visible = uiState.isTracking,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 80.dp, end = 12.dp),
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

        // ── Track info pill (when viewing saved track) ──────
        AnimatedVisibility(
            visible = uiState.isViewingSavedTrack,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 72.dp),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val (accentColor, actLabel, actIcon) = activityDisplayInfo(uiState.currentActivity)
            Surface(
                color = OverlayDark.copy(alpha = 0.85f),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(actIcon, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp))
                    Text(
                        "${uiState.pointCount} pts",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Text("\u2022", color = Color.White.copy(alpha = 0.3f), fontSize = 12.sp)
                    Text(
                        String.format("%.2f km", uiState.distanceKm),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ── Activity badge (bottom center, during tracking) ─
        AnimatedVisibility(
            visible = uiState.isTracking,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            ActivityBadge(activity = uiState.currentActivity, speed = uiState.currentSpeed)
        }

        // ── Speed legend (bottom start, saved track) ────────
        AnimatedVisibility(
            visible = uiState.isViewingSavedTrack,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 24.dp),
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut()
        ) {
            SpeedLegend()
        }

        // ── Control buttons (bottom end) ────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (uiState.isTracking) {
                // Pause / Resume pill
                Surface(
                    onClick = {
                        if (uiState.isPaused) viewModel.resumeTracking()
                        else viewModel.pauseTracking()
                    },
                    color = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 6.dp,
                    modifier = Modifier.height(40.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            if (uiState.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                            tint = OverlayDark,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            if (uiState.isPaused) "Resume" else "Pause",
                            color = OverlayDark,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Stop button
                FloatingActionButton(
                    onClick = { viewModel.stopTracking() },
                    containerColor = Error,
                    shape = CircleShape,
                    modifier = Modifier.size(60.dp),
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 8.dp,
                        pressedElevation = 12.dp
                    )
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier.size(28.dp),
                        tint = Color.White
                    )
                }
            } else if (!uiState.isViewingSavedTrack) {
                // Start tracking FAB
                LargeFloatingActionButton(
                    onClick = { showTrackNameDialog = true },
                    containerColor = Primary,
                    shape = RoundedCornerShape(20.dp),
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 8.dp,
                        pressedElevation = 12.dp
                    )
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Start Tracking",
                        modifier = Modifier.size(32.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }

    // ── Track name dialog ───────────────────────────────────
    if (showTrackNameDialog) {
        AlertDialog(
            onDismissRequest = { showTrackNameDialog = false },
            title = { Text("Start New Track", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = trackName,
                    onValueChange = { trackName = it },
                    label = { Text("Track name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        permissionLauncher.launch(requiredPermissions)
                        viewModel.startTracking(trackName)
                        showTrackNameDialog = false
                        trackName = ""
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
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
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    // ── Track completion dialog ─────────────────────────────
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
}

// ═══════════════════════════════════════════════════════════
//  FLOATING TRACK SELECTOR
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FloatingTrackSelector(
    tracks: List<TrackSession>,
    isViewingSavedTrack: Boolean,
    viewedTrackName: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    dateFormatter: SimpleDateFormat,
    onSelect: (TrackSession) -> Unit,
    onClear: () -> Unit
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Search-bar-style selector
        Surface(
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(16.dp)),
            color = Color.White,
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isViewingSavedTrack) {
                    val track = tracks.find { it.name == viewedTrackName || (it.name.isEmpty() && viewedTrackName == "Unnamed Track") }
                    val (accentColor, _, actIcon) = activityDisplayInfo(track?.activityType ?: ActivityType.UNKNOWN)
                    Surface(
                        color = accentColor.copy(alpha = 0.12f),
                        shape = CircleShape,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(actIcon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        viewedTrackName,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        color = OverlayDark
                    )
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Clear",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Select a track to view",
                        color = Color.Gray,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Filled.UnfoldMore,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Dropdown menu
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            tracks.forEach { track ->
                val (accentColor, _, actIcon) = activityDisplayInfo(track.activityType)
                val distKm = track.distanceMeters / 1000.0
                val durationMs = (track.endTime ?: track.startTime) - track.startTime
                val durationMin = durationMs / 60_000
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = accentColor.copy(alpha = 0.12f),
                                shape = CircleShape,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(actIcon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    track.name.ifEmpty { "Unnamed Track" },
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    dateFormatter.format(Date(track.startTime)),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    String.format(Locale.US, "%.1f km", distKm),
                                    fontSize = 12.sp,
                                    color = accentColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    if (durationMin >= 60) "${durationMin / 60}h ${durationMin % 60}m"
                                    else "${durationMin}m",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = { onSelect(track) },
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  TRACKING STATS BAR
// ═══════════════════════════════════════════════════════════

@Composable
private fun TrackingStatsBar(state: MapUiState) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = OverlayDark.copy(alpha = 0.88f),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(
                value = String.format("%.2f", state.distanceKm),
                unit = "km",
                label = "Distance",
                accentColor = PrimaryLight
            )
            StatDivider()
            StatItem(
                value = String.format("%.1f", state.currentSpeed),
                unit = "km/h",
                label = "Speed",
                accentColor = Accent
            )
            StatDivider()
            StatItem(
                value = state.pointCount.toString(),
                unit = "pts",
                label = "Points",
                accentColor = Secondary
            )
            StatDivider()
            StatItem(
                value = "${state.satelliteInfo.usedInFix}/${state.satelliteInfo.totalVisible}",
                unit = "",
                label = "Sats",
                accentColor = when {
                    state.satelliteInfo.usedInFix >= 8 -> PrimaryLight
                    state.satelliteInfo.usedInFix >= 4 -> Accent
                    else -> Error
                }
            )
            state.wearableReading?.heartRate?.let { hr ->
                StatDivider()
                StatItem(
                    value = hr.toString(),
                    unit = "bpm",
                    label = "Heart",
                    accentColor = when {
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
    accentColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = " $unit",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = accentColor.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(Color.White.copy(alpha = 0.12f))
    )
}

// ═══════════════════════════════════════════════════════════
//  ACTIVITY BADGE
// ═══════════════════════════════════════════════════════════

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

    Surface(
        modifier = modifier,
        color = color,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (speed > 0.5f) {
                Text(
                    "\u2022",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
                Text(
                    String.format("%.1f km/h", speed),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  SPEED LEGEND (gradient bar)
// ═══════════════════════════════════════════════════════════

@Composable
private fun SpeedLegend(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = OverlayDark.copy(alpha = 0.85f),
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Speed",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.7f),
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            // Gradient bar
            Canvas(
                modifier = Modifier
                    .width(14.dp)
                    .height(80.dp)
                    .clip(RoundedCornerShape(7.dp))
            ) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFFF5722),  // Fast — red-orange
                            Color(0xFFFFC107),  // Medium — amber
                            Color(0xFF4CAF50)   // Slow — green
                        )
                    ),
                    cornerRadius = CornerRadius(7.dp.toPx())
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Slow", fontSize = 9.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  GPS SATELLITE CHIP
// ═══════════════════════════════════════════════════════════

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

    Surface(
        modifier = modifier,
        color = OverlayDark.copy(alpha = 0.82f),
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(signalColor, CircleShape)
            )
            Icon(
                Icons.Filled.SatelliteAlt,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = buildString {
                    append("$usedInFix/$totalVisible")
                    accuracy?.let { append("  ${String.format("%.0f", it)}m") }
                },
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  WEARABLE STATUS CHIP
// ═══════════════════════════════════════════════════════════

@Composable
private fun WearableStatusChip(
    connectionState: WearableConnectionState,
    heartRate: Int?,
    batteryLevel: Int?,
    modifier: Modifier = Modifier
) {
    val (indicatorColor, text, icon) = when (connectionState) {
        is WearableConnectionState.Connected -> {
            val displayText = buildString {
                heartRate?.let { append("$it bpm") }
                batteryLevel?.let {
                    if (isNotEmpty()) append("  $it%")
                    else append("$it%")
                }
                if (isEmpty()) append(connectionState.device.name)
            }
            Triple(PrimaryLight, displayText, Icons.Filled.Watch)
        }
        is WearableConnectionState.Connecting -> Triple(Accent, "Connecting...", Icons.Filled.Watch)
        is WearableConnectionState.Scanning -> Triple(Accent, "Scanning...", Icons.Filled.BluetoothSearching)
        is WearableConnectionState.Error -> return
        is WearableConnectionState.Disconnected -> return
    }

    Surface(
        modifier = modifier,
        color = OverlayDark.copy(alpha = 0.82f),
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(indicatorColor, CircleShape)
            )
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
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

// ═══════════════════════════════════════════════════════════
//  TRACK COMPLETION DIALOG
// ═══════════════════════════════════════════════════════════

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
            shape = RoundedCornerShape(28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
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
                            color = Color.White.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(activityIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Text(activityLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Journey Complete",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        info.name.ifEmpty { "Unnamed Track" },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

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

                    Spacer(modifier = Modifier.height(20.dp))

                    // Stats grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CompletionStat(String.format(Locale.US, "%.2f", info.distanceKm), "km", "Distance", accentColor)
                        CompletionStat(durationText, "", "Duration", Secondary)
                        CompletionStat(String.format(Locale.US, "%.1f", info.avgSpeedKmh), "km/h", "Avg Speed", Walking)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CompletionStat(String.format(Locale.US, "%.1f", info.maxSpeedKmh), "km/h", "Max Speed", Accent)
                        CompletionStat(String.format(Locale.US, "%.0f", info.caloriesBurned), "kcal", "Calories", Running)
                        CompletionStat(info.pointCount.toString(), "pts", "GPS Points", Primary)
                    }

                    // Battery
                    if (info.batteryStart != null && info.batteryEnd != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Filled.BatteryStd,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${info.batteryStart}% \u2192 ${info.batteryEnd}%  (${info.batteryStart - info.batteryEnd}% used)",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

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
                            Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
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
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
            if (unit.isNotEmpty()) {
                Text(" $unit", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ═══════════════════════════════════════════════════════════
//  HORIZON LANDSCAPE ILLUSTRATION
// ═══════════════════════════════════════════════════════════

@Composable
private fun HorizonLandscape(
    activityType: ActivityType,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val skyTop = when (activityType) {
        ActivityType.WALKING    -> Color(0xFF1A237E)
        ActivityType.RUNNING    -> Color(0xFFE65100)
        ActivityType.CYCLING    -> Color(0xFF0D47A1)
        ActivityType.DRIVING    -> Color(0xFF4A148C)
        ActivityType.FLYING     -> Color(0xFF880E4F)
        ActivityType.STATIONARY -> Color(0xFF37474F)
        ActivityType.UNKNOWN    -> Color(0xFF37474F)
    }
    val skyBottom = when (activityType) {
        ActivityType.WALKING    -> Color(0xFF66BB6A)
        ActivityType.RUNNING    -> Color(0xFFFFCC80)
        ActivityType.CYCLING    -> Color(0xFF42A5F5)
        ActivityType.DRIVING    -> Color(0xFFCE93D8)
        ActivityType.FLYING     -> Color(0xFFF48FB1)
        ActivityType.STATIONARY -> Color(0xFF78909C)
        ActivityType.UNKNOWN    -> Color(0xFF78909C)
    }
    val silhouetteColor = accentColor.copy(alpha = 0.35f)
    val silhouetteDark = Color(0xFF1A1A2E)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(skyTop, skyBottom),
                startY = 0f,
                endY = h * 0.85f
            )
        )

        drawRect(
            color = silhouetteDark,
            topLeft = Offset(0f, h * 0.78f),
            size = Size(w, h * 0.22f)
        )

        when (activityType) {
            ActivityType.WALKING, ActivityType.RUNNING -> {
                val mountainPath = Path().apply {
                    moveTo(0f, h * 0.78f)
                    lineTo(w * 0.08f, h * 0.55f)
                    lineTo(w * 0.15f, h * 0.42f)
                    lineTo(w * 0.22f, h * 0.52f)
                    lineTo(w * 0.30f, h * 0.28f)
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
                val cityPath = Path().apply {
                    moveTo(0f, h * 0.78f)
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
                    lineTo(w * 0.22f, h * 0.32f)
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
                    lineTo(w * 0.56f, h * 0.28f)
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
                val cloudPath = Path().apply {
                    moveTo(0f, h * 0.78f)
                    lineTo(0f, h * 0.65f)
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

        val sunCenter = Offset(w * 0.75f, h * 0.50f)
        val sunRadius = w * 0.06f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.9f), skyBottom.copy(alpha = 0.3f)),
                center = sunCenter,
                radius = sunRadius * 2.5f
            ),
            center = sunCenter,
            radius = sunRadius * 2.5f
        )
        drawCircle(color = Color.White.copy(alpha = 0.85f), center = sunCenter, radius = sunRadius)
    }
}

// ═══════════════════════════════════════════════════════════
//  HELPERS
// ═══════════════════════════════════════════════════════════

private fun activityDisplayInfo(type: ActivityType): Triple<Color, String, ImageVector> = when (type) {
    ActivityType.WALKING    -> Triple(Walking, "Walking", Icons.Filled.DirectionsWalk)
    ActivityType.RUNNING    -> Triple(Running, "Running", Icons.Filled.DirectionsRun)
    ActivityType.CYCLING    -> Triple(Cycling, "Cycling", Icons.Filled.DirectionsBike)
    ActivityType.DRIVING    -> Triple(Driving, "Driving", Icons.Filled.DirectionsCar)
    ActivityType.FLYING     -> Triple(Flying, "Flying", Icons.Filled.Flight)
    ActivityType.STATIONARY -> Triple(Stationary, "Stationary", Icons.Filled.PauseCircle)
    ActivityType.UNKNOWN    -> Triple(Stationary, "Unknown", Icons.Filled.QuestionMark)
}
