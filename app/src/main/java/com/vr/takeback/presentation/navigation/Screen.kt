package com.vr.takeback.presentation.navigation

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
    object Home : Screen("home", "Home", Icons.Filled.Home, Icons.Outlined.Home)
    object Profiles : Screen("profiles", "Profiles", Icons.Filled.Shield, Icons.Outlined.Shield)
    object Focus : Screen("focus", "Focus", Icons.Filled.Timer, Icons.Outlined.Timer)
    object Insights : Screen("insights", "Insights", Icons.Filled.BarChart, Icons.Outlined.BarChart)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
    object PermissionCenter : Screen("permission_center", "Permissions", Icons.Filled.Security, Icons.Outlined.Security)
}
