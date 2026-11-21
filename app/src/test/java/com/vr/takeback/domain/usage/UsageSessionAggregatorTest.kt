package com.vr.takeback.domain.usage

import org.junit.Assert.assertEquals
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
    fun singleApp_openedMultipleTimes_producesSeparateSessions() {
        val startOfDay = 100000L
        val endOfDay = 5000000L

        val events = listOf(
            // Session 1: 5 minutes
            RawUsageEvent("com.instagram.android", "MainActivity", 200000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", 500000L, NormalizedEventType.ACTIVITY_PAUSED),

            // Session 2: 10 minutes (after a 10 min break)
            RawUsageEvent("com.instagram.android", "MainActivity", 1100000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", 1700000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals("Should produce 2 discrete sessions", 2, sessions.size)
        assertEquals(300000L, sessions[0].durationMillis)
        assertEquals(600000L, sessions[1].durationMillis)
    }

    @Test
    fun sequentialApps_packageHandoff_boundsPreviousAppAtNewAppResumeTime() {
        val startOfDay = 100000L
        val endOfDay = 5000000L

        // Instagram opens at 200000.
        // User opens Chrome at 300000 without waiting for Instagram's pause event.
        val events = listOf(
            RawUsageEvent("com.instagram.android", "MainActivity", 200000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.android.chrome", "Main", 300000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.android.chrome", "Main", 450000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertEquals("Both apps should be recorded with clean boundary handoff", 2, sessions.size)
        val insta = sessions.find { it.packageName == "com.instagram.android" }!!
        val chrome = sessions.find { it.packageName == "com.android.chrome" }!!

        assertEquals(200000L, insta.startEpochMillis)
        assertEquals(300000L, insta.endEpochMillis)
        assertEquals(100000L, insta.durationMillis)

        assertEquals(300000L, chrome.startEpochMillis)
        assertEquals(450000L, chrome.endEpochMillis)
        assertEquals(150000L, chrome.durationMillis)
    }

    @Test
    fun duplicateActivityResumed_doesNotOverwriteOriginalStartTime() {
        val startOfDay = 100000L
        val endOfDay = 5000000L

        // App resumes at 200000.
        // Secondary resume event fires at 250000 (e.g. fragment switch, window focus re-gain).
        // App pauses at 300000.
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
        assertEquals("Duration must be exact span without duplicate addition (60s)", 60000L, session.durationMillis)
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
            isToday = false, // Historical date
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
    fun computeDeviceScreenOnTimeMillis_mergesOverlappingIntervalsAcrossPackages() {
        val sessions = listOf(
            // App A: 10:00 to 10:20 (20 min)
            SessionInterval("com.instagram.android", 100000L, 120000L),
            // App B: 10:10 to 10:30 (20 min in split screen)
            SessionInterval("com.android.chrome", 110000L, 130000L),
            // App C: 11:00 to 11:15 (15 min)
            SessionInterval("com.reddit.frontpage", 200000L, 215000L)
        )

        val totalAppTime = sessions.sumOf { it.durationMillis }
        val screenOnTime = UsageSessionAggregator.computeDeviceScreenOnTimeMillis(sessions)

        // Sum of per-app durations: 20k + 20k + 15k = 55k
        assertEquals(55000L, totalAppTime)

        // True screen-on time: [100000..130000] (30k) + [200000..215000] (15k) = 45k
        assertEquals(45000L, screenOnTime)
    }

    @Test
    fun nonLaunchableApps_areExcludedFromSessions() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        val events = listOf(
            RawUsageEvent("com.sec.android.app.launcher", "Launcher", 110000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.sec.android.app.launcher", "Launcher", 140000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startOfDay,
            endEpochMillis = endOfDay,
            isToday = false,
            isUserLaunchableApp = isLaunchable
        )

        assertTrue("Non-launchable apps must not produce sessions", sessions.isEmpty())
    }
}
