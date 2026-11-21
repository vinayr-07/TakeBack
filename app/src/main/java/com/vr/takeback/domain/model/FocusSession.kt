package com.vr.takeback.domain.model

data class FocusSession(
    val sessionId: String,
    val focusDurationMinutes: Int = 25,
    val breakDurationMinutes: Int = 5,
    val totalCycles: Int = 4,
    val currentCycle: Int = 1,
    val state: FocusSessionState = FocusSessionState.IDLE,
    val activeProfileId: String? = null,
    val currentPeriodStartEpochMillis: Long = 0,
    val currentPeriodDurationMillis: Long = 0,
    val breakWhitelistApps: Set<String> = emptySet(),
    val ambientSoundKey: String? = null
)

enum class FocusSessionState {
    IDLE,
    FOCUS_RUNNING,
    BREAK_RUNNING,
    PAUSED,
    COMPLETED
}

data class ActivityEvent(
    val id: Long = 0,
    val eventType: EventType,
    val targetIdentifier: String,
    val profileId: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val extraMetadata: String? = null
)

enum class EventType {
    APP_OPENED,
    APP_BLOCKED,
    WEBSITE_BLOCKED,
    PROFILE_ACTIVATED,
    PROFILE_DEACTIVATED,
    FOCUS_STARTED,
    BREAK_STARTED,
    FOCUS_COMPLETED,
    LIMIT_EXHAUSTED
}

data class PermissionStatus(
    val hasUsageStats: Boolean = false,
    val hasOverlay: Boolean = false,
    val hasAccessibility: Boolean = false,
    val hasNotificationListener: Boolean = false,
    val hasPostNotifications: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false,
    val hasExactAlarm: Boolean = false,
    val hasLocation: Boolean = false
) {
    val isCoreProtected: Boolean
        get() = hasUsageStats && (hasOverlay || hasAccessibility)
}
