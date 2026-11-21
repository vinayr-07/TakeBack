package com.vr.takeback.domain.model

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val usageTimeMillis: Long,
    val launchCount: Int = 0,
    val lastTimeUsedMillis: Long = 0,
    val isBlocked: Boolean = false
)
