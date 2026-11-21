package com.vr.takeback.domain.usage

/**
 * Explicit data quality and completeness states for usage statistics.
 *
 * Invariants:
 * 1. A successful query returning 0 events is distinguished from a failed query.
 * 2. Incomplete historical event boundaries are explicitly reported as PARTIAL.
 * 3. Fresh intraday queries are marked PROVISIONAL due to OS event buffer flushing lag.
 * 4. Expired historical dates where events were purged and no ledger snapshot exists
 *    are reported as HISTORY_EXPIRED rather than zero usage.
 */
enum class UsageDataQuality(
    val displayLabel: String,
    val technicalDescription: String,
    val isTrustworthy: Boolean
) {
    EXACT_SYSTEM_RESULT(
        displayLabel = "Exact System",
        technicalDescription = "Direct calendar-aligned measurement from Android OS",
        isTrustworthy = true
    ),
    RECONSTRUCTED_COMPLETE(
        displayLabel = "Complete",
        technicalDescription = "Reconstructed from continuous UsageEvents with clean boundaries",
        isTrustworthy = true
    ),
    RECONSTRUCTED_PARTIAL(
        displayLabel = "Partial",
        technicalDescription = "Reconstructed from UsageEvents with incomplete start or end boundaries",
        isTrustworthy = false
    ),
    PROVISIONAL(
        displayLabel = "Live / Provisional",
        technicalDescription = "Intraday data subject to recent event buffer flushing delays",
        isTrustworthy = true
    ),
    PERMISSION_DENIED(
        displayLabel = "Permission Denied",
        technicalDescription = "PACKAGE_USAGE_STATS permission is not granted",
        isTrustworthy = false
    ),
    QUERY_FAILED(
        displayLabel = "Query Failed",
        technicalDescription = "UsageStatsManager system query encountered an exception",
        isTrustworthy = false
    ),
    HISTORY_EXPIRED(
        displayLabel = "History Expired",
        technicalDescription = "System UsageEvents retention expired (>7-10d) and no snapshot was persisted",
        isTrustworthy = false
    ),
    UNKNOWN(
        displayLabel = "Unknown",
        technicalDescription = "Usage data has not yet been collected or initialized",
        isTrustworthy = false
    )
}
