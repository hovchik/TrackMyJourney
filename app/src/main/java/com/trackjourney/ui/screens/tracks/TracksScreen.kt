package com.trackjourney.ui.screens.tracks

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

// ─── Sort options ────────────────────────────────────

enum class TrackSortOption(val label: String) {
    DATE_DESC("Newest first"),
    DATE_ASC("Oldest first"),
    DISTANCE("Longest distance"),
    DURATION("Longest duration")
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
    var sortOption by remember { mutableStateOf(TrackSortOption.DATE_DESC) }
    var showSortMenu by remember { mutableStateOf(false) }

    val filteredTracks = remember(tracks, selectedFilter, sortOption) {
        val filtered = if (selectedFilter != null) {
            tracks.filter { it.activityType == selectedFilter }
        } else tracks

        when (sortOption) {
            TrackSortOption.DATE_DESC -> filtered.sortedByDescending { it.startTime }
            TrackSortOption.DATE_ASC -> filtered.sortedBy { it.startTime }
            TrackSortOption.DISTANCE -> filtered.sortedByDescending { it.distanceMeters }
            TrackSortOption.DURATION -> filtered.sortedByDescending {
                (it.endTime ?: System.currentTimeMillis()) - it.startTime
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with sort action
        TopAppBar(
            title = {
                Column {
                    Text("My Tracks", fontWeight = FontWeight.Bold)
                    if (tracks.isNotEmpty()) {
                        Text(
                            "${tracks.size} track${if (tracks.size != 1) "s" else ""} recorded",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            },
            actions = {
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.Filled.Sort, contentDescription = "Sort tracks")
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        TrackSortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label,
                                        fontWeight = if (sortOption == option) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sortOption == option) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                leadingIcon = {
                                    if (sortOption == option) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                onClick = {
                                    sortOption = option
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }
        )

        // Activity filter chips — horizontally scrollable
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedFilter == null,
                    onClick = { selectedFilter = null },
                    label = { Text("All") },
                    leadingIcon = if (selectedFilter == null) {
                        {
                            Icon(
                                Icons.Filled.Done,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else null
                )
            }
            items(ActivityType.entries.filter { it != ActivityType.UNKNOWN }) { type ->
                FilterChip(
                    selected = selectedFilter == type,
                    onClick = {
                        selectedFilter = if (selectedFilter == type) null else type
                    },
                    label = {
                        Text(type.name.lowercase().replaceFirstChar { it.uppercase() })
                    },
                    leadingIcon = {
                        Icon(
                            activityIcon(type),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (selectedFilter == type) MaterialTheme.colorScheme.onSecondaryContainer
                            else activityColor(type)
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (filteredTracks.isEmpty()) {
            EmptyTracksState(
                hasFilter = selectedFilter != null,
                filterName = selectedFilter?.name?.lowercase()
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = 8.dp, bottom = 88.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Summary banner
                item(key = "summary") {
                    SummaryBanner(filteredTracks)
                }

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

// ─── SUMMARY BANNER ──────────────────────────────────

@Composable
private fun SummaryBanner(tracks: List<TrackSession>) {
    val totalDistanceKm = tracks.sumOf { it.distanceMeters } / 1000.0
    val totalDurationMs = tracks.sumOf {
        (it.endTime ?: System.currentTimeMillis()) - it.startTime
    }
    val totalHours = totalDurationMs / 3_600_000.0

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryItem(
                icon = Icons.Filled.Route,
                value = tracks.size.toString(),
                label = "Trips",
                color = Accent
            )
            SummaryItem(
                icon = Icons.Filled.Straighten,
                value = when {
                    totalDistanceKm < 1 -> String.format(Locale.US, "%.0f m", totalDistanceKm * 1000)
                    totalDistanceKm < 100 -> String.format(Locale.US, "%.1f km", totalDistanceKm)
                    else -> String.format(Locale.US, "%.0f km", totalDistanceKm)
                },
                label = "Distance",
                color = Primary
            )
            SummaryItem(
                icon = Icons.Filled.Schedule,
                value = when {
                    totalHours >= 1 -> String.format(Locale.US, "%.1fh", totalHours)
                    else -> String.format(Locale.US, "%.0fm", totalDurationMs / 60_000.0)
                },
                label = "Time",
                color = Secondary
            )
        }
    }
}

@Composable
private fun SummaryItem(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = color
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = color
        )
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── EMPTY STATE ─────────────────────────────────────

@Composable
private fun EmptyTracksState(hasFilter: Boolean, filterName: String?) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (hasFilter) Icons.Filled.FilterAlt else Icons.Filled.Route,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                if (hasFilter) "No $filterName tracks" else "No tracks yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (hasFilter) "Try a different filter or start a new journey"
                else "Start tracking from the Map tab to record your journeys",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
                modifier = Modifier.widthIn(max = 260.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─── TRACK CARD ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
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

    val sdf = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val durationMs = (track.endTime ?: System.currentTimeMillis()) - track.startTime
    val durationMin = durationMs / 60_000
    val hours = durationMin / 60
    val mins = durationMin % 60

    val color = activityColor(track.activityType)
    val icon = activityIcon(track.activityType)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            // Color accent strip at top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(color.copy(alpha = 0.6f))
            )

            Column(modifier = Modifier.padding(16.dp)) {
                // Header row: icon, name, date, live badge, expand
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = color.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            track.name.ifEmpty { "Unnamed Track" },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                sdf.format(Date(track.startTime)),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                " at ",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                timeFmt.format(Date(track.startTime)),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val relativeDay = relativeDay(track.startTime)
                            if (relativeDay != null) {
                                Text(
                                    " \u00B7 $relativeDay",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                )
                            }
                        }
                        // Place names
                        if (track.startPlaceName != null || track.endPlaceName != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Place,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    buildString {
                                        track.startPlaceName?.let { append(it) }
                                        if (track.startPlaceName != null && track.endPlaceName != null
                                            && track.startPlaceName != track.endPlaceName) {
                                            append("  \u2192  ")
                                            append(track.endPlaceName)
                                        }
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (track.isActive) {
                        Badge(containerColor = PrimaryLight) {
                            Text("LIVE", color = Color.White, fontSize = 10.sp)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Stats grid — 2x2 for core metrics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatChip(
                        icon = Icons.Filled.Straighten,
                        value = String.format(Locale.US, "%.2f km", track.distanceMeters / 1000),
                        label = "Distance",
                        color = Primary
                    )
                    StatChip(
                        icon = Icons.Filled.Schedule,
                        value = if (hours > 0) "${hours}h ${mins}m" else "${mins}m",
                        label = "Duration",
                        color = Secondary
                    )
                    StatChip(
                        icon = Icons.Filled.Speed,
                        value = String.format(Locale.US, "%.1f", track.avgSpeedKmh),
                        label = "km/h avg",
                        color = Walking
                    )
                    track.avgHeartRate?.let {
                        StatChip(
                            icon = Icons.Filled.Favorite,
                            value = "$it",
                            label = "bpm",
                            color = Running
                        )
                    } ?: StatChip(
                        icon = Icons.Filled.FlashOn,
                        value = String.format(Locale.US, "%.1f", track.maxSpeedKmh),
                        label = "km/h max",
                        color = Accent
                    )
                }

                // Expanded section
                if (expanded) {
                    Spacer(modifier = Modifier.height(14.dp))
                    @Suppress("DEPRECATION")
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Extra stats
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DetailItem(
                            modifier = Modifier.weight(1f),
                            label = "Max Speed",
                            value = String.format(Locale.US, "%.1f km/h", track.maxSpeedKmh)
                        )
                        track.avgHeartRate?.let {
                            DetailItem(
                                modifier = Modifier.weight(1f),
                                label = "Avg Heart Rate",
                                value = "$it bpm"
                            )
                        }
                        track.avgSpO2?.let {
                            DetailItem(
                                modifier = Modifier.weight(1f),
                                label = "Avg SpO2",
                                value = "$it%"
                            )
                        }
                    }

                    // AI Summary
                    track.aiSummary?.let { summary ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Accent
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    summary,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onExport,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Filled.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = onReanalyze,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Re-analyze", fontSize = 13.sp)
                        }
                        FilledTonalButton(
                            onClick = { showDeleteDialog = true },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Error.copy(alpha = 0.1f),
                                contentColor = Error
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("Delete Track?") },
            text = {
                Text("\"${track.name.ifEmpty { "Unnamed Track" }}\" and all its data will be permanently deleted.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Error)
                ) {
                    Text("Delete")
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

// ─── STAT CHIP ───────────────────────────────────────

@Composable
private fun StatChip(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── DETAIL ITEM (expanded) ──────────────────────────

@Composable
private fun DetailItem(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Text(
                label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── HELPERS ─────────────────────────────────────────

private fun activityColor(type: ActivityType): Color = when (type) {
    ActivityType.WALKING    -> Walking
    ActivityType.RUNNING    -> Running
    ActivityType.CYCLING    -> Cycling
    ActivityType.DRIVING    -> Driving
    ActivityType.FLYING     -> Flying
    ActivityType.STATIONARY -> Stationary
    ActivityType.UNKNOWN    -> Color.Gray
}

private fun activityIcon(type: ActivityType): ImageVector = when (type) {
    ActivityType.WALKING    -> Icons.Filled.DirectionsWalk
    ActivityType.RUNNING    -> Icons.Filled.DirectionsRun
    ActivityType.CYCLING    -> Icons.Filled.DirectionsBike
    ActivityType.DRIVING    -> Icons.Filled.DirectionsCar
    ActivityType.FLYING     -> Icons.Filled.Flight
    ActivityType.STATIONARY -> Icons.Filled.PauseCircle
    ActivityType.UNKNOWN    -> Icons.Filled.QuestionMark
}

private fun relativeDay(timestamp: Long): String? {
    val cal = Calendar.getInstance()
    val todayStart = cal.apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    return when {
        timestamp >= todayStart -> "Today"
        timestamp >= todayStart - 86_400_000 -> "Yesterday"
        timestamp >= todayStart - 2 * 86_400_000 -> "2 days ago"
        timestamp >= todayStart - 7 * 86_400_000 -> {
            val days = ((todayStart - timestamp) / 86_400_000 + 1).toInt()
            "$days days ago"
        }
        else -> null
    }
}
