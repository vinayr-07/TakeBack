package com.vr.takeback.domain.usage

import java.time.LocalDate

data class PackageComparisonEntry(
    val packageName: String,
    val appLabel: String,
    val queryAndAggregateMillis: Long,
    val intervalDailyMillis: Long,
    val takeBackReconstructedMillis: Long,
    val isEligibleUserApp: Boolean,
    val filterDecision: String
) {
    val differenceMillis: Long get() = queryAndAggregateMillis - takeBackReconstructedMillis
}

data class DiagnosticTotals(
    val allPackagesAggregateTotalMillis: Long,
    val allPackagesIntervalDailyTotalMillis: Long,
    val eligibleAggregateTotalMillis: Long,
    val eligibleIntervalDailyTotalMillis: Long,
    val eligibleTakeBackReconstructedTotalMillis: Long,
    val eligibleDifferenceMillis: Long,
    val excludedAggregateTotalMillis: Long,
    val deviceInteractiveTimeMillis: Long?,
    val isDeviceInteractiveAvailable: Boolean
)

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
    val excludedPackagesCount: Int
)

data class CalculationDiagnostics(
    val totalReconstructedSessions: Int,
    val totalAppUsageMillis: Long,
    val deviceScreenOnTimeMillis: Long,
    val snapshotTimestampMillis: Long,
    val dataQuality: UsageDataQuality = UsageDataQuality.RECONSTRUCTED_COMPLETE
)

/**
 * Diagnostic record capturing the complete empirical execution trace of a usage query.
 * Exposes exact metrics compared across identical package populations.
 * Reports purely measured facts without hardcoded conclusions or speculative rollover assumptions.
 */
data class UsageDiagnostics(
    val queryInfo: QueryMetadata,
    val totals: DiagnosticTotals,
    val filterDiagnostics: FilterDiagnostics,
    val calculationDiagnostics: CalculationDiagnostics,
    val packageMatrix: List<PackageComparisonEntry>,
    val dailyBuckets: List<DailyBucketInfo>,
    val eventCount: Int,
    val firstEventTimestampFormatted: String?,
    val lastEventTimestampFormatted: String?
) {
    fun toFormattedReport(): String {
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("      TAKEBACK USAGE STATISTICS DIAGNOSTICS       ")
        sb.appendLine("==================================================")
        sb.appendLine("Target Date:         ${queryInfo.targetDate}")
        sb.appendLine("Timezone:            ${queryInfo.timeZoneId}")
        sb.appendLine("Query Window Start:  ${queryInfo.startFormatted} (${queryInfo.startEpochMillis} ms)")
        sb.appendLine("Query Window End:    ${queryInfo.endFormatted} (${queryInfo.endEpochMillis} ms)")
        sb.appendLine("Execution Timestamp: ${queryInfo.queryExecutedFormatted}")
        sb.appendLine("Android API Level:   ${queryInfo.apiLevel}")
        sb.appendLine("Permission Granted:  ${queryInfo.hasUsagePermission}")
        sb.appendLine("Data Quality State:  ${calculationDiagnostics.dataQuality.displayLabel} (${calculationDiagnostics.dataQuality.technicalDescription})")
        sb.appendLine("Query Status:        ${if (queryInfo.isSuccess) "SUCCESS" else "FAILED: ${queryInfo.errorMessage}"}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("1. MEASURED TOTALS COMPARISON (SAME PACKAGE POPULATIONS):")
        sb.appendLine("   A. All Observed Packages (${filterDiagnostics.totalPackagesEvaluated} total):")
        sb.appendLine("      - queryAndAggregate Total:   ${formatMillis(totals.allPackagesAggregateTotalMillis)}")
        sb.appendLine("      - INTERVAL_DAILY Total:      ${formatMillis(totals.allPackagesIntervalDailyTotalMillis)}")
        sb.appendLine("      - Excluded System Packages:  ${formatMillis(totals.excludedAggregateTotalMillis)}")
        sb.appendLine("   B. Eligible User-Facing Applications (${filterDiagnostics.eligiblePackagesCount} packages):")
        sb.appendLine("      - queryAndAggregate Total:   ${formatMillis(totals.eligibleAggregateTotalMillis)}")
        sb.appendLine("      - TakeBack Reconstructed:    ${formatMillis(totals.eligibleTakeBackReconstructedTotalMillis)}")
        sb.appendLine("      - Measured Disparity:        ${formatMillis(totals.eligibleDifferenceMillis)}")
        sb.appendLine("   C. Device-Interactive Display Time:")
        sb.appendLine("      - Measured Display-On Time:  ${if (totals.isDeviceInteractiveAvailable && totals.deviceInteractiveTimeMillis != null) formatMillis(totals.deviceInteractiveTimeMillis) else "UNAVAILABLE (No screen-state events observed)"}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("2. USAGE EVENTS STREAM METRICS:")
        sb.appendLine("   - Event Count in Window:        $eventCount")
        sb.appendLine("   - First Observed Event:         ${firstEventTimestampFormatted ?: "None"}")
        sb.appendLine("   - Last Observed Event:          ${lastEventTimestampFormatted ?: "None"}")
        sb.appendLine("   - Reconstructed Session Count:  ${calculationDiagnostics.totalReconstructedSessions}")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("3. TOP PACKAGES MEASUREMENT MATRIX:")
        sb.appendLine(String.format("%-28s | %-12s | %-12s | %-12s", "Package", "OS Aggregate", "TakeBack", "Disparity"))
        sb.appendLine("----------------------------------------------------------------------")
        for (pkg in packageMatrix.take(15)) {
            val name = if (pkg.packageName.length > 28) pkg.packageName.take(25) + "..." else pkg.packageName
            sb.appendLine(
                String.format(
                    "%-28s | %-12s | %-12s | %-12s",
                    name,
                    formatShort(pkg.queryAndAggregateMillis),
                    formatShort(pkg.takeBackReconstructedMillis),
                    formatShort(pkg.differenceMillis)
                )
            )
        }
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

    private fun formatShort(millis: Long): String {
        val totalSec = millis / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m ${s}s"
    }
}
