package com.vr.takeback

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vr.takeback.platform.service.TakeBackEnforcementForegroundService
import com.vr.takeback.presentation.navigation.TakeBackNavHost
import com.vr.takeback.ui.theme.TakeBackTheme

class MainActivity : ComponentActivity() {

    private val profileRepository get() = TakeBackApplication.profileRepository
    private val permissionRepository get() = TakeBackApplication.permissionRepository
    private val focusSessionRepository get() = TakeBackApplication.focusSessionRepository
    private val quickBlockRepository get() = TakeBackApplication.quickBlockRepository
    private val strictModeRepository get() = TakeBackApplication.strictModeRepository
    private val alarmRepository get() = TakeBackApplication.alarmRepository
    private val usageStatsRepository get() = TakeBackApplication.usageStatsRepository
    private val timelineRepository get() = TakeBackApplication.timelineRepository
    private val alarmScheduler get() = TakeBackApplication.alarmScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start ongoing protection service
        try {
            TakeBackEnforcementForegroundService.start(applicationContext)
        } catch (e: Exception) {
            // Foreground service start will proceed once user grants notification/battery permissions
        }

        // Arm punctual schedule boundaries
        profileRepository.profiles.value.filter { it.isEnabled }.forEach { profile ->
            alarmScheduler.scheduleNextAlarmsForProfile(profile)
        }

        setContent {
            TakeBackTheme {
                TakeBackNavHost(
                    profileRepository = profileRepository,
                    permissionRepository = permissionRepository,
                    focusSessionRepository = focusSessionRepository,
                    quickBlockRepository = quickBlockRepository,
                    strictModeRepository = strictModeRepository,
                    alarmRepository = alarmRepository,
                    usageStatsRepository = usageStatsRepository,
                    timelineRepository = timelineRepository
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionRepository.refreshPermissions()
    }
}
