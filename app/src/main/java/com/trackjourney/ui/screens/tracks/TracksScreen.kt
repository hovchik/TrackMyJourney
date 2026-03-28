package com.trackjourney.ui.screens.tracks

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackjourney.data.model.*
import com.trackjourney.data.repository.TrackRepository
import com.trackjourney.ui.components.ActivityTimelineGraph
import com.trackjourney.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class TracksViewModel @Inject constructor(
    private val repository: TrackRepository
) : ViewModel() {

    val tracks: StateFlow<List<TrackSession>> = repository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tracksWithPoints: StateFlow<List<TrackWithPoints>> = repository.getAllTracksWithPoints()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<TrackingSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrackingSettings())

    private val _exportedFile = MutableStateFlow<java.io.File?>(null)
    val exportedFile: StateFlow<java.io.File?> = _exportedFile.asStateFlow()

    private val _exportError = MutableStateFlow<String?>(null)
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    private val _importResult = MutableStateFlow<TrackRepository.ImportResult?>(null)
    val importResult: StateFlow<TrackRepository.ImportResult?> = _importResult.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun importTrack(uri: Uri) {
        viewModelScope.launch {
            try {
                val result = repository.importTrack(uri)
                _importResult.value = result
            } catch (e: Exception) {
                _importError.value = e.message ?: "Import failed"
            }
        }
    }

    fun clearImportResult() {
        _importResult.value = null
    }

    fun clearImportError() {
        _importError.value = null
    }

    fun deleteTrack(trackId: String) {
        viewModelScope.launch { repository.deleteTrack(trackId) }
    }

    fun exportTrack(trackId: String, format: ExportFormat) {
        viewModelScope.launch {
            val file = repository.exportTrack(trackId, format)
            if (file != null) {
                _exportedFile.value = file
            } else {
                _exportError.value = "Export failed"
            }
        }
    }

    fun clearExportedFile() {
        _exportedFile.value = null
    }

    fun clearExportError() {
        _exportError.value = null
    }

    fun reanalyzeTrack(trackId: String) {
        viewModelScope.launch { repository.analyzeTrack(trackId) }
    }

    private val _insightAnalysis = MutableStateFlow<AiAnalysis?>(null)
    val insightAnalysis: StateFlow<AiAnalysis?> = _insightAnalysis.asStateFlow()

    private val _insightLoading = MutableStateFlow(false)
    val insightLoading: StateFlow<Boolean> = _insightLoading.asStateFlow()

    fun loadAnalysisForTrack(trackId: String) {
        viewModelScope.launch {
            _insightLoading.value = true
            _insightAnalysis.value = null
            // Observe the latest analysis; if none exists, trigger analysis first
            repository.getAnalysisForTrack(trackId).first().let { existing ->
                if (existing != null) {
                    _insightAnalysis.value = existing
                    _insightLoading.value = false
                } else {
                    // Trigger analysis, then wait for result
                    repository.analyzeTrack(trackId)
                    repository.getAnalysisForTrack(trackId).first()?.let {
                        _insightAnalysis.value = it
                    }
                    _insightLoading.value = false
                }
            }
        }
    }

    fun clearInsight() {
        _insightAnalysis.value = null
        _insightLoading.value = false
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
    onTrackClick: (String) -> Unit = {},
    isTracking: Boolean = false
) {
    val context = LocalContext.current
    val currentSettings by viewModel.settings.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val tracksWithPoints by viewModel.tracksWithPoints.collectAsState()
    val exportedFile by viewModel.exportedFile.collectAsState()
    val exportError by viewModel.exportError.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val importError by viewModel.importError.collectAsState()
    var selectedFilter by remember { mutableStateOf<ActivityType?>(null) }
    var sortOption by remember { mutableStateOf(TrackSortOption.DATE_DESC) }
    var showSortMenu by remember { mutableStateOf(false) }
    var exportTrackId by remember { mutableStateOf<String?>(null) }
    var insightTrackId by remember { mutableStateOf<String?>(null) }
    val insightAnalysis by viewModel.insightAnalysis.collectAsState()
    val insightLoading by viewModel.insightLoading.collectAsState()

    // File picker for import
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importTrack(it) }
    }

    // Share the exported file when ready
    LaunchedEffect(exportedFile) {
        exportedFile?.let { file ->
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val mimeType = when {
                file.name.endsWith(".gpx") -> "application/gpx+xml"
                file.name.endsWith(".csv") -> "text/csv"
                else -> "application/json"
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share track"))
            viewModel.clearExportedFile()
        }
    }

    // Show snackbar for errors and import success
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(exportError) {
        exportError?.let {
            snackbarHostState.showSnackbar(it)
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
            snackbarHostState.showSnackbar("Imported \"${it.trackName}\" with ${it.pointCount} points")
            viewModel.clearImportResult()
        }
    }

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
                IconButton(onClick = {
                    importLauncher.launch(arrayOf(
                        "application/json",
                        "application/gpx+xml",
                        "text/xml",
                        "application/xml",
                        "text/csv",
                        "text/comma-separated-values",
                        "*/*"
                    ))
                }) {
                    Icon(Icons.Filled.FileUpload, contentDescription = "Import track")
                }
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
            val activeTypeNames = currentSettings.activityConfigs.filter { it.isActive }.map { it.activityType }.toSet()
            items(ActivityType.entries.filter { it != ActivityType.UNKNOWN && (activeTypeNames.isEmpty() || activeTypeNames.contains(it.name)) }) { type ->
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
                    val trackPoints = remember(tracksWithPoints, track.id) {
                        tracksWithPoints.find { it.track.id == track.id }?.points ?: emptyList()
                    }
                    TrackCard(
                        track = track,
                        points = trackPoints,
                        currency = currentSettings.currency,
                        onClick = { onTrackClick(track.id) },
                        onExport = { exportTrackId = track.id },
                        onDelete = { viewModel.deleteTrack(track.id) },
                        onReanalyze = { viewModel.reanalyzeTrack(track.id) },
                        onInsight = {
                            insightTrackId = track.id
                            viewModel.loadAnalysisForTrack(track.id)
                        },
                        isTrackingActive = isTracking
                    )
                }
            }
        }
    }

    // Export format picker dialog
    if (exportTrackId != null) {
        ExportFormatDialog(
            onFormatSelected = { format ->
                viewModel.exportTrack(exportTrackId!!, format)
                exportTrackId = null
            },
            onDismiss = { exportTrackId = null }
        )
    }

    // AI Insight dialog
    if (insightTrackId != null) {
        AiInsightDialog(
            analysis = insightAnalysis,
            isLoading = insightLoading,
            trackName = tracks.find { it.id == insightTrackId }?.name ?: "Track",
            onDismiss = {
                insightTrackId = null
                viewModel.clearInsight()
            }
        )
    }

    // Snackbar host for error messages
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(hostState = snackbarHostState)
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
    points: List<TrackPoint>,
    currency: String,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onReanalyze: () -> Unit,
    onInsight: () -> Unit,
    isTrackingActive: Boolean = false
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

                    // Activity timeline graph
                    if (points.size >= 2) {
                        ActivityTimelineGraph(
                            points = points,
                            modifier = Modifier.padding(bottom = 14.dp)
                        )
                        @Suppress("DEPRECATION")
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(14.dp))
                    }

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
                    }

                    // Ride Cost
                    track.rideCost?.let { cost ->
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailItem(
                            modifier = Modifier.fillMaxWidth(),
                            label = "Ride Cost",
                            value = "${currency}${String.format(Locale.US, "%.2f", cost)}"
                        )
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
                        OutlinedButton(
                            onClick = onInsight,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Lightbulb,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Insight", fontSize = 13.sp)
                        }
                        FilledTonalButton(
                            onClick = { showDeleteDialog = true },
                            enabled = !(isTrackingActive && track.isActive),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Error.copy(alpha = 0.1f),
                                contentColor = Error
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = if (isTrackingActive && track.isActive) "Stop tracking to delete" else "Delete",
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

// ─── EXPORT FORMAT DIALOG ────────────────────────────

@Composable
private fun ExportFormatDialog(
    onFormatSelected: (ExportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.FileDownload,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = { Text("Export Format") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormatOption(
                    icon = Icons.Filled.DataObject,
                    label = "JSON",
                    description = "Full data with AI analysis",
                    color = Secondary,
                    onClick = { onFormatSelected(ExportFormat.JSON) }
                )
                ExportFormatOption(
                    icon = Icons.Filled.Map,
                    label = "GPX",
                    description = "GPS track for mapping apps",
                    color = PrimaryLight,
                    onClick = { onFormatSelected(ExportFormat.GPX) }
                )
                ExportFormatOption(
                    icon = Icons.Filled.TableChart,
                    label = "CSV",
                    description = "Spreadsheet-friendly format",
                    color = Accent,
                    onClick = { onFormatSelected(ExportFormat.CSV) }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ExportFormatOption(
    icon: ImageVector,
    label: String,
    description: String,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = color.copy(alpha = 0.15f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── AI INSIGHT DIALOG ──────────────────────────────

@Composable
private fun AiInsightDialog(
    analysis: AiAnalysis?,
    isLoading: Boolean,
    trackName: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                if (trackName.isNotEmpty()) "AI Insight" else "AI Insight",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (isLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Analyzing track...",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (analysis != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Detected activity + confidence
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Detected Activity",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    analysis.detectedActivity.name.lowercase()
                                        .replaceFirstChar { it.uppercase() },
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                            }
                            Surface(
                                color = when {
                                    analysis.confidence >= 0.8f -> Primary.copy(alpha = 0.15f)
                                    analysis.confidence >= 0.5f -> Accent.copy(alpha = 0.15f)
                                    else -> Error.copy(alpha = 0.15f)
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "${"%.0f".format(analysis.confidence * 100)}% conf.",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = when {
                                        analysis.confidence >= 0.8f -> Primary
                                        analysis.confidence >= 0.5f -> Accent
                                        else -> Error
                                    }
                                )
                            }
                        }
                    }

                    // Summary
                    if (analysis.summary.isNotBlank()) {
                        Column {
                            Text(
                                "Summary",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                analysis.summary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }

                    // Suggestions
                    if (analysis.suggestions.isNotBlank()) {
                        Column {
                            Text(
                                "Suggestions",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val suggestions = try {
                                val arr = JSONArray(analysis.suggestions)
                                (0 until arr.length()).map { arr.getString(it) }
                            } catch (_: Exception) {
                                listOf(analysis.suggestions)
                            }
                            suggestions.forEach { suggestion ->
                                Row(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        "\u2022 ",
                                        fontSize = 14.sp,
                                        color = Accent
                                    )
                                    Text(
                                        suggestion,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }

                    // Health insights
                    if (!analysis.healthInsights.isNullOrBlank()) {
                        Surface(
                            color = Running.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Filled.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Running
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "Health Insights",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Running
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        analysis.healthInsights,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    "No analysis available for this track.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

// ─── HELPERS ─────────────────────────────────────────

private fun activityColor(type: ActivityType): Color = when (type) {
    ActivityType.WALKING    -> Walking
    ActivityType.RUNNING    -> Running
    ActivityType.CYCLING    -> Cycling
    ActivityType.DRIVING    -> Driving
    ActivityType.FLYING     -> Flying
    ActivityType.STATIONARY -> Stationary
    ActivityType.HIKING     -> Hiking
    ActivityType.UNKNOWN    -> Color.Gray
}

private fun activityIcon(type: ActivityType): ImageVector = when (type) {
    ActivityType.WALKING    -> Icons.Filled.DirectionsWalk
    ActivityType.RUNNING    -> Icons.Filled.DirectionsRun
    ActivityType.CYCLING    -> Icons.Filled.DirectionsBike
    ActivityType.DRIVING    -> Icons.Filled.DirectionsCar
    ActivityType.FLYING     -> Icons.Filled.Flight
    ActivityType.STATIONARY -> Icons.Filled.PauseCircle
    ActivityType.HIKING     -> Icons.Filled.Terrain
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
            val days = ((todayStart - timestamp) / 86_400_000).toInt()
            "$days days ago"
        }
        else -> null
    }
}
