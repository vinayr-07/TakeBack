package com.vr.takeback.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun singleApp_continuousFiveHours_doesNotTruncateSession() {
        val startOfDay = 100000L
        val endOfDay = 30000000L
        val fiveHoursMillis = 5L * 60 * 60 * 1000L // 5 hours continuous

        val events = listOf(
            RawUsageEvent("com.instagram.android", "MainActivity", 200000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", 200000L + fiveHoursMillis, NormalizedEventType.ACTIVITY_PAUSED)
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
        assertEquals("Five-hour session must NOT be capped at 4 hours", fiveHoursMillis, session.durationMillis)
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
    fun multipleActivities_sameApp_mergesOverlappingIntervalsWithoutDoubleCounting() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        val events = listOf(
            RawUsageEvent("com.google.android.youtube", "HomeActivity", 110000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.google.android.youtube", "WatchActivity", 130000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.google.android.youtube", "HomeActivity", 130500L, NormalizedEventType.ACTIVITY_PAUSED),
            RawUsageEvent("com.google.android.youtube", "HomeActivity", 131000L, NormalizedEventType.ACTIVITY_STOPPED),
            RawUsageEvent("com.google.android.youtube", "WatchActivity", 170000L, NormalizedEventType.ACTIVITY_PAUSED),
            RawUsageEvent("com.google.android.youtube", "WatchActivity", 171000L, NormalizedEventType.ACTIVITY_STOPPED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals("Overlapping activities in same app must merge into 1 contiguous session", 1, sessions.size)
        val session = sessions.first()
        assertEquals("com.google.android.youtube", session.packageName)
        assertEquals(110000L, session.startEpochMillis)
        assertEquals(170000L, session.endEpochMillis)
        assertEquals(60000L, session.durationMillis)
    }

    @Test
    fun screenNonInteractive_terminatesActiveSession() {
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
    fun deviceInteractiveIntervals_tracksDisplayActivityIndependentlyOfPackages() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        // Screen unlocks at 120000, user is on home launcher (non-launchable app),
        // then turns screen off at 180000.
        // User unlocks again at 220000 and locks at 250000.
        val events = listOf(
            RawUsageEvent("android", null, 120000L, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent("android", null, 120500L, NormalizedEventType.KEYGUARD_HIDDEN),
            RawUsageEvent("com.sec.android.app.launcher", "Launcher", 121000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("android", null, 180000L, NormalizedEventType.SCREEN_NON_INTERACTIVE),
            RawUsageEvent("android", null, 180500L, NormalizedEventType.KEYGUARD_SHOWN),
            RawUsageEvent("android", null, 220000L, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent("android", null, 250000L, NormalizedEventType.SCREEN_NON_INTERACTIVE)
        )

        val intervals = UsageSessionAggregator.reconstructDeviceInteractiveIntervals(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false
        )

        assertEquals("Should produce 2 interactive intervals", 2, intervals.size)
        assertEquals(60000L, intervals[0].durationMillis) // 120k to 180k
        assertEquals(30000L, intervals[1].durationMillis) // 220k to 250k

        val totalInteractive = UsageSessionAggregator.computeTotalInteractiveDuration(intervals)
        assertEquals("Total device interactive time must be 90s regardless of launcher exclusion", 90000L, totalInteractive)
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
    fun midnightCrossing_clipsToStartOfDay() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        // App started 20s before midnight
        val events = listOf(
            RawUsageEvent("com.instagram.android", "MainActivity", 80000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", 130000L, NormalizedEventType.ACTIVITY_PAUSED)
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
        assertEquals("Session start must be clipped to start of day (100000)", startOfDay, session.startEpochMillis)
        assertEquals(130000L, session.endEpochMillis)
        assertEquals(30000L, session.durationMillis)
    }

    @Test
    fun historicalDate_sessionCrossingMidnight_boundsAtEndOfDay() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        // App resumes at 450000 and is still running when day ends at 500000
        val events = listOf(
            RawUsageEvent("com.instagram.android", "MainActivity", 450000L, NormalizedEventType.ACTIVITY_RESUMED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable,
            currentEpochMillis = 900000L
        )

        assertEquals(1, sessions.size)
        val session = sessions.first()
        assertEquals(450000L, session.startEpochMillis)
        assertEquals("Historical unclosed session must cleanly bound at end of day (500000)", endOfDay, session.endEpochMillis)
        assertEquals(50000L, session.durationMillis)
    }

    @Test
    fun reconstructFull_detectsBoundaryQualityAndSeparateMetrics() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        // App A and App B run in split-screen (overlap from 120000 to 140000)
        // Screen is active from 110000 to 150000 (includes 10s of home screen before and after)
        val events = listOf(
            RawUsageEvent("android", null, 110000L, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent("com.instagram.android", "MainActivity", 115000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.android.chrome", "Main", 120000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", 140000L, NormalizedEventType.ACTIVITY_PAUSED),
            RawUsageEvent("com.android.chrome", "Main", 145000L, NormalizedEventType.ACTIVITY_PAUSED),
            RawUsageEvent("android", null, 150000L, NormalizedEventType.SCREEN_NON_INTERACTIVE)
        )

        val result = UsageSessionAggregator.reconstructFull(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals("Instagram: 25s, Chrome: 25s => Total app usage = 50s", 50000L, result.totalTrackedAppUsageMillis)
        assertEquals("Device screen active from 110k to 150k => 40s", 40000L, result.deviceInteractiveTimeMillis)
        assertFalse(result.hasIncompleteStartBoundary)
    }
}
