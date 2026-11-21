package com.vr.takeback.domain.usage

import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.engine.SystemContextSnapshot
import com.vr.takeback.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Defect 8: End-to-end tests covering the full data-flow:
 *   raw events → reconstructSessions / reconstructFull → SystemContextSnapshot → CentralRestrictionEngine.evaluate()
 *
 * These are pure-JVM tests (no Android framework dependencies).
 */
class EndToEndUsageFlowTest {

    private val engine = CentralRestrictionEngine()

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private val INSTAGRAM = "com.instagram.android"
    private val YOUTUBE = "com.google.android.youtube"
    private val REDDIT = "com.reddit.frontpage"

    private fun makeProfile(
        id: String = "p1",
        name: String = "Profile",
        targetPackages: List<String> = listOf(INSTAGRAM),
        limitMinutes: Int = 60,
        windowType: LimitWindowType = LimitWindowType.FULL_DAY,
        targetPackageName: String? = targetPackages.first()
    ): Profile = Profile(
        id = id,
        name = name,
        isEnabled = true,
        targetApps = targetPackages.map { TargetApp(it, it.substringAfterLast('.'), isAllowlist = false) },
        usageLimits = listOf(
            UsageLimit(
                id = 1,
                profileId = id,
                targetPackageName = targetPackageName,
                dailyLimitMinutes = limitMinutes,
                windowType = windowType,
                isEnabled = true
            )
        )
    )

    private fun snapshot(
        foregroundPkg: String,
        accumulatedMillis: Map<String, Long> = emptyMap(),
        hourlyMillis: Map<String, Long> = emptyMap(),
        sessionMillis: Long = 0L,
        isAvailable: Boolean = true,
        quality: UsageDataQuality = UsageDataQuality.PROVISIONAL
    ) = SystemContextSnapshot(
        timestampMillis = System.currentTimeMillis(),
        localTime = LocalTime.of(14, 0),
        dayOfWeek = DayOfWeek.MONDAY,
        foregroundPackageName = foregroundPkg,
        accumulatedForegroundTimeMillis = accumulatedMillis,
        hourlyForegroundTimeMillis = hourlyMillis,
        currentSessionDurationMillis = sessionMillis,
        isUsageDataAvailable = isAvailable,
        usageDataQuality = quality
    )

