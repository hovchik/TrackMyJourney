package com.trackmyjourney.ui.screens.dashboard

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trackmyjourney.ui.components.LoadingIndicator
import com.trackmyjourney.ui.theme.*
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("Dashboard", fontWeight = FontWeight.Bold) }
        )

        // Period selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatsPeriod.entries.forEach { period ->
                FilterChip(
                    selected = uiState.selectedPeriod == period,
                    onClick = { viewModel.selectPeriod(period) },
                    label = {
                        Text(
                            period.label,
                            fontWeight = if (uiState.selectedPeriod == period) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(64.dp),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        } else {
            val stats = uiState.stats

            // Distance & Duration — large hero cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HeroStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Straighten,
                    value = formatDistance(stats.totalDistanceKm),
                    unit = "km",
                    label = "Distance",
                    color = Primary
                )
                HeroStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Schedule,
                    value = formatDurationShort(stats.totalDurationMs),
                    unit = "",
                    label = "Duration",
                    color = Secondary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Trips, Avg Speed, Max Speed, Calories
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SmallStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Route,
                    value = stats.trackCount.toString(),
                    label = "Trips",
                    color = Accent
                )
                SmallStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Speed,
                    value = String.format(Locale.US, "%.1f", stats.averageSpeedKmh),
                    label = "Avg km/h",
                    color = Walking
                )
                SmallStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.LocalFireDepartment,
                    value = formatCalories(stats.totalCalories),
                    label = "Calories",
                    color = Running
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Detailed breakdown
            Text(
                "Details",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            DetailRow(
                icon = Icons.Filled.Straighten,
                label = "Total Distance",
                value = String.format(Locale.US, "%.2f km", stats.totalDistanceKm)
            )
            DetailRow(
                icon = Icons.Filled.Schedule,
                label = "Total Time",
                value = formatDurationLong(stats.totalDurationMs)
            )
            DetailRow(
                icon = Icons.Filled.Route,
                label = "Number of Trips",
                value = stats.trackCount.toString()
            )
            DetailRow(
                icon = Icons.Filled.Speed,
                label = "Average Speed",
                value = String.format(Locale.US, "%.1f km/h", stats.averageSpeedKmh)
            )
            DetailRow(
                icon = Icons.Filled.FlashOn,
                label = "Top Speed",
                value = String.format(Locale.US, "%.1f km/h", stats.maxSpeedKmh)
            )
            DetailRow(
                icon = Icons.Filled.LocalFireDepartment,
                label = "Calories Burned",
                value = String.format(Locale.US, "%.0f kcal", stats.totalCalories)
            )

            if (stats.trackCount == 0) {
                Spacer(modifier = Modifier.height(32.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Explore,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No trips in this period",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                        Text(
                            "Start tracking from the Map tab",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun HeroStatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    unit: String,
    label: String,
    color: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        unit,
                        fontSize = 14.sp,
                        color = color.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SmallStatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatDistance(km: Double): String = when {
    km < 1 -> String.format(Locale.US, "%.0f m", km * 1000)
    km < 100 -> String.format(Locale.US, "%.1f", km)
    else -> String.format(Locale.US, "%.0f", km)
}

private fun formatDurationShort(ms: Long): String {
    val totalMin = ms / 60_000
    val hours = totalMin / 60
    val mins = totalMin % 60
    return when {
        hours > 0 -> "${hours}h${mins}m"
        mins > 0 -> "${mins}m"
        else -> "0m"
    }
}

private fun formatCalories(cal: Double): String = when {
    cal < 1 -> "0"
    cal < 1000 -> String.format(Locale.US, "%.0f", cal)
    else -> String.format(Locale.US, "%.1fk", cal / 1000)
}

private fun formatDurationLong(ms: Long): String {
    val totalMin = ms / 60_000
    val hours = totalMin / 60
    val mins = totalMin % 60
    return when {
        hours > 0 -> "${hours}h ${mins}min"
        mins > 0 -> "${mins} min"
        else -> "0 min"
    }
}
