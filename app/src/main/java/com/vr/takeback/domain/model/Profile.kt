package com.vr.takeback.domain.model

data class Profile(
    val id: String,
    val name: String,
    val iconName: String = "auto_stories",
    val colorHex: String = "#10B981",
    val isEnabled: Boolean = true,
    val isPreset: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val targetApps: List<TargetApp> = emptyList(),
    val targetWebsites: List<TargetWebsite> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val usageLimits: List<UsageLimit> = emptyList(),
    val geofences: List<GeofenceRule> = emptyList(),
    val strictModeConfig: StrictModeConfig? = null
)

data class GeofenceRule(
    val id: String,
    val profileId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 200f,
    val triggerOnEnter: Boolean = true,
    val triggerOnExit: Boolean = false,
    val isEnabled: Boolean = true
)

data class TargetApp(
    val packageName: String,
    val appName: String,
    val isAllowlist: Boolean = false
)

data class TargetWebsite(
    val domainPattern: String,
    val ruleType: WebsiteRuleType = WebsiteRuleType.EXACT_DOMAIN,
    val isAllowlist: Boolean = false
)

enum class WebsiteRuleType {
    EXACT_DOMAIN,
    WILDCARD,
    KEYWORD
}

data class Schedule(
    val id: Long = 0,
    val profileId: String,
    val daysOfWeekMask: Int, // 1=Mon, 2=Tue, 4=Wed, 8=Thu, 16=Fri, 32=Sat, 64=Sun
    val startTimeSeconds: Int, // 0 to 86399
    val endTimeSeconds: Int,   // 0 to 86399
    val isEnabled: Boolean = true
) {
    fun isDayActive(dayIndex: Int): Boolean { // 1 = Monday ... 7 = Sunday
        val bit = 1 shl (dayIndex - 1)
        return (daysOfWeekMask and bit) != 0
    }
}

data class UsageLimit(
    val id: Long = 0,
    val profileId: String,
    val targetPackageName: String? = null, // null means shared pool limit for all profile apps
    val dailyLimitMinutes: Int,
    val windowType: LimitWindowType = LimitWindowType.FULL_DAY,
    val isEnabled: Boolean = true
)

enum class LimitWindowType {
    FULL_DAY,
    PER_HOUR,
    PER_SESSION
}

data class StrictModeConfig(
    val profileId: String,
    val isStrictActive: Boolean = false,
    val mechanism: StrictMechanism = StrictMechanism.PIN,
    val saltBase64: String? = null,
    val passwordHash: String? = null,
    val lockedUntilEpochMillis: Long? = null,
    val delayMinutes: Int = 5
)

enum class StrictMechanism {
    PIN,
    TIMED_LOCK,
    TEXT_CHALLENGE,
    DELAY
}
