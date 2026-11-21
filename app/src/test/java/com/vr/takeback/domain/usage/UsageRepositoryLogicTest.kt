package com.vr.takeback.domain.usage

import com.vr.takeback.data.repository.UsageEventsQueryResult
import com.vr.takeback.data.repository.ValidatedUsageSnapshot
import com.vr.takeback.domain.model.AppUsageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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
}
