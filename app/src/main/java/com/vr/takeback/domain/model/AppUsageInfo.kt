package com.vr.takeback.domain.model

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val usageTimeMillis: Long,
    val reconstructedSessionCount: Int = 0,
    val lastTimeUsedMillis: Long = 0,
    val isBlocked: Boolean = false
) {
    @Deprecated(
        "Renamed to reconstructedSessionCount to reflect that public UsageEvents only expose reconstructed session intervals, not physical app launches.",
        ReplaceWith("reconstructedSessionCount")
    )
    val launchCount: Int get() = reconstructedSessionCount
}
