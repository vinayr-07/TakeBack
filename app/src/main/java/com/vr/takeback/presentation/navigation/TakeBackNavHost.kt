package com.vr.takeback.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.vr.takeback.data.repository.AlarmRepository
import com.vr.takeback.data.repository.FocusSessionRepository
import com.vr.takeback.data.repository.PermissionRepository
import com.vr.takeback.data.repository.ProfileRepository
import com.vr.takeback.data.repository.QuickBlockRepository
import com.vr.takeback.data.repository.StrictModeRepository
import com.vr.takeback.data.repository.TimelineRepository
import com.vr.takeback.data.repository.UsageStatsRepository
import com.vr.takeback.presentation.ui.screens.*

@Composable
fun TakeBackNavHost(
    profileRepository: ProfileRepository,
    permissionRepository: PermissionRepository,
    focusSessionRepository: FocusSessionRepository,
    quickBlockRepository: QuickBlockRepository,
    strictModeRepository: StrictModeRepository,
    alarmRepository: AlarmRepository,
    usageStatsRepository: UsageStatsRepository,
    timelineRepository: TimelineRepository
) {
    val navController = rememberNavController()

    val navItems = listOf(
        Screen.Home,
        Screen.Profiles,
        Screen.Focus,
        Screen.Insights,
        Screen.Settings
    )

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            // Hide bottom bar on nested screens like permission center or usage diagnostics
            if (currentRoute in navItems.map { it.route }) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    navItems.forEach { screen ->
                        val isSelected = currentRoute == screen.route
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = screen.title
                                )
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            selected = isSelected,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = (screen.route != Screen.Home.route)
                                        }
                                        launchSingleTop = true
                                        restoreState = (screen.route != Screen.Home.route)
                                    }
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    profileRepository = profileRepository,
                    quickBlockRepository = quickBlockRepository,
                    usageStatsRepository = usageStatsRepository,
                    permissionRepository = permissionRepository,
                    onNavigateToFocus = { navController.navigate(Screen.Focus.route) },
                    onNavigateToProfiles = { navController.navigate(Screen.Profiles.route) },
                    onNavigateToPermissions = { navController.navigate(Screen.PermissionCenter.route) },
                    onNavigateToInsights = { navController.navigate(Screen.Insights.route) }
                )
            }
            composable(Screen.Profiles.route) {
                ProfilesScreen(
                    profileRepository = profileRepository,
                    strictModeRepository = strictModeRepository,
                    permissionRepository = permissionRepository
                )
            }
            composable(Screen.Focus.route) {
                FocusScreen(
                    focusSessionRepository = focusSessionRepository,
                    alarmRepository = alarmRepository
                )
            }
            composable(Screen.Insights.route) {
                InsightsScreen(
                    usageStatsRepository = usageStatsRepository,
                    timelineRepository = timelineRepository,
                    onNavigateToDiagnostics = { navController.navigate(Screen.UsageDiagnostics.route) }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    permissionRepository = permissionRepository,
                    strictModeRepository = strictModeRepository,
                    onNavigateToPermissions = { navController.navigate(Screen.PermissionCenter.route) },
                    onNavigateToDiagnostics = { navController.navigate(Screen.UsageDiagnostics.route) }
                )
            }
            composable(Screen.PermissionCenter.route) {
                PermissionCenterScreen(
                    permissionRepository = permissionRepository,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.UsageDiagnostics.route) {
                UsageDiagnosticsScreen(
                    usageStatsRepository = usageStatsRepository,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
