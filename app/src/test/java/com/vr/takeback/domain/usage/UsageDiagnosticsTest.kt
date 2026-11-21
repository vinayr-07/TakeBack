package com.vr.takeback.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UsageDiagnosticsTest {

    @Test
    fun formattedReport_generatesAccurateComparisonMatrix() {
        val targetDate = LocalDate.of(2026, 11, 21)
        val diagnostics = UsageDiagnostics(
            queryInfo = QueryMetadata(
                targetDate = targetDate,
                timeZoneId = "Asia/Kolkata",
                startEpochMillis = 1732127400000L,
                startFormatted = "2026-11-21 00:00:00.000",
                endEpochMillis = 1732172400000L,
                endFormatted = "2026-11-21 12:30:00.000",
                queryExecutedEpochMillis = 1732172401000L,
                queryExecutedFormatted = "2026-11-21 12:30:01.000",
                apiLevel = 37,
                hasUsagePermission = true,
                isSuccess = true
            ),
            rawOsResults = RawOsResults(
                queryAndAggregateTotalMillis = 5400000L, // 1h 30m (over-reported due to 17:25 bucket)
                queryAndAggregatePackageCount = 45,
                queryAndAggregateTopPackages = listOf(
                    PackageRawStat("com.instagram.android", 3600000L, 0L, 1732170000000L)
                ),
                dailyBucketCount = 1,
                dailyIntervalTotalMillis = 5400000L,
                dailyBuckets = emptyList(),
                eventCount = 120,
                firstEventTimestampFormatted = "2026-11-21 07:15:00.000",
                lastEventTimestampFormatted = "2026-11-21 12:25:00.000"
            ),
            filterDiagnostics = FilterDiagnostics(
                totalPackagesEvaluated = 45,
                eligiblePackagesCount = 20,
                excludedPackagesCount = 25,
                filterDecisions = mapOf("com.android.systemui" to "EXCLUDED: Explicit system framework component")
            ),
            calculationDiagnostics = CalculationDiagnostics(
                totalReconstructedSessions = 12,
                totalAppUsageMillis = 3000000L, // 50m (true usage since 00:00)
                deviceScreenOnTimeMillis = 2700000L, // 45m (true screen-on time)
                perAppUsage = mapOf("com.instagram.android" to 1800000L),
                perAppLaunchCounts = mapOf("com.instagram.android" to 4),
                snapshotTimestampMillis = 1732172401000L
            )
        )

        val report = diagnostics.toFormattedReport()
        assertTrue(report.contains("TAKEBACK USAGE STATISTICS DIAGNOSTICS"))
        assertTrue(report.contains("Target Date:         2026-11-21"))
        assertTrue(report.contains("OS Aggregate Over-reporting:"))
        assertTrue(report.contains("Device Screen-On Time"))
    }
}
