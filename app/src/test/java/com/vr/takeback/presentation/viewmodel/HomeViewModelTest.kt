package com.vr.takeback.presentation.viewmodel

import com.vr.takeback.data.repository.DailyUsageSummary
import com.vr.takeback.data.repository.QuickBlockState
import com.vr.takeback.domain.model.PermissionStatus
import com.vr.takeback.domain.model.Profile
import com.vr.takeback.domain.model.Schedule
import com.vr.takeback.domain.model.TargetApp
import com.vr.takeback.domain.usage.UsageDataQuality
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class HomeViewModelTest {

    @Test
    fun testDefaultHomeUiState_invariants() {
        val state = HomeUiState()
        assertTrue("Accessibility should be flagged missing by default", state.isAccessibilityMissing)
        assertTrue("Usage stats should be flagged missing by default", state.isUsageStatsMissing)
        assertFalse("Quick block should be inactive by default", state.isQuickBlockActive)
        assertFalse("No profiles should be active by default", state.isProtectionActive)
        assertFalse("No allowance dialog should be shown by default", state.showAllowanceDialog)
        assertNull("No pending toggle profile by default", state.pendingToggleProfileId)
    }

    @Test
    fun testHomeUiState_permissionsGranted() {
        val perms = PermissionStatus(
            hasUsageStats = true,
            hasAccessibility = true,
            hasOverlay = true
        )
        val state = HomeUiState(permissionStatus = perms)
        assertFalse("Accessibility is granted", state.isAccessibilityMissing)
        assertFalse("Usage stats is granted", state.isUsageStatsMissing)
    }

    @Test
    fun testHomeUiState_quickBlockActive_setsProtectionActive() {
        val qbState = QuickBlockState(isActive = true, remainingSeconds = 1800)
        val state = HomeUiState(quickBlockState = qbState)
        assertTrue("Quick block active", state.isQuickBlockActive)
        assertTrue("Protection should be active when Quick Block is active", state.isProtectionActive)
        assertTrue("Protection should be configured", state.isProtectionConfigured)
    }

    @Test
    fun testHomeUiState_activelyEnforcingProfiles() {
        val studyProfile = Profile(
            id = "study_profile",
            name = "Deep Study",
            isEnabled = true,
            targetApps = listOf(TargetApp("com.instagram.android", "Instagram")),
            schedules = listOf(
                Schedule(
                    profileId = "study_profile",
                    daysOfWeekMask = 127, // All days
                    startTimeSeconds = 9 * 3600,
                    endTimeSeconds = 17 * 3600
                )
            )
        )
        val stateDuringSchedule = HomeUiState(
            profiles = listOf(studyProfile),
            evaluationTime = LocalTime.of(14, 0),
            evaluationDay = DayOfWeek.MONDAY
        )
        assertEquals(1, stateDuringSchedule.activelyEnforcingProfiles.size)
        assertTrue(stateDuringSchedule.isProtectionActive)

        val stateOutsideSchedule = HomeUiState(
            profiles = listOf(studyProfile),
            evaluationTime = LocalTime.of(20, 0),
            evaluationDay = DayOfWeek.MONDAY
        )
        assertEquals(0, stateOutsideSchedule.activelyEnforcingProfiles.size)
        assertFalse(stateOutsideSchedule.isProtectionActive)
        assertTrue("Protection is configured even if outside active hours", stateOutsideSchedule.isProtectionConfigured)
    }

    @Test
    fun testHomeUiEvent_typesAndPayloads() {
        val startQb = HomeUiEvent.StartQuickBlock(45)
        assertEquals(45, startQb.minutes)

        val setAllowance = HomeUiEvent.SetDailyAllowance(180)
        assertEquals(180, setAllowance.minutes)

        val toggleReq = HomeUiEvent.RequestToggleProfile("profile_abc")
        assertEquals("profile_abc", toggleReq.profileId)

        val confirmToggle = HomeUiEvent.ConfirmToggleProfileWithToken("profile_abc", "token_xyz")
        assertEquals("profile_abc", confirmToggle.profileId)
        assertEquals("token_xyz", confirmToggle.token)
    }

    @Test
    fun testUsageSummary_deviceInteractiveAvailable_vsTrackedAppsOnly() {
        val interactiveSummary = DailyUsageSummary(
            totalTimeMillis = 3600_000L,
            deviceScreenTimeMillis = 7200_000L,
            isRealDataAvailable = true,
            isDeviceInteractiveAvailable = true,
            topApps = emptyList(),
            sessions = emptyList(),
            weeklyOverview = emptyList(),
            dataQuality = UsageDataQuality.RECONSTRUCTED_COMPLETE
        )
        val state1 = HomeUiState(usageSummary = interactiveSummary)
        assertTrue(state1.usageSummary!!.isDeviceInteractiveAvailable)
        assertEquals(7200_000L, state1.usageSummary!!.deviceScreenTimeMillis)

        val partialSummary = DailyUsageSummary(
            totalTimeMillis = 3600_000L,
            deviceScreenTimeMillis = null,
            isRealDataAvailable = true,
            isDeviceInteractiveAvailable = false,
            topApps = emptyList(),
            sessions = emptyList(),
            weeklyOverview = emptyList(),
            dataQuality = UsageDataQuality.RECONSTRUCTED_PARTIAL
        )
        val state2 = HomeUiState(usageSummary = partialSummary)
        assertFalse(state2.usageSummary!!.isDeviceInteractiveAvailable)
        assertNull(state2.usageSummary!!.deviceScreenTimeMillis)
    }
}
