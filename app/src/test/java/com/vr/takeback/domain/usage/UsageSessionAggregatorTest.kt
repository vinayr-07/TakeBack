package com.vr.takeback.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageSessionAggregatorTest {

    private val launchableApps = setOf("com.instagram.android", "com.google.android.youtube", "com.android.chrome")
    private val isLaunchable: (String) -> Boolean = { it in launchableApps }

    @Test
    fun singleApp_singleActivity_createsCleanSession() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        val events = listOf(
            RawUsageEvent("com.instagram.android", "MainActivity", 110000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent("com.instagram.android", "MainActivity", 170000L, NormalizedEventType.ACTIVITY_PAUSED),
            RawUsageEvent("com.instagram.android", "MainActivity", 171000L, NormalizedEventType.ACTIVITY_STOPPED)
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
        assertEquals(110000L, session.startEpochMillis)
        assertEquals(170000L, session.endEpochMillis)
        assertEquals(60000L, session.durationMillis)
    }

    @Test
    fun multipleActivities_sameApp_mergesOverlappingIntervalsWithoutDoubleCounting() {
        val startOfDay = 100000L
        val endOfDay = 500000L

        // Activity A resumes at 110000.
        // User navigates to Activity B at 130000: Activity B resumes at 130000, then Activity A pauses at 130500.
        // Activity B pauses at 170000.
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
    fun deviceShutdown_discardsExtensionAcrossShutdown() {
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
            isUserLaunchableApp = isLaunchable
        )

        assertEquals(1, sessions.size)
        val session = sessions.first()
        assertEquals(120000L, session.startEpochMillis)
        assertEquals(140000L, session.endEpochMillis)
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
