package com.trackmyjourney.ui.screens.analysis

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackmyjourney.data.model.ActivityType
import com.trackmyjourney.data.repository.ActivityBreakdown
import com.trackmyjourney.ui.components.LoadingIndicator
import com.trackmyjourney.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    viewModel: AnalysisViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            TopAppBar(
                title = { Text("AI Insights", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshWithAiAnalysis() },
                        enabled = !uiState.isAnalyzing
                    ) {
                        if (uiState.isAnalyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Re-analyze tracks")
                        }
                    }
                }
            )
        }

        // ── Overview Stats (expanded) ─────────────────────────
        item {
            Text(
                "Overview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OverviewStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Route,
                    value = uiState.stats.totalTracks.toString(),
                    label = "Total Tracks",
                    color = Primary
                )
                OverviewStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Straighten,
                    value = String.format("%.1f", uiState.stats.totalDistanceKm),
                    label = "Total km",
                    color = Secondary
                )
                OverviewStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Speed,
                    value = String.format("%.1f", uiState.stats.averageSpeedKmh),
                    label = "Avg km/h",
                    color = Accent
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OverviewStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Schedule,
                    value = formatDuration(uiState.stats.totalDurationMs),
                    label = "Total Time",
                    color = Walking
                )
                OverviewStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.LocalFireDepartment,
                    value = formatCalories(uiState.stats.totalCalories),
                    label = "Calories",
                    color = Running
                )
                OverviewStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Terrain,
                    value = "${uiState.stats.totalElevationGain.toInt()}m",
                    label = "Elev. Gain",
                    color = Hiking
                )
            }
        }

        // Extra detail row
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DetailChip(
                        label = "Top Speed",
                        value = String.format("%.1f km/h", uiState.stats.maxSpeedKmh)
                    )
                    DetailChip(
                        label = "GPS Points",
                        value = formatLargeNumber(uiState.stats.totalPoints)
                    )
                    DetailChip(
                        label = "Avg/Trip",
                        value = if (uiState.stats.totalTracks > 0)
                            String.format("%.1f km", uiState.stats.totalDistanceKm / uiState.stats.totalTracks)
                        else "—"
                    )
                }
            }
        }

        // ── Activity Breakdown ────────────────────────────────
        if (uiState.activityBreakdown.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Activity Breakdown",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(uiState.activityBreakdown) { breakdown ->
                ActivityBreakdownCard(breakdown, uiState.stats.totalDistanceKm)
            }
        }

        // ── AI Suggestions ────────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "AI Trip Suggestions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (uiState.isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Analyzing your tracks...", fontSize = 14.sp)
                    }
                }
            }
        } else {
            items(uiState.suggestions) { suggestion ->
                SuggestionCard(suggestion)
            }
        }

        // ── Recent Track Analyses ─────────────────────────────
        if (uiState.recentAnalyses.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Recent Track Analyses",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items(uiState.recentAnalyses) { (track, analysis) ->
                RecentAnalysisCard(track, analysis)
            }
        }

        // ── Activity Detection Legend ─────────────────────────
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Activity Detection Legend",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ActivityLegendItem("Walking", "< 7 km/h", Walking)
                    ActivityLegendItem("Running", "7 — 15 km/h", Running)
                    ActivityLegendItem("Cycling", "15 — 40 km/h", Cycling)
                    ActivityLegendItem("Driving", "40 — 200 km/h", Driving)
                    ActivityLegendItem("Flying", "> 200 km/h", Flying)
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "All AI analysis runs locally on your device. Your data never leaves your phone. " +
                            "Activity detection uses speed, altitude, and movement patterns.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        lineHeight = 18.sp
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun OverviewStatCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = color)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActivityBreakdownCard(breakdown: ActivityBreakdown, totalDistanceKm: Double) {
    val color = activityColor(breakdown.activity)
    val fraction = if (totalDistanceKm > 0)
        (breakdown.totalDistanceKm / totalDistanceKm).toFloat().coerceIn(0f, 1f)
    else 0f

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = color,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.size(10.dp)
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    breakdown.activity.name.lowercase().replaceFirstChar { it.uppercase() },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${breakdown.trackCount} trips",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    String.format("%.1f km", breakdown.totalDistanceKm),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatDuration(breakdown.totalDurationMs),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    String.format("%.0f%%", fraction * 100),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = color
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = fraction,
                modifier = Modifier.fillMaxWidth(),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: com.trackmyjourney.data.ai.LocalAiEngine.TripSuggestion) {
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                suggestion.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                suggestion.description,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
            if (suggestion.score > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = suggestion.score,
                    modifier = Modifier.fillMaxWidth(),
                    color = PrimaryLight,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecentAnalysisCard(
    track: com.trackmyjourney.data.model.TrackSession,
    analysis: com.trackmyjourney.data.model.AiAnalysis
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val color = activityColor(analysis.detectedActivity)

    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = color,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.size(10.dp)
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    track.name.ifEmpty {
                        analysis.detectedActivity.name.lowercase()
                            .replaceFirstChar { it.uppercase() } + " trip"
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    dateFormat.format(Date(track.startTime)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    String.format("%.1f km", track.distanceMeters / 1000.0),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    String.format("%.1f km/h avg", track.avgSpeedKmh),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${(analysis.confidence * 100).toInt()}% confidence",
                    fontSize = 12.sp,
                    color = color
                )
            }
            if (!analysis.lifetimeInsights.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    analysis.lifetimeInsights.split("\n").first(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ActivityLegendItem(name: String, range: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = color,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.size(12.dp)
        ) {}
        Spacer(modifier = Modifier.width(12.dp))
        Text(name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(range, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun activityColor(activity: ActivityType): Color = when (activity) {
    ActivityType.WALKING -> Walking
    ActivityType.RUNNING -> Running
    ActivityType.CYCLING -> Cycling
    ActivityType.DRIVING -> Driving
    ActivityType.FLYING -> Flying
    ActivityType.HIKING -> Hiking
    ActivityType.STATIONARY -> Stationary
    ActivityType.UNKNOWN -> Stationary
}

private fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val hours = totalMin / 60
    val mins = totalMin % 60
    return when {
        hours > 0 -> "${hours}h ${mins}m"
        mins > 0 -> "${mins}m"
        else -> "0m"
    }
}

private fun formatCalories(cal: Double): String = when {
    cal < 1 -> "0"
    cal < 1000 -> String.format("%.0f", cal)
    else -> String.format("%.1fk", cal / 1000)
}

private fun formatLargeNumber(n: Int): String = when {
    n < 1000 -> n.toString()
    n < 1_000_000 -> String.format("%.1fk", n / 1000.0)
    else -> String.format("%.1fM", n / 1_000_000.0)
}
