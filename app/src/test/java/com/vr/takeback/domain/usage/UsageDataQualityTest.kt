package com.vr.takeback.domain.usage

import com.vr.takeback.data.database.PersistedAppRecord
import com.vr.takeback.data.database.PersistedDeviceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UsageDataQualityTest {

    @Test
    fun usageDataQuality_trustworthinessInvariants() {
        // Complete and exact system measurements are trustworthy
        assertTrue(UsageDataQuality.EXACT_SYSTEM_RESULT.isTrustworthy)
        assertTrue(UsageDataQuality.RECONSTRUCTED_COMPLETE.isTrustworthy)
        assertTrue(UsageDataQuality.PROVISIONAL.isTrustworthy) // Intraday live is trustworthy for real-time tracking

        // Partial, error, and expired states are NOT marked trustworthy
        assertFalse(UsageDataQuality.RECONSTRUCTED_PARTIAL.isTrustworthy)
        assertFalse(UsageDataQuality.PERMISSION_DENIED.isTrustworthy)
        assertFalse(UsageDataQuality.QUERY_FAILED.isTrustworthy)
        assertFalse(UsageDataQuality.HISTORY_EXPIRED.isTrustworthy)
        assertFalse(UsageDataQuality.UNKNOWN.isTrustworthy)
    }

    @Test
    fun persistedDeviceRecord_preservesDistinctionBetweenDeviceAndAppTime() {
        val record = PersistedDeviceRecord(
            dateIso = "2026-11-20",
            deviceInteractiveMillis = 18000000L, // 5 hours screen on
            totalTrackedAppMillis = 21600000L,   // 6 hours app usage (due to split-screen)
            dataSource = "UsageEvents",
            qualityStatus = UsageDataQuality.RECONSTRUCTED_COMPLETE,
            queryTimestamp = 1732147200000L,
            lastSyncEventTimestamp = 1732147195000L,
            deviceTimezone = "Asia/Kolkata",
            isFinalized = true
        )

        assertEquals("2026-11-20", record.dateIso)
        assertEquals(18000000L, record.deviceInteractiveMillis)
        assertEquals(21600000L, record.totalTrackedAppMillis)
        assertTrue(record.isFinalized)
        assertEquals(UsageDataQuality.RECONSTRUCTED_COMPLETE, record.qualityStatus)
    }

    @Test
    fun persistedAppRecord_storesTraceablePerAppMetrics() {
        val app = PersistedAppRecord(
            dateIso = "2026-11-20",
            packageName = "com.instagram.android",
            appLabel = "Instagram",
            foregroundDurationMillis = 3600000L,
            reconstructedSessionCount = 8,
            lastTimeUsedMillis = 1732140000000L,
            dataSource = "UsageEvents",
            qualityStatus = UsageDataQuality.RECONSTRUCTED_COMPLETE,
            queryTimestamp = 1732147200000L,
            lastSyncEventTimestamp = 1732147195000L,
            deviceTimezone = "Asia/Kolkata"
        )

        assertEquals("com.instagram.android", app.packageName)
        assertEquals(3600000L, app.foregroundDurationMillis)
        assertEquals(8, app.reconstructedSessionCount)
        @Suppress("DEPRECATION")
        assertEquals(8, app.launchCount)
        assertEquals(UsageDataQuality.RECONSTRUCTED_COMPLETE, app.qualityStatus)
    }
}
