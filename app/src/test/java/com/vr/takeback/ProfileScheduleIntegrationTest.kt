package com.vr.takeback

import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.engine.SystemContextSnapshot
import com.vr.takeback.domain.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class ProfileScheduleIntegrationTest {

    private lateinit var engine: CentralRestrictionEngine

    @Before
    fun setUp() {
        engine = CentralRestrictionEngine()
    }

    @Test
    fun profileScheduleIntegration_createsAndEnforcesSchedule() {
        // 1. Create a Work Profile with 09:00 to 17:00 Weekdays schedule
        val schedule = Schedule(
            id = 101L,
            profileId = "work_profile",
            daysOfWeekMask = 31, // Mon-Fri
            startTimeSeconds = 9 * 3600,
            endTimeSeconds = 17 * 3600,
            isEnabled = true
        )
        val profile = Profile(
            id = "work_profile",
            name = "Work Focus",
            isEnabled = true,
            targetApps = listOf(
                TargetApp("com.instagram.android", "Instagram"),
                TargetApp("com.reddit.frontpage", "Reddit")
            ),
            schedules = listOf(schedule)
        )

        // 2. Evaluate during Work Hours (Tuesday 11:30 AM)
        val snapshotWork = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(11, 30),
            dayOfWeek = DayOfWeek.TUESDAY,
            foregroundPackageName = "com.instagram.android"
        )
        val decisionWork = engine.evaluate(listOf(profile), snapshotWork)
        assertTrue("Instagram must be blocked during scheduled work hours", decisionWork is BlockDecision.Blocked)

        // 3. Evaluate after Work Hours (Tuesday 17:30 PM)
        val snapshotEvening = snapshotWork.copy(localTime = LocalTime.of(17, 30))
        val decisionEvening = engine.evaluate(listOf(profile), snapshotEvening)
        assertTrue("Instagram must be allowed after scheduled work hours", decisionEvening is BlockDecision.Allowed)

        // 4. Modify schedule to extend until 19:00
        val modifiedSchedule = schedule.copy(endTimeSeconds = 19 * 3600)
        val updatedProfile = profile.copy(schedules = listOf(modifiedSchedule))

        // 5. Re-evaluate at 17:30 PM with extended schedule
        val decisionExtended = engine.evaluate(listOf(updatedProfile), snapshotEvening)
        assertTrue("Instagram must now be blocked under the modified 19:00 schedule", decisionExtended is BlockDecision.Blocked)
    }

    @Test
    fun digitalDetox_withoutSchedule_enforcesImmediateBlocking() {
        // Digital Detox profile has no schedules: blocks immediately when enabled
        val detoxProfile = Profile(
            id = "detox_profile",
            name = "Digital Detox",
            isEnabled = true,
            targetApps = listOf(
                TargetApp("com.zhiliaoapp.musically", "TikTok"),
                TargetApp("com.facebook.katana", "Facebook")
            ),
            schedules = emptyList() // No schedule = always block while enabled
        )

        val snapshot = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(21, 15),
            dayOfWeek = DayOfWeek.SATURDAY,
            foregroundPackageName = "com.zhiliaoapp.musically"
        )

        val isEnforcing = engine.isProfileCurrentlyEnforcing(
            detoxProfile,
            LocalTime.of(21, 15),
            DayOfWeek.SATURDAY
        )
        assertTrue("Digital Detox profile must report active enforcing status", isEnforcing)

        val decision = engine.evaluate(listOf(detoxProfile), snapshot)
        assertTrue("TikTok must be blocked immediately when Digital Detox is enabled", decision is BlockDecision.Blocked)

        // Turn OFF Digital Detox profile
        val disabledDetoxProfile = detoxProfile.copy(isEnabled = false)
        val isEnforcingAfterDisable = engine.isProfileCurrentlyEnforcing(
            disabledDetoxProfile,
            LocalTime.of(21, 15),
            DayOfWeek.SATURDAY
        )
        assertFalse("Disabled profile must not report enforcing status", isEnforcingAfterDisable)

        val decisionAfterDisable = engine.evaluate(listOf(disabledDetoxProfile), snapshot)
        assertTrue("TikTok must be allowed immediately when profile is turned off", decisionAfterDisable is BlockDecision.Allowed)
    }
}
