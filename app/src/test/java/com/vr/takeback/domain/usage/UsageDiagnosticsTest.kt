package com.vr.takeback.domain.usage

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
            totals = DiagnosticTotals(
                allPackagesAggregateTotalMillis = 5400000L,
                allPackagesIntervalDailyTotalMillis = 5400000L,
                eligibleAggregateTotalMillis = 4000000L,
                eligibleIntervalDailyTotalMillis = 4000000L,
                eligibleTakeBackReconstructedTotalMillis = 2400000L,
                eligibleDifferenceMillis = 1600000L,
                excludedAggregateTotalMillis = 1400000L,
                deviceInteractiveTimeMillis = 2700000L,
                isDeviceInteractiveAvailable = true
            ),
            filterDiagnostics = FilterDiagnostics(
                totalPackagesEvaluated = 45,
                eligiblePackagesCount = 20,
                excludedPackagesCount = 25
            ),
            calculationDiagnostics = CalculationDiagnostics(
                totalReconstructedSessions = 12,
                totalAppUsageMillis = 2400000L,
                deviceScreenOnTimeMillis = 2700000L,
                snapshotTimestampMillis = 1732172401000L,
                dataQuality = UsageDataQuality.RECONSTRUCTED_COMPLETE
            ),
            packageMatrix = listOf(
                PackageComparisonEntry(
                    packageName = "com.instagram.android",
                    appLabel = "Instagram",
                    queryAndAggregateMillis = 3600000L,
                    intervalDailyMillis = 3600000L,
                    takeBackReconstructedMillis = 1800000L,
                    isEligibleUserApp = true,
                    filterDecision = "INCLUDED: User App"
                )
            ),
            dailyBuckets = emptyList(),
            eventCount = 120,
            firstEventTimestampFormatted = "2026-11-21 07:15:00.000",
            lastEventTimestampFormatted = "2026-11-21 12:25:00.000"
        )

        val report = diagnostics.toFormattedReport()
        assertTrue(report.contains("TAKEBACK USAGE STATISTICS DIAGNOSTICS"))
        assertTrue(report.contains("Target Date:         2026-11-21"))
        assertTrue(report.contains("MEASURED TOTALS COMPARISON (SAME PACKAGE POPULATIONS)"))
        assertTrue(report.contains("Measured Display-On Time:"))
        assertTrue(report.contains("TOP PACKAGES MEASUREMENT MATRIX:"))
        assertTrue(report.contains("com.instagram.android"))
    }
}
