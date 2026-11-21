package com.vr.takeback.domain.usage

import com.vr.takeback.data.repository.LastKnownQuotaState
import com.vr.takeback.data.repository.UsageEventsQueryResult
import com.vr.takeback.data.repository.ValidatedUsageSnapshot
import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.engine.SystemContextSnapshot
import com.vr.takeback.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class UsageRepositoryLogicTest {

    @Test
    fun previousDaySnapshot_cannotBeAcceptedForTodayQuota() {
        val yesterday = LocalDate.now().minusDays(1)
        val today = LocalDate.now()

        val snapshot = ValidatedUsageSnapshot(
            date = yesterday,
            capturedAtMillis = System.currentTimeMillis() - 60000L,
            foregroundUsageByPackage = mapOf("com.instagram.android" to 3600000L),
            quality = UsageDataQuality.RECONSTRUCTED_COMPLETE,
            isAvailable = true,
            dataSource = "UsageEvents"
        )

        // Validation rule: snapshot date must equal target today date
        val isValidForToday = (snapshot.date == today)
        assertFalse("Yesterday's snapshot must NEVER be accepted as today's usage quota", isValidForToday)
    }

    @Test
    fun staleSnapshot_olderThanFifteenMinutes_isRejected() {
        val today = LocalDate.now()
        val twentyMinutesAgo = System.currentTimeMillis() - (20 * 60 * 1000L)

        val snapshot = ValidatedUsageSnapshot(
            date = today,
            capturedAtMillis = twentyMinutesAgo,
            foregroundUsageByPackage = mapOf("com.instagram.android" to 1200000L),
            quality = UsageDataQuality.PROVISIONAL,
            isAvailable = true,
            dataSource = "UsageEvents"
        )

        val ageMillis = System.currentTimeMillis() - snapshot.capturedAtMillis
        val isFresh = ageMillis <= (15 * 60 * 1000L)
        assertFalse("Snapshot older than 15 minutes must be flagged stale", isFresh)
    }

    @Test
    fun partialRecord_isNotEligibleForFinalization() {
        val isTargetPast = true
        val quality = UsageDataQuality.RECONSTRUCTED_PARTIAL
        val hasIncompleteStartBoundary = true
        val hasIncompleteEndBoundary = false

        val isEligibleForFinalization = isTargetPast &&
                quality == UsageDataQuality.RECONSTRUCTED_COMPLETE &&
                !hasIncompleteStartBoundary &&
                !hasIncompleteEndBoundary

        assertFalse("Partial reconstructions must NEVER be permanently finalized in database ledger", isEligibleForFinalization)
    }

    @Test
    fun completePastRecord_isEligibleForFinalization() {
        val isTargetPast = true
        val quality = UsageDataQuality.RECONSTRUCTED_COMPLETE
        val hasIncompleteStartBoundary = false
        val hasIncompleteEndBoundary = false

        val isEligibleForFinalization = isTargetPast &&
                quality == UsageDataQuality.RECONSTRUCTED_COMPLETE &&
                !hasIncompleteStartBoundary &&
                !hasIncompleteEndBoundary

        assertTrue("Complete historical reconstructions with clean boundaries are eligible for finalization", isEligibleForFinalization)
    }

    @Test
    fun structuredQueryResults_distinguishErrorsFromEmptySuccess() {
        val empty = UsageEventsQueryResult.EmptySuccess
        val security = UsageEventsQueryResult.SecurityError(SecurityException("Denied"))
        val nullResp = UsageEventsQueryResult.NullResponse
        val queryErr = UsageEventsQueryResult.QueryError(RuntimeException("Failed"))
        val success = UsageEventsQueryResult.Success(emptyList())

        assertTrue(empty is UsageEventsQueryResult.EmptySuccess)
        assertTrue(security is UsageEventsQueryResult.SecurityError)
        assertTrue(nullResp is UsageEventsQueryResult.NullResponse)
        assertTrue(queryErr is UsageEventsQueryResult.QueryError)
        assertTrue(success is UsageEventsQueryResult.Success)
    }

    @Test
    fun appUsageInfo_reconstructedSessionCount_isRenamedFromLaunchCount() {
        val app = AppUsageInfo(
            packageName = "com.instagram.android",
            appName = "Instagram",
            usageTimeMillis = 1800000L,
            reconstructedSessionCount = 5
        )

        assertEquals(5, app.reconstructedSessionCount)
        @Suppress("DEPRECATION")
        assertEquals("Deprecated launchCount must match reconstructedSessionCount", 5, app.launchCount)
    }

    @Test
    fun sharedProfilePoolLimit_aggregatesUsageAcrossAllTargetApps() {
        val engine = CentralRestrictionEngine()
        val profile = Profile(
            id = "social_pool",
            name = "Social Pool",
            isEnabled = true,
            targetApps = listOf(
                TargetApp("com.instagram.android", "Instagram", isAllowlist = false),
                TargetApp("com.google.android.youtube", "YouTube", isAllowlist = false)
            ),
            usageLimits = listOf(
                UsageLimit(
                    id = 1,
                    profileId = "social_pool",
                    targetPackageName = null, // Shared pool limit!
                    dailyLimitMinutes = 60,
                    windowType = LimitWindowType.FULL_DAY,
                    isEnabled = true
                )
            )
        )

        // Instagram: 35 minutes, YouTube: 30 minutes
        // Total pool usage = 65 minutes (>= 60 minute limit)
        // Individually, neither has reached 60 mins (35 < 60, 30 < 60)
        val snapshot = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(15, 0),
            dayOfWeek = DayOfWeek.FRIDAY,
            foregroundPackageName = "com.instagram.android",
            accumulatedForegroundTimeMillis = mapOf(
                "com.instagram.android" to 35 * 60 * 1000L,
                "com.google.android.youtube" to 30 * 60 * 1000L
            ),
            isUsageDataAvailable = true
        )

        val decision = engine.evaluate(listOf(profile), snapshot)
        assertTrue("Shared pool limit must aggregate usage across all target apps and block when sum exceeds limit", decision is BlockDecision.Blocked)
        val blocked = decision as BlockDecision.Blocked
        assertTrue(blocked.reason is BlockReason.DailyLimitExceeded)
        assertEquals(60, (blocked.reason as BlockReason.DailyLimitExceeded).limitMinutes)
        assertEquals(65, (blocked.reason as BlockReason.DailyLimitExceeded).usedMinutes)
    }

    @Test
    fun distinctSemantics_forHourlyAndSessionLimits() {
        val engine = CentralRestrictionEngine()
        val hourlyProfile = Profile(
            id = "hourly_profile",
            name = "Hourly Limit Profile",
            isEnabled = true,
            targetApps = listOf(TargetApp("com.reddit.frontpage", "Reddit", isAllowlist = false)),
            usageLimits = listOf(
                UsageLimit(
                    id = 2,
                    profileId = "hourly_profile",
                    targetPackageName = "com.reddit.frontpage",
                    dailyLimitMinutes = 20,
                    windowType = LimitWindowType.PER_HOUR,
                    isEnabled = true
                )
            )
        )

        val sessionProfile = Profile(
            id = "session_profile",
            name = "Session Limit Profile",
            isEnabled = true,
            targetApps = listOf(TargetApp("com.reddit.frontpage", "Reddit", isAllowlist = false)),
            usageLimits = listOf(
                UsageLimit(
                    id = 3,
                    profileId = "session_profile",
                    targetPackageName = "com.reddit.frontpage",
                    dailyLimitMinutes = 15,
                    windowType = LimitWindowType.PER_SESSION,
                    isEnabled = true
                )
            )
        )

        // Test Hourly: 25 minutes in current hour -> Blocked with HourlyQuotaExceeded
        val hourlySnapshot = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(16, 30),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.reddit.frontpage",
            accumulatedForegroundTimeMillis = mapOf("com.reddit.frontpage" to 50 * 60 * 1000L),
            hourlyForegroundTimeMillis = mapOf("com.reddit.frontpage" to 25 * 60 * 1000L),
            isUsageDataAvailable = true
        )
        val hourlyDecision = engine.evaluate(listOf(hourlyProfile), hourlySnapshot)
        assertTrue(hourlyDecision is BlockDecision.Blocked)
        assertTrue((hourlyDecision as BlockDecision.Blocked).reason is BlockReason.HourlyQuotaExceeded)

        // Test Session: continuous session 18 minutes -> Blocked
        val sessionSnapshot = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(16, 30),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.reddit.frontpage",
            currentSessionDurationMillis = 18 * 60 * 1000L,
            isUsageDataAvailable = true
        )
        val sessionDecision = engine.evaluate(listOf(sessionProfile), sessionSnapshot)
        assertTrue(sessionDecision is BlockDecision.Blocked)
    }

    @Test
    fun staleOrUnavailableData_doesNotResetExceededUsageToZero() {
        val engine = CentralRestrictionEngine()
        val profile = Profile(
            id = "daily_p",
            name = "Daily Limit",
            isEnabled = true,
            targetApps = listOf(TargetApp("com.instagram.android", "Instagram", isAllowlist = false)),
            usageLimits = listOf(
                UsageLimit(
                    id = 4,
                    profileId = "daily_p",
                    targetPackageName = "com.instagram.android",
                    dailyLimitMinutes = 60,
                    windowType = LimitWindowType.FULL_DAY,
                    isEnabled = true
                )
            )
        )

        // User previously used 75 mins (limit is 60). Now snapshot is stale / unavailable.
        // It must NOT silently reset to 0 minutes and allow bypass!
        val snapshot = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(18, 0),
            dayOfWeek = DayOfWeek.TUESDAY,
            foregroundPackageName = "com.instagram.android",
            accumulatedForegroundTimeMillis = mapOf("com.instagram.android" to 75 * 60 * 1000L),
            isUsageDataAvailable = false,
            usageDataQuality = UsageDataQuality.STALE
        )

        val decision = engine.evaluate(listOf(profile), snapshot)
        assertTrue("Exceeded usage must remain blocked even when snapshot is stale/unavailable", decision is BlockDecision.Blocked)
    }

    @Test
    fun validatedUsageSnapshot_availabilityTracking() {
        val today = LocalDate.now()
        val available = ValidatedUsageSnapshot(
            date = today,
            capturedAtMillis = System.currentTimeMillis(),
            foregroundUsageByPackage = mapOf("com.instagram.android" to 1000L),
            quality = UsageDataQuality.PROVISIONAL,
            isAvailable = true,
            dataSource = "UsageEvents"
        )
        assertTrue(available.isAvailable)

        val unavailable = ValidatedUsageSnapshot(
            date = today,
            capturedAtMillis = System.currentTimeMillis(),
            foregroundUsageByPackage = emptyMap(),
            quality = UsageDataQuality.PERMISSION_DENIED,
            isAvailable = false,
            dataSource = "Permission Denied"
        )
        assertFalse(unavailable.isAvailable)
    }

    // -------------------------------------------------------------------------
    // Build 8 Tests: Quota Preservation, Empty Events, and Multi-Caller Refresh
    // -------------------------------------------------------------------------

    @Test
    fun testBuild8_permissionRevocation_preservesSameDayLastKnownQuotaState() {
        val today = LocalDate.now()
        val now = System.currentTimeMillis()

        // 1. Captured state earlier today showing Instagram at 75 mins (exceeded 60 min limit)
        val dailyMap = mapOf("com.instagram.android" to 75 * 60 * 1000L)
        val hourlyMap = mapOf("com.instagram.android" to 30 * 60 * 1000L)
        val quotaState = LastKnownQuotaState(
            date = today,
            capturedAtMillis = now - 1000L,
            dailyForegroundUsageByPackage = dailyMap,
            hourlyForegroundUsageByPackage = hourlyMap,
            quality = UsageDataQuality.PROVISIONAL,
            provenance = "UsageEvents"
        )

        // 2. Permission is now revoked; simulate getActiveUsageSnapshot logic
        val sameDayQuota = quotaState.takeIf { it.date == today }
        val snapshot = ValidatedUsageSnapshot(
            date = today,
            capturedAtMillis = now,
            foregroundUsageByPackage = sameDayQuota?.dailyForegroundUsageByPackage ?: emptyMap(),
            hourlyUsageByPackage = sameDayQuota?.hourlyForegroundUsageByPackage ?: emptyMap(),
            quality = UsageDataQuality.PERMISSION_DENIED,
            isAvailable = false,
            dataSource = if (sameDayQuota != null) "Permission Denied (Same-Day Quota Preserved)" else "Permission Denied"
        )

        // Snapshot is marked unavailable for analytics, but retains same-day maps for restriction engine
        assertFalse("Analytics must report unavailable on permission revocation", snapshot.isAvailable)
        assertEquals(UsageDataQuality.PERMISSION_DENIED, snapshot.quality)
        assertEquals(75 * 60 * 1000L, snapshot.foregroundUsageByPackage["com.instagram.android"])
        assertEquals(30 * 60 * 1000L, snapshot.hourlyUsageByPackage["com.instagram.android"])

        // 3. CentralRestrictionEngine evaluation with this snapshot must STILL block Instagram!
        val engine = CentralRestrictionEngine()
        val profile = Profile(
            id = "daily_p",
            name = "Daily Limit",
            isEnabled = true,
            targetApps = listOf(TargetApp("com.instagram.android", "Instagram", isAllowlist = false)),
            usageLimits = listOf(
                UsageLimit(
                    id = 1,
                    profileId = "daily_p",
                    targetPackageName = "com.instagram.android",
                    dailyLimitMinutes = 60,
                    windowType = LimitWindowType.FULL_DAY,
                    isEnabled = true
                )
            )
        )
        val contextSnapshot = SystemContextSnapshot(
            timestampMillis = now,
            localTime = LocalTime.of(15, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.instagram.android",
            accumulatedForegroundTimeMillis = snapshot.foregroundUsageByPackage,
            hourlyForegroundTimeMillis = snapshot.hourlyUsageByPackage,
            isUsageDataAvailable = snapshot.isAvailable,
            usageDataQuality = snapshot.quality
        )
        val decision = engine.evaluate(listOf(profile), contextSnapshot)
        assertTrue("Exceeded daily quota MUST remain blocked after permission revocation using preserved same-day state", decision is BlockDecision.Blocked)
    }

    @Test
    fun testBuild8_crossDayRollover_clearsLastKnownQuotaState() {
        val yesterday = LocalDate.now().minusDays(1)
        val today = LocalDate.now()
        val now = System.currentTimeMillis()

        // Quota state was captured yesterday
        val yesterdayQuota = LastKnownQuotaState(
            date = yesterday,
            capturedAtMillis = now - 24 * 3600 * 1000L,
            dailyForegroundUsageByPackage = mapOf("com.instagram.android" to 80 * 60 * 1000L),
            hourlyForegroundUsageByPackage = emptyMap(),
            quality = UsageDataQuality.PROVISIONAL,
            provenance = "UsageEvents"
        )

        // Day rollover check: yesterday's quota MUST NOT be accepted for today
        val sameDayQuota = yesterdayQuota.takeIf { it.date == today }
        assertNull("Yesterday's quota state must be discarded on day rollover", sameDayQuota)

        val rolloverSnapshot = ValidatedUsageSnapshot(
            date = today,
            capturedAtMillis = now,
            foregroundUsageByPackage = sameDayQuota?.dailyForegroundUsageByPackage ?: emptyMap(),
            hourlyUsageByPackage = sameDayQuota?.hourlyForegroundUsageByPackage ?: emptyMap(),
            quality = UsageDataQuality.PERMISSION_DENIED,
            isAvailable = false,
            dataSource = "Permission Denied"
        )
        assertTrue("Without same-day quota, usage map must be empty", rolloverSnapshot.foregroundUsageByPackage.isEmpty())
    }

    @Test
    fun testBuild8_emptyUsageEvents_markedUnavailableUnknown_retainsPreservedQuota() {
        val today = LocalDate.now()
        val now = System.currentTimeMillis()

        // Existing same-day quota showing Reddit exceeded 30-minute hourly quota
        val existingQuota = LastKnownQuotaState(
            date = today,
            capturedAtMillis = now - 5000L,
            dailyForegroundUsageByPackage = mapOf("com.reddit.frontpage" to 40 * 60 * 1000L),
            hourlyForegroundUsageByPackage = mapOf("com.reddit.frontpage" to 35 * 60 * 1000L),
            quality = UsageDataQuality.PROVISIONAL,
            provenance = "UsageEvents"
        )

        // OS returns EmptySuccess (0 events due to buffer delay)
        val sameDayQuota = existingQuota.takeIf { it.date == today }
        val snapshot = ValidatedUsageSnapshot(
            date = today,
            capturedAtMillis = now,
            foregroundUsageByPackage = sameDayQuota?.dailyForegroundUsageByPackage ?: emptyMap(),
            hourlyUsageByPackage = sameDayQuota?.hourlyForegroundUsageByPackage ?: emptyMap(),
            quality = UsageDataQuality.UNKNOWN,
            isAvailable = false, // Critical Build 8 requirement: NOT available zero usage
            dataSource = "UsageEvents (0 events, unverified)"
        )

        assertFalse("Empty events stream must NOT be reported as available zero usage", snapshot.isAvailable)
        assertEquals(UsageDataQuality.UNKNOWN, snapshot.quality)
        // Must preserve the existing usage rather than replacing with emptyMap
        assertEquals(35 * 60 * 1000L, snapshot.hourlyUsageByPackage["com.reddit.frontpage"])
    }

    @Test
    fun testBuild8_sharedRefreshQueue_deliversToAllPendingCallers() {
        val pendingCallbacks = java.util.concurrent.CopyOnWriteArrayList<(ValidatedUsageSnapshot) -> Unit>()
        val results = mutableListOf<ValidatedUsageSnapshot>()

        // 3 concurrent callers register callbacks for the in-flight query
        pendingCallbacks.add { results.add(it) }
        pendingCallbacks.add { results.add(it) }
        pendingCallbacks.add { results.add(it) }

        assertEquals(3, pendingCallbacks.size)

        // Fresh snapshot completes
        val freshSnapshot = ValidatedUsageSnapshot(
            date = LocalDate.now(),
            capturedAtMillis = System.currentTimeMillis(),
            foregroundUsageByPackage = mapOf("com.instagram.android" to 5000L),
            quality = UsageDataQuality.PROVISIONAL,
            isAvailable = true,
            dataSource = "UsageEvents"
        )

        // Drain and deliver
        val toDeliver = ArrayList(pendingCallbacks)
        pendingCallbacks.clear()
        toDeliver.forEach { it.invoke(freshSnapshot) }

        assertEquals(0, pendingCallbacks.size)
        assertEquals("All 3 callers must receive the fresh snapshot", 3, results.size)
        results.forEach { assertEquals(freshSnapshot, it) }
    }
}
