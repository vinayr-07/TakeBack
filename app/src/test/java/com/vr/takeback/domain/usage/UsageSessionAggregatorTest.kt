package com.vr.takeback.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageSessionAggregatorTest {

    private val launchableApps = setOf(
        "com.instagram.android",
        "com.google.android.youtube",
        "com.android.chrome",
        "com.reddit.frontpage"
    )
    private val isLaunchable: (String) -> Boolean = { it in launchableApps }

    @Test
    fun singleApp_continuousTenMinutes_producesExactTenMinuteSession() {
        val startOfDay = 100000L
        val endOfDay = 5000000L
        val tenMinutesMillis = 10 * 60 * 1000L

        val events = listOf(
            RawUsageEvent("com.instagram.android", "MainActivity", 200000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", 200000L + tenMinutesMillis, NormalizedEventType.ACTIVITY_PAUSED),
            RawUsageEvent("com.instagram.android", "MainActivity", 200000L + tenMinutesMillis + 500, NormalizedEventType.ACTIVITY_STOPPED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals(1, sessions.size)
        val session = sessions.first()
        assertEquals("com.instagram.android", session.packageName)
        assertEquals(200000L, session.startEpochMillis)
        assertEquals(200000L + tenMinutesMillis, session.endEpochMillis)
        assertEquals(tenMinutesMillis, session.durationMillis)
    }

    @Test
    fun appSessionLongerThanTwelveHours_isNotTruncated() {
        val startOfDay = 100000L
        val endOfDay = 60000000L
        val fourteenHoursMillis = 14L * 60 * 60 * 1000L // 14 hours continuous

        val events = listOf(
            RawUsageEvent("com.instagram.android", "MainActivity", 200000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", 200000L + fourteenHoursMillis, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals(1, sessions.size)
        val session = sessions.first()
        assertEquals("Fourteen-hour continuous session must NOT be truncated", fourteenHoursMillis, session.durationMillis)
    }

    @Test
    fun multiResume_overlappingForegroundApps_tracksBothAppsIndependentlyWithoutTruncation() {
        val startOfDay = 100000L
        val endOfDay = 5000000L

        // Split-screen: Instagram resumes at 200000 and pauses at 400000.
        // Chrome resumes at 300000 and pauses at 450000.
        val events = listOf(
            RawUsageEvent("com.instagram.android", "MainActivity", 200000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.android.chrome", "Main", 300000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", 400000L, NormalizedEventType.ACTIVITY_PAUSED),
            RawUsageEvent("com.android.chrome", "Main", 450000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals("Both simultaneously resumed apps must be recorded independently", 2, sessions.size)
        val insta = sessions.find { it.packageName == "com.instagram.android" }!!
        val chrome = sessions.find { it.packageName == "com.android.chrome" }!!

        assertEquals(200000L, insta.startEpochMillis)
        assertEquals(400000L, insta.endEpochMillis)
        assertEquals(200000L, insta.durationMillis)

        assertEquals(300000L, chrome.startEpochMillis)
        assertEquals(450000L, chrome.endEpochMillis)
        assertEquals(150000L, chrome.durationMillis)
    }

    @Test
    fun duplicateActivityResumed_doesNotOverwriteOriginalStartTime() {
        val startOfDay = 100000L
        val endOfDay = 5000000L

        val events = listOf(
            RawUsageEvent("com.instagram.android", "MainActivity", 200000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", 250000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", 300000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals(1, sessions.size)
        val session = sessions.first()
        assertEquals("Initial start time 200000 must be preserved", 200000L, session.startEpochMillis)
        assertEquals(300000L, session.endEpochMillis)
        assertEquals("Duration must be full 100s, not truncated to 50s", 100000L, session.durationMillis)
    }

    @Test
    fun keyguardShown_whileScreenRemainsInteractive_doesNotCloseDeviceInteractiveInterval() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        // Screen turns interactive at 120000.
        // Keyguard is shown at 150000 (e.g. timeout lock with screen still on).
        // Screen turns non-interactive at 200000.
        val events = listOf(
            RawUsageEvent("android", null, 80000L, NormalizedEventType.SCREEN_NON_INTERACTIVE), // Lookback: known non-interactive
            RawUsageEvent("android", null, 120000L, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent("android", null, 150000L, NormalizedEventType.KEYGUARD_SHOWN),
            RawUsageEvent("android", null, 200000L, NormalizedEventType.SCREEN_NON_INTERACTIVE)
        )

        val (intervals, hasIncompleteStart) = UsageSessionAggregator.reconstructDeviceInteractiveIntervals(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false
        )

        assertFalse(hasIncompleteStart)
        assertEquals("Should produce 1 continuous interactive interval from 120k to 200k", 1, intervals.size)
        assertEquals(120000L, intervals[0].startEpochMillis)
        assertEquals(200000L, intervals[0].endEpochMillis)
        assertEquals("Duration must be full 80s (KEYGUARD_SHOWN does not terminate screen interactive interval)", 80000L, intervals[0].durationMillis)
    }

    @Test
    fun missingInitialScreenInteractiveEvent_reportsIncompleteStartBoundary_andDoesNotSynthesizeMidnightInterval() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        // No lookback events at all (initial state UNKNOWN).
        // First event observed after midnight is SCREEN_NON_INTERACTIVE at 130000.
        val events = listOf(
            RawUsageEvent("android", null, 130000L, NormalizedEventType.SCREEN_NON_INTERACTIVE)
        )

        val (intervals, hasIncompleteStart) = UsageSessionAggregator.reconstructDeviceInteractiveIntervals(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false
        )

        assertTrue("Initial state was UNKNOWN so hasIncompleteStart must be true", hasIncompleteStart)
        assertEquals("Requirement 2: Must NOT synthesize an unprovable interval from midnight", 0, intervals.size)

        val result = UsageSessionAggregator.reconstructFull(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )
        assertFalse("Measurement must be marked unavailable without guessing", result.isDeviceInteractiveAvailable)
        assertNull("Device interactive duration must be null", result.deviceInteractiveTimeMillis)
        assertTrue(result.hasIncompleteStartBoundary)
    }

    @Test
    fun missingFinalScreenNonInteractiveEvent_onHistoricalDay_reportsIncompleteEndBoundary() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        // App resumes at 450000 on a historical day and has no closing event.
        val events = listOf(
            RawUsageEvent("android", null, 80000L, NormalizedEventType.SCREEN_NON_INTERACTIVE),
            RawUsageEvent("android", null, 400000L, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent("com.instagram.android", "MainActivity", 450000L, NormalizedEventType.ACTIVITY_RESUMED)
        )

        val result = UsageSessionAggregator.reconstructFull(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertTrue("Historical unclosed session crossing boundary must report hasIncompleteEndBoundary = true", result.hasIncompleteEndBoundary)
        assertEquals(50000L, result.totalTrackedAppUsageMillis) // 450k to 500k
    }

    @Test
    fun reconstructFull_noScreenEvents_marksDeviceInteractiveUnavailableWithoutFallback() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        // App session events exist (YouTube for 60s), but NO screen-state events exist in window.
        val events = listOf(
            RawUsageEvent("com.google.android.youtube", "Watch", 120000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.google.android.youtube", "Watch", 180000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val result = UsageSessionAggregator.reconstructFull(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals("App usage is 60s", 60000L, result.totalTrackedAppUsageMillis)
        assertFalse("Device interactive time must be marked unavailable when no screen events exist", result.isDeviceInteractiveAvailable)
        assertNull("Device interactive time must be null (NEVER silently fall back to app session duration)", result.deviceInteractiveTimeMillis)
    }

    @Test
    fun screenNonInteractive_terminatesActiveAppSession() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        val events = listOf(
            RawUsageEvent("com.android.chrome", "TabbedActivity", 120000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("android", null, 150000L, NormalizedEventType.SCREEN_NON_INTERACTIVE),
            RawUsageEvent("android", null, 150500L, NormalizedEventType.KEYGUARD_SHOWN)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals(1, sessions.size)
        val session = sessions.first()
        assertEquals("com.android.chrome", session.packageName)
        assertEquals(120000L, session.startEpochMillis)
        assertEquals(150000L, session.endEpochMillis)
        assertEquals(30000L, session.durationMillis)
    }

    @Test
    fun deviceShutdown_boundsActiveSessionAtShutdownTimestamp() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        val events = listOf(
            RawUsageEvent("com.instagram.android", "MainActivity", 120000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("android", null, 140000L, NormalizedEventType.DEVICE_SHUTDOWN)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = true,
            isUserLaunchableApp = isLaunchable,
            currentEpochMillis = 400000L
        )

        assertEquals(1, sessions.size)
        val session = sessions.first()
        assertEquals(120000L, session.startEpochMillis)
        assertEquals("Must end exactly at shutdown time (140000), not continue to currentEpochMillis", 140000L, session.endEpochMillis)
        assertEquals(20000L, session.durationMillis)
    }

    @Test
    fun lockAndUnlock_whileDisplayRemainsOn_allowsSubsequentAppSessions() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        // Screen turns on at 100k
        // Instagram used from 110k to 120k
        // Lock screen appears at 120k (screen stays on)
        // User unlocks at 130k (KEYGUARD_HIDDEN, screen still on)
        // Instagram resumes at 140k and pauses at 150k
        val events = listOf(
            RawUsageEvent("android", null, 100000L, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent("com.instagram.android", "MainActivity", 110000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("android", null, 120000L, NormalizedEventType.KEYGUARD_SHOWN),
            RawUsageEvent("android", null, 130000L, NormalizedEventType.KEYGUARD_HIDDEN),
            RawUsageEvent("com.instagram.android", "MainActivity", 140000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", 150000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals("Both sessions must be recorded because KEYGUARD_HIDDEN restores ability to track when screen remained interactive", 2, sessions.size)
        assertEquals(110000L, sessions[0].startEpochMillis)
        assertEquals(120000L, sessions[0].endEpochMillis)
        assertEquals(140000L, sessions[1].startEpochMillis)
        assertEquals(150000L, sessions[1].endEpochMillis)
    }

    @Test
    fun lockFollowedByScreenOff_handlesIndependentTransitions() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        val events = listOf(
            RawUsageEvent("android", null, 100000L, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent("com.instagram.android", "MainActivity", 110000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("android", null, 120000L, NormalizedEventType.KEYGUARD_SHOWN),
            RawUsageEvent("android", null, 125000L, NormalizedEventType.SCREEN_NON_INTERACTIVE),
            RawUsageEvent("android", null, 150000L, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent("android", null, 160000L, NormalizedEventType.KEYGUARD_HIDDEN),
            RawUsageEvent("com.instagram.android", "MainActivity", 165000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", 175000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals(2, sessions.size)
        assertEquals(110000L, sessions[0].startEpochMillis)
        assertEquals(120000L, sessions[0].endEpochMillis)
        assertEquals(165000L, sessions[1].startEpochMillis)
        assertEquals(175000L, sessions[1].endEpochMillis)
    }

    @Test
    fun unlockFollowedByAppResume_withoutScreenInteractiveEvent_tracksSession() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        // Screen was turned interactive in lookback
        val events = listOf(
            RawUsageEvent("android", null, 80000L, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent("android", null, 110000L, NormalizedEventType.KEYGUARD_SHOWN),
            RawUsageEvent("android", null, 130000L, NormalizedEventType.KEYGUARD_HIDDEN),
            RawUsageEvent("com.google.android.youtube", "Watch", 140000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.google.android.youtube", "Watch", 180000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals(1, sessions.size)
        assertEquals("com.google.android.youtube", sessions[0].packageName)
        assertEquals(140000L, sessions[0].startEpochMillis)
        assertEquals(180000L, sessions[0].endEpochMillis)
        assertEquals(40000L, sessions[0].durationMillis)
    }

    @Test
    fun duplicateKeyguardTransitions_maintainRobustState() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        val events = listOf(
            RawUsageEvent("android", null, 100000L, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent("android", null, 110000L, NormalizedEventType.KEYGUARD_SHOWN),
            RawUsageEvent("android", null, 112000L, NormalizedEventType.KEYGUARD_SHOWN), // duplicate
            RawUsageEvent("android", null, 120000L, NormalizedEventType.KEYGUARD_HIDDEN),
            RawUsageEvent("android", null, 122000L, NormalizedEventType.KEYGUARD_HIDDEN), // duplicate
            RawUsageEvent("com.android.chrome", "TabbedActivity", 130000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.android.chrome", "TabbedActivity", 140000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals(1, sessions.size)
        assertEquals("com.android.chrome", sessions[0].packageName)
        assertEquals(130000L, sessions[0].startEpochMillis)
        assertEquals(140000L, sessions[0].endEpochMillis)
    }

    @Test
    fun appUsageAcrossMidnight_resumedBeforeMidnight_retainsUsageAfterMidnight() {
        // startOfDay = 1000000L (Midnight)
        val midnight = 1000000L
        val endOfDay = midnight + 86400000L
        val thirtyMinutesMillis = 30 * 60 * 1000L

        // App resumed at 23:30 (midnight - 30m)
        // Screen is interactive
        // App pauses at 00:30 (midnight + 30m)
        val events = listOf(
            RawUsageEvent("android", null, midnight - 40 * 60 * 1000L, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent("com.instagram.android", "MainActivity", midnight - thirtyMinutesMillis, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", midnight + thirtyMinutesMillis, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = midnight,
            endEpochMillis = endOfDay,
            isToday = true,
            isUserLaunchableApp = isLaunchable,
            currentEpochMillis = midnight + 60 * 60 * 1000L
        )

        assertEquals("Session active across midnight must be captured for today", 1, sessions.size)
        val session = sessions.first()
        assertEquals("Start time for today's quota must be bounded at midnight (startOfDay)", midnight, session.startEpochMillis)
        assertEquals("End time must be 00:30", midnight + thirtyMinutesMillis, session.endEpochMillis)
        assertEquals("Session duration after midnight must be exactly 30 minutes", thirtyMinutesMillis, session.durationMillis)
    }

    @Test
    fun appSession_whereOriginalResumeLiesOutsideLookback_reportsIncompleteBoundary() {
        // App was resumed before lookback, so only a pause event exists after midnight
        val midnight = 1000000L
        val endOfDay = midnight + 86400000L

        val events = listOf(
            RawUsageEvent("android", null, midnight + 10000L, NormalizedEventType.SCREEN_INTERACTIVE),
            // No ACTIVITY_RESUMED in lookback
            RawUsageEvent("com.instagram.android", "MainActivity", midnight + 30000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = midnight,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        // Without a preceding resume event in lookback, an orphan pause cannot fabricate an arbitrary session
        assertEquals("Orphan pause without preceding resume must not fabricate an arbitrary duration", 0, sessions.size)
    }
}