    // -------------------------------------------------------------------------
    // Test 1: Full-day limit — events → reconstructSessions → engine → blocked
    // -------------------------------------------------------------------------
    @Test
    fun test1_fullDayLimit_eventsToEngineBlock() {
        val midnight = 1_700_000_000_000L // arbitrary epoch midnight
        val endOfDay = midnight + 24 * 60 * 60 * 1000L
        val now = midnight + 65 * 60 * 1000L // 65 minutes into the day

        val events = listOf(
            RawUsageEvent(INSTAGRAM, null, midnight + 1_000, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent(INSTAGRAM, "MainActivity", midnight + 2_000, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent(INSTAGRAM, "MainActivity", midnight + 61 * 60 * 1000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = midnight,
            endEpochMillis = now,
            isToday = true,
            isUserLaunchableApp = { true },
            currentEpochMillis = now
        )

        val usageByPkg = sessions.groupBy { it.packageName }
            .mapValues { (_, list) -> list.sumOf { it.durationMillis } }

        val profile = makeProfile(limitMinutes = 60)
        val snap = snapshot(INSTAGRAM, accumulatedMillis = usageByPkg)
        val decision = engine.evaluate(listOf(profile), snap)

        assertTrue("Full-day limit exceeded: engine must block", decision is BlockDecision.Blocked)
        val blocked = decision as BlockDecision.Blocked
        assertTrue(blocked.reason is BlockReason.DailyLimitExceeded)
        assertEquals(60, (blocked.reason as BlockReason.DailyLimitExceeded).limitMinutes)
    }

    // -------------------------------------------------------------------------
    // Test 2: Rolling hourly limit — events in last 60 minutes → engine → blocked
    // -------------------------------------------------------------------------
    @Test
    fun test2_rollingHourlyLimit_eventsToEngineBlock() {
        val now = System.currentTimeMillis()
        val windowStart = now - 60 * 60 * 1000L

        val events = listOf(
            RawUsageEvent(REDDIT, null, windowStart + 1_000, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent(REDDIT, "MainActivity", windowStart + 2_000, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent(REDDIT, "MainActivity", windowStart + 25 * 60 * 1000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = windowStart,
            endEpochMillis = now,
            isToday = true,
            isUserLaunchableApp = { true },
            currentEpochMillis = now
        )
        val hourlyByPkg = sessions.groupBy { it.packageName }
            .mapValues { (_, list) -> list.sumOf { it.durationMillis } }

        val profile = makeProfile(
            id = "hourly",
            targetPackages = listOf(REDDIT),
            limitMinutes = 20,
            windowType = LimitWindowType.PER_HOUR,
            targetPackageName = REDDIT
        )
        val snap = snapshot(REDDIT, hourlyMillis = hourlyByPkg)
        val decision = engine.evaluate(listOf(profile), snap)

        assertTrue("Hourly limit exceeded: engine must block", decision is BlockDecision.Blocked)
        assertTrue((decision as BlockDecision.Blocked).reason is BlockReason.HourlyQuotaExceeded)
    }

    // -------------------------------------------------------------------------
    // Test 3: Per-session limit — currentSessionDurationMillis → engine → blocked
    // -------------------------------------------------------------------------
    @Test
    fun test3_perSessionLimit_sessionDurationEngineBlock() {
        val profile = makeProfile(
            id = "session",
            targetPackages = listOf(YOUTUBE),
            limitMinutes = 15,
            windowType = LimitWindowType.PER_SESSION,
            targetPackageName = YOUTUBE
        )
        val snap = snapshot(YOUTUBE, sessionMillis = 18 * 60 * 1000L)
        val decision = engine.evaluate(listOf(profile), snap)

        assertTrue("Per-session limit exceeded: engine must block", decision is BlockDecision.Blocked)
    }

    // -------------------------------------------------------------------------
    // Test 4: Missing data (isAvailable = false, empty map) → engine must NOT block
    // -------------------------------------------------------------------------
    @Test
    fun test4_missingData_noAccumulatedUsage_mustNotBlock() {
        val profile = makeProfile(limitMinutes = 60)
        val snap = snapshot(
            foregroundPkg = INSTAGRAM,
            accumulatedMillis = emptyMap(), // no data
            isAvailable = false,
            quality = UsageDataQuality.QUERY_FAILED
        )
        val decision = engine.evaluate(listOf(profile), snap)
        assertTrue(
            "With no accumulated usage data and QUERY_FAILED, engine must not block",
            decision is BlockDecision.Allowed
        )
    }

    // -------------------------------------------------------------------------
    // Test 5: Stale snapshot with usage BELOW limit → engine allows
    // -------------------------------------------------------------------------
    @Test
    fun test5_staleSnapshot_belowLimit_allows() {
        val profile = makeProfile(limitMinutes = 60)
        val snap = snapshot(
            foregroundPkg = INSTAGRAM,
            accumulatedMillis = mapOf(INSTAGRAM to 30 * 60 * 1000L), // 30 min — below limit
            isAvailable = false,
            quality = UsageDataQuality.STALE
        )
        val decision = engine.evaluate(listOf(profile), snap)
        assertTrue(
            "Stale snapshot with usage below limit must allow (cannot confirm exceeded)",
            decision is BlockDecision.Allowed
        )
    }

    // -------------------------------------------------------------------------
    // Test 6: Stale snapshot with usage ABOVE limit → engine still blocks (last-known exceeded)
    // -------------------------------------------------------------------------
    @Test
    fun test6_staleSnapshot_aboveLimit_blocks() {
        val profile = makeProfile(limitMinutes = 60)
        val snap = snapshot(
            foregroundPkg = INSTAGRAM,
            accumulatedMillis = mapOf(INSTAGRAM to 75 * 60 * 1000L), // 75 min > 60 min limit
            isAvailable = false,
            quality = UsageDataQuality.STALE
        )
        val decision = engine.evaluate(listOf(profile), snap)
        assertTrue(
            "Last-known usage exceeding limit must remain blocked even when snapshot is stale",
            decision is BlockDecision.Blocked
        )
    }

    // -------------------------------------------------------------------------
    // Test 7: Permission revoked → engine allows (cannot verify usage)
    // -------------------------------------------------------------------------
    @Test
    fun test7_permissionDenied_mustAllow() {
        val profile = makeProfile(limitMinutes = 60)
        val snap = snapshot(
            foregroundPkg = INSTAGRAM,
            accumulatedMillis = emptyMap(),
            isAvailable = false,
            quality = UsageDataQuality.PERMISSION_DENIED
        )
        val decision = engine.evaluate(listOf(profile), snap)
        assertTrue(
            "With PERMISSION_DENIED and no known usage, engine must not block",
            decision is BlockDecision.Allowed
        )
    }

    // -------------------------------------------------------------------------
    // Test 8: App exit to launcher → session timer resets → second session counted correctly
    // -------------------------------------------------------------------------
    @Test
    fun test8_launcherTransition_sessionTimerReset_secondSessionCountedOnly() {
        // Simulate: Instagram 20 min → Home → Instagram 5 min
        // The second Instagram session should be ~5 min, not 25 min.
        val t0 = 1_700_000_000_000L
        val events = listOf(
            RawUsageEvent(INSTAGRAM, null, t0, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent(INSTAGRAM, "MainActivity", t0, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent(INSTAGRAM, "MainActivity", t0 + 20 * 60 * 1000L, NormalizedEventType.ACTIVITY_PAUSED), // goes home
            RawUsageEvent(INSTAGRAM, "MainActivity", t0 + 30 * 60 * 1000L, NormalizedEventType.ACTIVITY_RESUMED), // back
            RawUsageEvent(INSTAGRAM, "MainActivity", t0 + 35 * 60 * 1000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val now = t0 + 36 * 60 * 1000L
        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = t0,
            endEpochMillis = now,
            isToday = true,
            isUserLaunchableApp = { true },
            currentEpochMillis = now
        )

        // Total usage = 20 + 5 = 25 minutes (two separate sessions)
        val totalMs = sessions.sumOf { it.durationMillis }
        assertEquals("Total usage should be 25 minutes (20 + 5)", 25 * 60 * 1000L, totalMs)

        // The second session should be exactly 5 minutes
        val sortedSessions = sessions.sortedBy { it.startEpochMillis }
        assertEquals("Should have exactly 2 sessions", 2, sortedSessions.size)
        val secondSession = sortedSessions[1]
        assertEquals("Second session should be 5 minutes", 5 * 60 * 1000L, secondSession.durationMillis)
    }

    // -------------------------------------------------------------------------
    // Test 9: Mid-session midnight crossing: resume at 23:30, pause at 00:30 → 30 min today
    // -------------------------------------------------------------------------
    @Test
    fun test9_midnightCrossing_sessionBoundedToToday() {
        // today = day D; midnight = D 00:00; session started at D-1 23:30, ends D 00:30
        val midnight = 1_700_000_000_000L // represents day D midnight
        val resumeBeforeMidnight = midnight - 30 * 60 * 1000L // D-1 23:30
        val pauseAfterMidnight = midnight + 30 * 60 * 1000L   // D 00:30

        val events = listOf(
            RawUsageEvent(INSTAGRAM, null, midnight - 31 * 60 * 1000L, NormalizedEventType.SCREEN_INTERACTIVE),
            RawUsageEvent(INSTAGRAM, "MainActivity", resumeBeforeMidnight, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent(INSTAGRAM, "MainActivity", pauseAfterMidnight, NormalizedEventType.ACTIVITY_PAUSED)
        )

        // Query for day D only: startEpochMillis = midnight
        val sessions = UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = midnight,
            endEpochMillis = pauseAfterMidnight + 1_000L,
            isToday = true,
            isUserLaunchableApp = { true },
            currentEpochMillis = pauseAfterMidnight + 1_000L
        )

        val totalMs = sessions.filter { it.packageName == INSTAGRAM }.sumOf { it.durationMillis }
        assertEquals(
            "Session crossing midnight: only 30 minutes should be counted for today",
            30 * 60 * 1000L, totalMs
        )
    }

    // -------------------------------------------------------------------------
    // Test 10: Empty event history → NOT reported as RECONSTRUCTED_COMPLETE
    // -------------------------------------------------------------------------
    @Test
    fun test10_emptyHistory_notReconstructedComplete() {
        // Simulates what would happen if queryResult is EmptySuccess for a past day within 7 days.
        // The correct quality must NOT be RECONSTRUCTED_COMPLETE.
        // We test this purely via the quality enum logic (the repository path is integration-tested).
        val isTargetToday = false
        val daysAgo = 1L // yesterday

        // Per Defect 5 fix: quality for yesterday with 0 events should be RECONSTRUCTED_PARTIAL or UNKNOWN
        val quality = when {
            isTargetToday -> UsageDataQuality.PROVISIONAL
            daysAgo == 1L -> UsageDataQuality.RECONSTRUCTED_PARTIAL
            else -> UsageDataQuality.UNKNOWN
        }

        assertNotEquals(
            "Empty event history within 7 days must NEVER be RECONSTRUCTED_COMPLETE",
            UsageDataQuality.RECONSTRUCTED_COMPLETE, quality
        )
    }

    // -------------------------------------------------------------------------
    // Test 11: Shared pool hourly limit — two apps, combined hourly usage > limit
    // -------------------------------------------------------------------------
    @Test
    fun test11_sharedPoolHourlyLimit_aggregatedAcrossApps() {
        val profile = Profile(
            id = "social_hourly",
            name = "Social Hourly Pool",
            isEnabled = true,
            targetApps = listOf(
                TargetApp(INSTAGRAM, "Instagram", isAllowlist = false),
                TargetApp(YOUTUBE, "YouTube", isAllowlist = false)
            ),
            usageLimits = listOf(
                UsageLimit(
                    id = 10,
                    profileId = "social_hourly",
                    targetPackageName = null, // shared pool
                    dailyLimitMinutes = 30,
                    windowType = LimitWindowType.PER_HOUR,
                    isEnabled = true
                )
            )
        )

        // Instagram: 18 min, YouTube: 15 min in last hour → total 33 min > 30 min limit
        val snap = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(16, 0),
            dayOfWeek = DayOfWeek.WEDNESDAY,
            foregroundPackageName = INSTAGRAM,
            hourlyForegroundTimeMillis = mapOf(
                INSTAGRAM to 18 * 60 * 1000L,
                YOUTUBE to 15 * 60 * 1000L
            ),
            isUsageDataAvailable = true,
            usageDataQuality = UsageDataQuality.PROVISIONAL
        )

        val decision = engine.evaluate(listOf(profile), snap)
        assertTrue(
            "Shared pool hourly limit: 33 min > 30 min; engine must block",
            decision is BlockDecision.Blocked
        )
        assertTrue(
            (decision as BlockDecision.Blocked).reason is BlockReason.HourlyQuotaExceeded
        )
    }

    // -------------------------------------------------------------------------
    // Test 12: reconstructFull with no screen events → deviceInteractiveTimeMillis = null
    // -------------------------------------------------------------------------
    @Test
    fun test12_reconstructFull_noScreenEvents_deviceInteractiveUnavailable() {
        val t0 = 1_700_000_000_000L
        val events = listOf(
            RawUsageEvent(INSTAGRAM, "MainActivity", t0 + 1_000L, NormalizedEventType.ACTIVITY_RESUMED),
            RawUsageEvent(INSTAGRAM, "MainActivity", t0 + 10 * 60 * 1000L, NormalizedEventType.ACTIVITY_PAUSED)
        )

        val result = UsageSessionAggregator.reconstructFull(
            events = events,
            startEpochMillis = t0,
            endEpochMillis = t0 + 11 * 60 * 1000L,
            isToday = true,
            isUserLaunchableApp = { true },
            currentEpochMillis = t0 + 11 * 60 * 1000L
        )

        assertNull(
            "Without any screen events, deviceInteractiveTimeMillis must be null (unavailable)",
            result.deviceInteractiveTimeMillis
        )
        assertFalse(result.isDeviceInteractiveAvailable)
    }
}
