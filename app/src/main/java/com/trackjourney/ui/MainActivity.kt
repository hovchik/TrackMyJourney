package com.trackjourney.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
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
                            // Navigate to track detail if desired
                        }
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
    Surface(
        color = if (state.isTracking)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (state.isTracking) Icons.Filled.MyLocation else Icons.Filled.LocationOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (state.isTracking)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                if (state.isTracking)
                    "${state.pointCount} pts  •  ${String.format(Locale.US, "%.2f", state.distanceKm)} km  •  ${String.format(Locale.US, "%.1f", state.currentSpeedKmh)} km/h"
                else
                    "Location Tracking",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (state.isTracking)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )

            Switch(
                checked = state.isTracking,
                onCheckedChange = onToggle,
                modifier = Modifier.height(28.dp),
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}
