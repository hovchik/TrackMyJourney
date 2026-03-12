package com.trackjourney.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Dashboard : Screen(
        route = "dashboard",
        title = "Dashboard",
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard
    )

    data object Map : Screen(
        route = "map",
        title = "Map",
        selectedIcon = Icons.Filled.Map,
        unselectedIcon = Icons.Outlined.Map
    )

    data object Tracks : Screen(
        route = "tracks",
        title = "Tracks",
        selectedIcon = Icons.Filled.Route,
        unselectedIcon = Icons.Outlined.Route
    )

    data object Analysis : Screen(
        route = "analysis",
        title = "AI Insights",
        selectedIcon = Icons.Filled.Analytics,
        unselectedIcon = Icons.Outlined.Analytics
    )

    data object Settings : Screen(
        route = "settings",
        title = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    )

    data object TrackDetail : Screen(
        route = "track_detail/{trackId}",
        title = "Track Detail",
        selectedIcon = Icons.Filled.Info,
        unselectedIcon = Icons.Outlined.Info
    ) {
        fun createRoute(trackId: String) = "track_detail/$trackId"
    }

    data object Subscription : Screen(
        route = "subscription",
        title = "Premium",
        selectedIcon = Icons.Filled.WorkspacePremium,
        unselectedIcon = Icons.Outlined.WorkspacePremium
    )

    data object Onboarding : Screen(
        route = "onboarding",
        title = "Welcome",
        selectedIcon = Icons.Filled.Start,
        unselectedIcon = Icons.Outlined.Start
    )

    companion object {
        val bottomNavItems = listOf(Dashboard, Map, Tracks, Analysis, Settings)
    }
}
