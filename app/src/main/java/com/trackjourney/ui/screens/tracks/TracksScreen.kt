package com.trackjourney.ui.screens.tracks

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.model.*
import com.trackjourney.data.repository.TrackRepository
import com.trackjourney.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class TracksViewModel @Inject constructor(
    private val repository: TrackRepository
) : ViewModel() {

    val tracks: StateFlow<List<TrackSession>> = repository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteTrack(trackId: String) {
        viewModelScope.launch { repository.deleteTrack(trackId) }
    }

    fun exportTrack(trackId: String) {
        viewModelScope.launch { repository.exportTrackToJson(trackId) }
    }

    fun reanalyzeTrack(trackId: String) {
        viewModelScope.launch { repository.analyzeTrack(trackId) }
    }
}

// ─── TRACKS LIST SCREEN ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksScreen(
    viewModel: TracksViewModel = hiltViewModel(),
    onTrackClick: (String) -> Unit = {}
) {
    val tracks by viewModel.tracks.collectAsState()
    var selectedFilter by remember { mutableStateOf<ActivityType?>(null) }

    val filteredTracks = if (selectedFilter != null) {
        tracks.filter { it.activityType == selectedFilter }
    } else tracks

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        TopAppBar(
            title = { Text("My Tracks", fontWeight = FontWeight.Bold) },
            actions = {
                Text(
                    "${tracks.size} tracks",
                    modifier = Modifier.padding(end = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        )

        // Activity filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { selectedFilter = null },
                label = { Text("All") }
            )
            ActivityType.entries.filter { it != ActivityType.UNKNOWN }.forEach { type ->
                FilterChip(
                    selected = selectedFilter == type,
                    onClick = { selectedFilter = if (selectedFilter == type) null else type },
                    label = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }

        if (filteredTracks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Route,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No tracks yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Start tracking from the Map tab",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredTracks, key = { it.id }) { track ->
                    TrackCard(
                        track = track,
                        onClick = { onTrackClick(track.id) },
                        onExport = { viewModel.exportTrack(track.id) },
                        onDelete = { viewModel.deleteTrack(track.id) },
                        onReanalyze = { viewModel.reanalyzeTrack(track.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackCard(
    track: TrackSession,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onReanalyze: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val sdf = remember { SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()) }
    val durationMs = (track.endTime ?: System.currentTimeMillis()) - track.startTime
    val durationMin = durationMs / 60_000
    val hours = durationMin / 60
    val mins = durationMin % 60

    val activityColor = when (track.activityType) {
        ActivityType.WALKING    -> Walking
        ActivityType.RUNNING    -> Running
        ActivityType.CYCLING    -> Cycling
        ActivityType.DRIVING    -> Driving
        ActivityType.FLYING     -> Flying
        ActivityType.STATIONARY -> Stationary
        ActivityType.UNKNOWN    -> Color.Gray
    }

    val activityIcon = when (track.activityType) {
        ActivityType.WALKING    -> Icons.Filled.DirectionsWalk
        ActivityType.RUNNING    -> Icons.Filled.DirectionsRun
        ActivityType.CYCLING    -> Icons.Filled.DirectionsBike
        ActivityType.DRIVING    -> Icons.Filled.DirectionsCar
        ActivityType.FLYING     -> Icons.Filled.Flight
        ActivityType.STATIONARY -> Icons.Filled.PauseCircle
        ActivityType.UNKNOWN    -> Icons.Filled.QuestionMark
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Activity icon with colored background
                Surface(
                    color = activityColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            activityIcon,
                            contentDescription = null,
                            tint = activityColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        track.name.ifEmpty { "Unnamed Track" },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        sdf.format(Date(track.startTime)),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (track.isActive) {
                    Badge(containerColor = PrimaryLight) {
                        Text("LIVE", color = Color.White, fontSize = 10.sp)
                    }
                }

                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "More"
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TrackStat(
                    label = "Distance",
                    value = String.format(Locale.US, "%.2f km", track.distanceMeters / 1000)
                )
                TrackStat(
                    label = "Duration",
                    value = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                )
                TrackStat(
                    label = "Avg Speed",
                    value = String.format(Locale.US, "%.1f km/h", track.avgSpeedKmh)
                )
                track.avgHeartRate?.let {
                    TrackStat(label = "Avg HR", value = "$it bpm")
                }
            }

            // Expanded section
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                @Suppress("DEPRECATION")
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                // AI Summary
                track.aiSummary?.let { summary ->
                    Text(
                        summary,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onExport,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export JSON", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = onReanalyze,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Re-analyze", fontSize = 13.sp)
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Error)
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Track?") },
            text = { Text("This will permanently delete this track and all its data.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("Delete", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TrackStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
