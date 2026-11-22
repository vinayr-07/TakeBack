package com.vr.takeback.domain.model

sealed class BlockDecision {
    object Allowed : BlockDecision()

    data class Blocked(
        val target: BlockTarget,
        val triggeringProfileId: String,
        val triggeringProfileName: String,
        val reason: BlockReason,
        val remainingDurationMillis: Long? = null,
        val allowBreakUntilMillis: Long? = null
    ) : BlockDecision()
}

sealed class BlockTarget {
    data class Application(val packageName: String) : BlockTarget()
    data class Website(val domainOrUrl: String) : BlockTarget()
    data class ContentArea(val areaType: ContentType) : BlockTarget()
}

enum class ContentType {
    YOUTUBE_SHORTS,
    INSTAGRAM_REELS,
    FACEBOOK_REELS,
    SNAPCHAT_SPOTLIGHT
}

sealed class BlockReason {
    object ScheduleActive : BlockReason()
    data class DailyLimitExceeded(val limitMinutes: Int, val usedMinutes: Int) : BlockReason()
    data class HourlyQuotaExceeded(val limitMinutes: Int) : BlockReason()
    data class SessionQuotaExceeded(val limitMinutes: Int, val usedMinutes: Int) : BlockReason()
    data class GoalNotMet(val requiredMinutes: Int, val completedMinutes: Int) : BlockReason()
    object GeofenceActive : BlockReason()
    object FocusSessionActive : BlockReason()
    object QuickBlockActive : BlockReason()
    data class DomainBlock(val domain: String) : BlockReason()
    data class KeywordMatch(val keyword: String) : BlockReason()
    data class DailyAllowanceExceeded(val allowanceMinutes: Int, val usedMinutes: Int) : BlockReason()
}
