package com.trackjourney.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.trackjourney.data.model.ActivityType
import com.trackjourney.ui.navigation.Screen
import com.trackjourney.ui.screens.analysis.AnalysisScreen
import com.trackjourney.ui.screens.dashboard.DashboardScreen
import com.trackjourney.ui.screens.map.MapScreen
import com.trackjourney.ui.screens.settings.SettingsScreen
import com.trackjourney.ui.screens.tracks.TracksScreen
import com.trackjourney.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrackMyJourneyTheme {
                TrackMyJourneyApp()
            }
        }
    }
}

@Composable
fun TrackMyJourneyApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val trackingViewModel: TrackingStateViewModel = hiltViewModel()
    val trackingState by trackingViewModel.state.collectAsState()

    var pendingTrackingStart by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted && pendingTrackingStart) {
            trackingViewModel.startTracking()
            pendingTrackingStart = false
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true

                    NavigationBarItem(
                        icon = {
                            Icon(
                                if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title
                            )
                        },
                        label = { Text(screen.title) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Global tracking toggle bar — visible on all pages
            TrackingToggleBar(
                state = trackingState,
                onToggle = { enabled ->
                    if (enabled) {
                        pendingTrackingStart = true
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        )
                    } else {
                        trackingViewModel.stopTracking()
                    }
                }
            )

            NavHost(
                navController = navController,
                startDestination = Screen.Dashboard.route
            ) {
                composable(Screen.Dashboard.route) {
                    DashboardScreen()
                }
                composable(Screen.Map.route) {
                    MapScreen()
                }
                composable(Screen.Tracks.route) {
                    TracksScreen(
                        onTrackClick = { trackId ->
                            navController.navigate("map/$trackId")
                        }
                    )
                }
                composable(
                    route = "map/{trackId}",
                    arguments = listOf(navArgument("trackId") { type = NavType.StringType })
                ) {
                    MapScreen(
                        onBackFromTrack = { navController.popBackStack() }
                    )
                }
                composable(Screen.Analysis.route) {
                    AnalysisScreen()
                }
                composable(Screen.Settings.route) {
                    SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun TrackingToggleBar(
    state: TrackingBarState,
    onToggle: (Boolean) -> Unit
) {
    val (activityIcon, activityColor, activityLabel) = remember(state.activityType) {
        when (state.activityType) {
            ActivityType.WALKING    -> Triple(Icons.Filled.DirectionsWalk, Walking, "Walking")
            ActivityType.RUNNING    -> Triple(Icons.Filled.DirectionsRun, Running, "Running")
            ActivityType.CYCLING    -> Triple(Icons.Filled.DirectionsBike, Cycling, "Cycling")
            ActivityType.DRIVING    -> Triple(Icons.Filled.DirectionsCar, Driving, "Driving")
            ActivityType.FLYING     -> Triple(Icons.Filled.Flight, Flying, "Flying")
            ActivityType.STATIONARY -> Triple(Icons.Filled.PauseCircle, Stationary, "Still")
            ActivityType.HIKING     -> Triple(Icons.Filled.Terrain, Hiking, "Hiking")
            ActivityType.UNKNOWN    -> Triple(Icons.Filled.MyLocation, Primary, "Detecting")
        }
    }

    // Elapsed time
    val elapsed = if (state.isTracking && state.startTime > 0) {
        val diff = System.currentTimeMillis() - state.startTime
        val mins = diff / 60_000
        val hrs = mins / 60
        if (hrs > 0) "${hrs}h ${mins % 60}m" else "${mins}m"
    } else ""

    Surface(
        color = if (state.isTracking)
            activityColor.copy(alpha = 0.08f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            // Row 1: Status + Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isTracking) {
                    // Activity indicator dot
                    Surface(
                        shape = CircleShape,
                        color = activityColor,
                        modifier = Modifier.size(10.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        activityLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = activityColor
                    )
                    if (state.trackName.isNotEmpty()) {
                        Text(
                            "  •  ${state.trackName}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                } else {
                    Icon(
                        Icons.Filled.LocationOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Location Tracking",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Switch(
                    checked = state.isTracking,
                    onCheckedChange = onToggle,
                    modifier = Modifier.height(28.dp),
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = activityColor,
                        checkedThumbColor = Color.White
                    )
                )
            }

            // Row 2: Stats (only when tracking)
            AnimatedVisibility(
                visible = state.isTracking,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TrackingStatChip(
                        icon = Icons.Filled.Straighten,
                        value = String.format(Locale.US, "%.2f", state.distanceKm),
                        unit = "km",
                        color = Primary
                    )
                    TrackingStatChip(
                        icon = Icons.Filled.Speed,
                        value = String.format(Locale.US, "%.1f", state.currentSpeedKmh),
                        unit = "km/h",
                        color = activityColor
                    )
                    TrackingStatChip(
                        icon = Icons.Filled.GpsFixed,
                        value = "${state.pointCount}",
                        unit = "pts",
                        color = Secondary
                    )
                    TrackingStatChip(
                        icon = Icons.Filled.SatelliteAlt,
                        value = "${state.satelliteInfo.usedInFix}/${state.satelliteInfo.totalVisible}",
                        unit = "",
                        color = when {
                            state.satelliteInfo.usedInFix >= 8 -> PrimaryLight
                            state.satelliteInfo.usedInFix >= 4 -> Accent
                            else -> Error
                        }
                    )
                    if (elapsed.isNotEmpty()) {
                        TrackingStatChip(
                            icon = Icons.Filled.Schedule,
                            value = elapsed,
                            unit = "",
                            color = Accent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackingStatChip(
    icon: ImageVector,
    value: String,
    unit: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = color
        )
        Text(
            text = if (unit.isNotEmpty()) "$value $unit" else value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}
