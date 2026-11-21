package com.vr.takeback.domain.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Diagnostic record capturing the complete execution trace of a usage query.
 * Exposes exact metrics from:
 * 1. OS queryAndAggregateUsageStats()
 * 2. OS queryUsageStats(INTERVAL_DAILY)
 * 3. UsageEvents event stream & session reconstruction
 * 4. Package filtering decisions
 */
data class UsageDiagnostics(
    val queryInfo: QueryMetadata,
    val rawOsResults: RawOsResults,
    val filterDiagnostics: FilterDiagnostics,
    val calculationDiagnostics: CalculationDiagnostics
) {
    fun toFormattedReport(): String {
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("      TAKEBACK USAGE STATISTICS DIAGNOSTICS       ")
        sb.appendLine("==================================================")
        sb.appendLine("Target Date:         ${queryInfo.targetDate}")
        sb.appendLine("Timezone:            ${queryInfo.timeZoneId}")
        sb.appendLine("Start Time:          ${queryInfo.startFormatted} (${queryInfo.startEpochMillis} ms)")
        sb.appendLine("End Time:            ${queryInfo.endFormatted} (${queryInfo.endEpochMillis} ms)")
        sb.appendLine("Execution Time:      ${queryInfo.queryExecutedFormatted}")
        sb.appendLine("API Level:           ${queryInfo.apiLevel}")
        sb.appendLine("Permission Granted:  ${queryInfo.hasUsagePermission}")
        sb.appendLine("Query Status:        ${if (queryInfo.isSuccess) "SUCCESS" else "FAILED: ${queryInfo.errorMessage}"}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("1. RAW ANDROID OS LAYER COMPARISON:")
        sb.appendLine("   - queryAndAggregate Total:     ${formatMillis(rawOsResults.queryAndAggregateTotalMillis)}")
        sb.appendLine("   - queryAndAggregate Packages:  ${rawOsResults.queryAndAggregatePackageCount}")
        sb.appendLine("   - queryUsageStats Daily Files: ${rawOsResults.dailyBucketCount}")
        sb.appendLine("   - Daily Interval Total:        ${formatMillis(rawOsResults.dailyIntervalTotalMillis)}")
        sb.appendLine("   - Raw Event Count:             ${rawOsResults.eventCount}")
        sb.appendLine("   - First Event In Window:       ${rawOsResults.firstEventTimestampFormatted ?: "None"}")
        sb.appendLine("   - Last Event In Window:        ${rawOsResults.lastEventTimestampFormatted ?: "None"}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("2. PACKAGE FILTERING LAYER:")
        sb.appendLine("   - Total Packages Evaluated:    ${filterDiagnostics.totalPackagesEvaluated}")
        sb.appendLine("   - Eligible / Included:         ${filterDiagnostics.eligiblePackagesCount}")
        sb.appendLine("   - Excluded:                    ${filterDiagnostics.excludedPackagesCount}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("3. TAKEBACK RECONSTRUCTION LAYER:")
        sb.appendLine("   - Reconstructed Sessions:      ${calculationDiagnostics.totalReconstructedSessions}")
        sb.appendLine("   - Reconstructed Total App Time:${formatMillis(calculationDiagnostics.totalAppUsageMillis)}")
        sb.appendLine("   - Device Screen-On Time:       ${formatMillis(calculationDiagnostics.deviceScreenOnTimeMillis)}")
        sb.appendLine("   - Top Apps Count:              ${calculationDiagnostics.perAppUsage.size}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("4. ROOT-CAUSE SUMMARY:")
        sb.appendLine("   OS Aggregate Over-reporting:   ${formatMillis(rawOsResults.queryAndAggregateTotalMillis - calculationDiagnostics.totalAppUsageMillis)}")
        sb.appendLine("   Reason: Android daily buckets rotate at internal device intervals (~24h from boot), NOT midnight. queryAndAggregateUsageStats returns pre-midnight usage.")
        sb.appendLine("==================================================")
        return sb.toString()
    }

    private fun formatMillis(millis: Long): String {
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02dh %02dm %02ds (%d ms)", h, m, s, millis)
    }
}

data class QueryMetadata(
    val targetDate: LocalDate,
    val timeZoneId: String,
    val startEpochMillis: Long,
    val startFormatted: String,
    val endEpochMillis: Long,
    val endFormatted: String,
    val queryExecutedEpochMillis: Long,
    val queryExecutedFormatted: String,
    val apiLevel: Int,
    val hasUsagePermission: Boolean,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

data class RawOsResults(
    val queryAndAggregateTotalMillis: Long,
    val queryAndAggregatePackageCount: Int,
    val queryAndAggregateTopPackages: List<PackageRawStat>,
    val dailyBucketCount: Int,
    val dailyIntervalTotalMillis: Long,
    val dailyBuckets: List<DailyBucketInfo>,
    val eventCount: Int,
    val firstEventTimestampFormatted: String?,
    val lastEventTimestampFormatted: String?
)

data class PackageRawStat(
    val packageName: String,
    val totalTimeInForegroundMillis: Long,
    val totalTimeVisibleMillis: Long = 0L,
    val lastTimeUsedMillis: Long = 0L
)

data class DailyBucketInfo(
    val beginTimestamp: Long,
    val endTimestamp: Long,
    val beginFormatted: String,
    val endFormatted: String,
    val totalForegroundMillis: Long,
    val packageCount: Int
)

data class FilterDiagnostics(
    val totalPackagesEvaluated: Int,
    val eligiblePackagesCount: Int,
    val excludedPackagesCount: Int,
    val filterDecisions: Map<String, String>
)

data class CalculationDiagnostics(
    val totalReconstructedSessions: Int,
    val totalAppUsageMillis: Long,
    val deviceScreenOnTimeMillis: Long,
    val perAppUsage: Map<String, Long>,
    val perAppLaunchCounts: Map<String, Int>,
    val snapshotTimestampMillis: Long
)
