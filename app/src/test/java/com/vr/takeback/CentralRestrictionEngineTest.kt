package com.vr.takeback

import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.engine.SystemContextSnapshot
import com.vr.takeback.domain.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class CentralRestrictionEngineTest {

    private lateinit var engine: CentralRestrictionEngine

    @Before
    fun setUp() {
        engine = CentralRestrictionEngine()
    }

    @Test
    fun emergencyAllowlist_isNeverBlocked_evenWhenExplicitlyTargeted() {
        val profile = Profile(
            id = "study_profile",
            name = "Deep Study",
            isEnabled = true,
            targetApps = listOf(
                TargetApp(packageName = "com.google.android.dialer", appName = "Phone", isAllowlist = false)
            ),
            schedules = listOf(
                Schedule(profileId = "study_profile", daysOfWeekMask = 127, startTimeSeconds = 0, endTimeSeconds = 86399)
            )
        )

        val snapshot = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(14, 30),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.google.android.dialer"
        )

        val decision = engine.evaluate(listOf(profile), snapshot)
        assertTrue("Emergency phone dialer must always be allowed", decision is BlockDecision.Allowed)
    }

    @Test
    fun scheduledDaytimeBlocking_blocksWithinInterval_andAllowsOutside() {
        val profile = Profile(
            id = "study_profile",
            name = "Deep Study",
            isEnabled = true,
            targetApps = listOf(
                TargetApp(packageName = "com.instagram.android", appName = "Instagram")
            ),
            schedules = listOf(
                Schedule(
                    profileId = "study_profile",
                    daysOfWeekMask = 1, // Monday only (bit 1)
                    startTimeSeconds = 14 * 3600, // 14:00
                    endTimeSeconds = 18 * 3600   // 18:00
                )
            )
        )

        // Inside window: Monday 15:30
        val snapshotInside = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(15, 30),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.instagram.android"
        )
        val decisionInside = engine.evaluate(listOf(profile), snapshotInside)
        assertTrue("Should be blocked inside window", decisionInside is BlockDecision.Blocked)
        assertEquals(BlockReason.ScheduleActive, (decisionInside as BlockDecision.Blocked).reason)

        // Outside window: Monday 18:05
        val snapshotOutside = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(18, 5),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.instagram.android"
        )
        val decisionOutside = engine.evaluate(listOf(profile), snapshotOutside)
        assertTrue("Should be allowed outside window", decisionOutside is BlockDecision.Allowed)

        // Wrong day: Tuesday 15:30
        val snapshotWrongDay = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(15, 30),
            dayOfWeek = DayOfWeek.TUESDAY,
            foregroundPackageName = "com.instagram.android"
        )
        val decisionWrongDay = engine.evaluate(listOf(profile), snapshotWrongDay)
        assertTrue("Should be allowed on non-scheduled day", decisionWrongDay is BlockDecision.Allowed)
    }

    @Test
    fun overnightSchedule_blocksCorrectlyAcrossMidnight() {
        // Sleep schedule: Monday night 22:00 to Tuesday morning 06:00
        val profile = Profile(
            id = "sleep_profile",
            name = "Sleep",
            isEnabled = true,
            targetApps = listOf(
                TargetApp(packageName = "com.twitter.android", appName = "X / Twitter")
            ),
            schedules = listOf(
                Schedule(
                    profileId = "sleep_profile",
                    daysOfWeekMask = 1, // Scheduled for Monday evening (starts Mon 22:00 -> ends Tue 06:00)
                    startTimeSeconds = 22 * 3600, // 22:00
                    endTimeSeconds = 6 * 3600     // 06:00
                )
            )
        )

        // Test 1: Monday at 23:30 (Case B: started today evening)
        val monNight = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(23, 30),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.twitter.android"
        )
        val decisionMonNight = engine.evaluate(listOf(profile), monNight)
        assertTrue("Should be blocked on Monday night", decisionMonNight is BlockDecision.Blocked)

        // Test 2: Tuesday at 02:15 (Case A: started yesterday evening)
        val tueMorning = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(2, 15),
            dayOfWeek = DayOfWeek.TUESDAY,
            foregroundPackageName = "com.twitter.android"
        )
        val decisionTueMorning = engine.evaluate(listOf(profile), tueMorning)
        assertTrue("Should be blocked on Tuesday early morning", decisionTueMorning is BlockDecision.Blocked)

        // Test 3: Tuesday at 06:01 (Schedule expired)
        val tueAwake = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(6, 1),
            dayOfWeek = DayOfWeek.TUESDAY,
            foregroundPackageName = "com.twitter.android"
        )
        val decisionTueAwake = engine.evaluate(listOf(profile), tueAwake)
        assertTrue("Should be allowed after 06:00 on Tuesday", decisionTueAwake is BlockDecision.Allowed)
    }

    @Test
    fun dailyUsageLimit_blocksWhenQuotaExceeded() {
        val profile = Profile(
            id = "work_profile",
            name = "Work Hours",
            isEnabled = true,
            targetApps = listOf(
                TargetApp(packageName = "com.reddit.frontpage", appName = "Reddit")
            ),
            usageLimits = listOf(
                UsageLimit(
                    profileId = "work_profile",
                    targetPackageName = "com.reddit.frontpage",
                    dailyLimitMinutes = 30
                )
            )
        )

        // 25 minutes used -> Allowed
        val snapshotUnder = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(11, 0),
            dayOfWeek = DayOfWeek.WEDNESDAY,
            foregroundPackageName = "com.reddit.frontpage",
            accumulatedForegroundTimeMillis = mapOf("com.reddit.frontpage" to 25 * 60 * 1000L)
        )
        assertTrue(engine.evaluate(listOf(profile), snapshotUnder) is BlockDecision.Allowed)

        // 31 minutes used -> Blocked
        val snapshotOver = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(11, 0),
            dayOfWeek = DayOfWeek.WEDNESDAY,
            foregroundPackageName = "com.reddit.frontpage",
            accumulatedForegroundTimeMillis = mapOf("com.reddit.frontpage" to 31 * 60 * 1000L)
        )
        val decisionOver = engine.evaluate(listOf(profile), snapshotOver)
        assertTrue(decisionOver is BlockDecision.Blocked)
        assertTrue((decisionOver as BlockDecision.Blocked).reason is BlockReason.DailyLimitExceeded)
    }

    @Test
    fun breakWhitelist_allowsDesignatedAppDuringBreak() {
        val profile = Profile(
            id = "study_profile",
            name = "Study",
            isEnabled = true,
            targetApps = listOf(
                TargetApp(packageName = "com.whatsapp", appName = "WhatsApp"),
                TargetApp(packageName = "com.instagram.android", appName = "Instagram")
            ),
            schedules = listOf(
                Schedule(profileId = "study_profile", daysOfWeekMask = 127, startTimeSeconds = 0, endTimeSeconds = 86399)
            )
        )

        // Break active with WhatsApp whitelisted
        val snapshotBreak = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(15, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.whatsapp",
            isBreakActive = true,
            breakWhitelistApps = setOf("com.whatsapp")
        )

        val whatsAppDecision = engine.evaluate(listOf(profile), snapshotBreak)
        assertTrue("WhatsApp must be allowed during break", whatsAppDecision is BlockDecision.Allowed)

        // Instagram is NOT in break whitelist -> still blocked
        val snapshotInstagram = snapshotBreak.copy(foregroundPackageName = "com.instagram.android")
        val instaDecision = engine.evaluate(listOf(profile), snapshotInstagram)
        assertTrue("Instagram must remain blocked during break", instaDecision is BlockDecision.Blocked)
    }

    @Test
    fun immediateProfile_withoutSchedulesOrLimits_blocksTargetAppWhenEnabled() {
        val profile = Profile(
            id = "direct_profile",
            name = "Immediate Block",
            isEnabled = true,
            targetApps = listOf(
                TargetApp(packageName = "com.tiktok.android", appName = "TikTok")
            )
        )
        val snapshot = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(15, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.tiktok.android"
        )
        val decision = engine.evaluate(listOf(profile), snapshot)
        assertTrue("Immediate profile must block target app when enabled", decision is BlockDecision.Blocked)
    }

    @Test
    fun webFiltering_blocksBlockedWebsite_andRespectsScheduleAndAllowlist() {
        val profile = Profile(
            id = "web_profile",
            name = "Web Filter",
            isEnabled = true,
            targetWebsites = listOf(
                TargetWebsite(domainPattern = "facebook.com", ruleType = WebsiteRuleType.EXACT_DOMAIN),
                TargetWebsite(domainPattern = "developer.android.com", ruleType = WebsiteRuleType.EXACT_DOMAIN, isAllowlist = true)
            ),
            schedules = listOf(
                Schedule(profileId = "web_profile", daysOfWeekMask = 127, startTimeSeconds = 9 * 3600, endTimeSeconds = 17 * 3600)
            )
        )

        // 1. In browser at 14:00 (during schedule) opening facebook.com -> Blocked
        val snapshotBlockedWeb = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(14, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.android.chrome",
            activeBrowserUrl = "https://facebook.com/feed"
        )
        val decisionBlocked = engine.evaluate(listOf(profile), snapshotBlockedWeb)
        assertTrue("Facebook should be blocked during active profile schedule", decisionBlocked is BlockDecision.Blocked)
        assertEquals("facebook.com", ((decisionBlocked as BlockDecision.Blocked).reason as BlockReason.DomainBlock).domain)

        // 2. Allowlisted URL -> Allowed
        val snapshotAllowedWeb = snapshotBlockedWeb.copy(activeBrowserUrl = "https://developer.android.com/reference")
        val decisionAllowed = engine.evaluate(listOf(profile), snapshotAllowedWeb)
        assertTrue("developer.android.com must be allowed by allowlist", decisionAllowed is BlockDecision.Allowed)

        // 3. Outside schedule (19:00) -> Allowed
        val snapshotEveningWeb = snapshotBlockedWeb.copy(localTime = LocalTime.of(19, 0))
        val decisionEvening = engine.evaluate(listOf(profile), snapshotEveningWeb)
        assertTrue("Facebook should be allowed outside scheduled hours", decisionEvening is BlockDecision.Allowed)
    }
}
