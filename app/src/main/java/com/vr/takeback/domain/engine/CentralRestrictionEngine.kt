package com.vr.takeback.domain.engine

import com.vr.takeback.domain.model.*
import com.vr.takeback.domain.usage.UsageDataQuality
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Immutable snapshot of system environment at the moment of evaluation.
 */
data class SystemContextSnapshot(
    val timestampMillis: Long,
    val localTime: LocalTime,
    val dayOfWeek: DayOfWeek,
    val foregroundPackageName: String?,
    val activeBrowserUrl: String? = null,
    val accumulatedForegroundTimeMillis: Map<String, Long> = emptyMap(),
    val hourlyForegroundTimeMillis: Map<String, Long> = emptyMap(),
    val currentSessionDurationMillis: Long = 0L,
    val isUsageDataAvailable: Boolean = true,
    val usageDataQuality: UsageDataQuality = UsageDataQuality.RECONSTRUCTED_COMPLETE,
    val activeGeofenceIds: Set<String> = emptySet(),
    val isFocusSessionActive: Boolean = false,
    val isBreakActive: Boolean = false,
    val activeFocusSessionProfileId: String? = null,
    val breakWhitelistApps: Set<String> = emptySet(),
    val isQuickBlockActive: Boolean = false,
    val quickBlockExpirationEpochMillis: Long? = null,
    val quickBlockTargetPackages: Set<String> = emptySet(),
    val isStrictModeActive: Boolean = false,
    val strictModeExpirationMillis: Long? = null
)

/**
 * Central Restriction Engine: Pure domain evaluator.
 * Zero Android framework imports. Fully deterministic.
 */
class CentralRestrictionEngine {

    companion object {
        // Packages that must never be blocked under any circumstances for safety
        val EMERGENCY_ALLOWLIST_PACKAGES = setOf(
            "com.android.phone",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.server.telecom",
            "com.android.emergency",
            "com.google.android.apps.safetyhub",
            "com.android.settings",
            "com.vr.takeback"
        )

        /**
         * Parses and extracts normalized lowercase hostname from a raw URL string.
         * Strips scheme, ports, query parameters, fragments, and trailing dots.
         * Pure JVM implementation without Android framework dependencies.
         */
        fun extractHost(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return null
            val urlWithScheme = if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
                "https://$trimmed"
            } else {
                trimmed
            }
            return try {
                val uri = java.net.URI(urlWithScheme)
                val host = uri.host?.lowercase()?.trimEnd('.')
                if (host.isNullOrEmpty()) null else host
            } catch (e: Exception) {
                // Fallback for malformed URLs
                val withoutScheme = urlWithScheme.substringAfter("://")
                val hostPart = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
                val host = hostPart.lowercase().trimEnd('.')
                if (host.isEmpty()) null else host
            }
        }
    }

    /**
     * Evaluates access for foreground package and optional browser URL.
     */
    fun evaluate(
        profiles: List<Profile>,
        snapshot: SystemContextSnapshot
    ): BlockDecision {
        val targetPackage = snapshot.foregroundPackageName ?: return BlockDecision.Allowed

        // Rule 0: Emergency Allowlist (Absolute Exemption)
        if (isEmergencyPackage(targetPackage)) {
            return BlockDecision.Allowed
        }

        // Rule 1: Break Whitelist during active Pomodoro Break
        if (snapshot.isBreakActive && snapshot.breakWhitelistApps.contains(targetPackage)) {
            return BlockDecision.Allowed
        }

        // Rule 1.5: Quick Block & Digital Detox Active
        if (snapshot.isQuickBlockActive) {
            val isTargetedByQuickBlock = snapshot.quickBlockTargetPackages.isEmpty() ||
                    snapshot.quickBlockTargetPackages.contains(targetPackage)
            if (isTargetedByQuickBlock) {
                val remaining = snapshot.quickBlockExpirationEpochMillis?.let { it - snapshot.timestampMillis }?.coerceAtLeast(0L)
                return BlockDecision.Blocked(
                    target = BlockTarget.Application(targetPackage),
                    triggeringProfileId = "quick_block",
                    triggeringProfileName = "Quick Block",
                    reason = BlockReason.QuickBlockActive,
                    remainingDurationMillis = remaining
                )
            }
        }

        // Rule 2: Active Focus Session (High Priority Override)
        if (snapshot.isFocusSessionActive && !snapshot.isBreakActive) {
            val focusProfile = profiles.find { it.id == snapshot.activeFocusSessionProfileId }
            if (focusProfile != null && isAppBlockedByProfile(focusProfile, targetPackage)) {
                return BlockDecision.Blocked(
                    target = BlockTarget.Application(targetPackage),
                    triggeringProfileId = focusProfile.id,
                    triggeringProfileName = focusProfile.name,
                    reason = BlockReason.FocusSessionActive
                )
            }
        }

        // Rule 3: Active Profiles Evaluation (Union Semantics)
        val enabledProfiles = profiles.filter { it.isEnabled }
        for (profile in enabledProfiles) {
            // Check if profile targets this app
            val isTargeted = isAppBlockedByProfile(profile, targetPackage)
            if (!isTargeted) continue

            // 3.1: Check Schedules
            val matchingSchedule = findActiveSchedule(profile.schedules, snapshot.localTime, snapshot.dayOfWeek)
            if (matchingSchedule != null) {
                val remainingSeconds = calculateRemainingScheduleSeconds(matchingSchedule, snapshot.localTime)
                return BlockDecision.Blocked(
                    target = BlockTarget.Application(targetPackage),
                    triggeringProfileId = profile.id,
                    triggeringProfileName = profile.name,
                    reason = BlockReason.ScheduleActive,
                    remainingDurationMillis = remainingSeconds * 1000L
                )
            }

            // 3.2: Check Daily & Hourly Usage Limits
            val limitDecision = evaluateUsageLimits(profile, targetPackage, snapshot)
            if (limitDecision is BlockDecision.Blocked) {
                return limitDecision
            }

            // 3.3: Check Geofences
            val matchesGeofence = profile.geofences.any { snapshot.activeGeofenceIds.contains(it.id) && it.isEnabled }
            if (matchesGeofence) {
                return BlockDecision.Blocked(
                    target = BlockTarget.Application(targetPackage),
                    triggeringProfileId = profile.id,
                    triggeringProfileName = profile.name,
                    reason = BlockReason.GeofenceActive
                )
            }

            // 3.4: Immediate / Always-Active Profile (profile is enabled with target apps and no conditional schedules/limits)
            if (profile.schedules.isEmpty() && profile.usageLimits.isEmpty() && profile.geofences.isEmpty()) {
                return BlockDecision.Blocked(
                    target = BlockTarget.Application(targetPackage),
                    triggeringProfileId = profile.id,
                    triggeringProfileName = profile.name,
                    reason = BlockReason.ScheduleActive,
                    remainingDurationMillis = null
                )
            }
        }

        // Rule 4: Browser URL Filtering (if URL is present)
        val activeUrl = snapshot.activeBrowserUrl
        if (!activeUrl.isNullOrBlank()) {
            val webDecision = evaluateWebFiltering(enabledProfiles, activeUrl, snapshot.localTime, snapshot.dayOfWeek)
            if (webDecision is BlockDecision.Blocked) {
                return webDecision
            }
        }

        return BlockDecision.Allowed
    }

    private fun isEmergencyPackage(packageName: String): Boolean {
        if (EMERGENCY_ALLOWLIST_PACKAGES.contains(packageName)) return true
        if (packageName.contains("dialer", ignoreCase = true) || packageName.contains("telecom", ignoreCase = true)) {
            return true
        }
        return false
    }

    private fun isAppBlockedByProfile(profile: Profile, packageName: String): Boolean {
        // If app is explicitly whitelisted in this profile, it is NOT blocked by this profile
        val isWhitelisted = profile.targetApps.any { it.packageName == packageName && it.isAllowlist }
        if (isWhitelisted) return false

        // Otherwise check if explicitly targeted
        return profile.targetApps.any { it.packageName == packageName && !it.isAllowlist }
    }

    /**
     * Evaluates schedule matching including overnight schedules.
     */
    fun findActiveSchedule(
        schedules: List<Schedule>,
        currentTime: LocalTime,
        currentDay: DayOfWeek
    ): Schedule? {
        val currentSeconds = currentTime.toSecondOfDay()
        val currentDayIndex = currentDay.value // 1 = Monday ... 7 = Sunday
        val previousDayIndex = if (currentDayIndex == 1) 7 else currentDayIndex - 1

        for (schedule in schedules) {
            if (!schedule.isEnabled) continue

            val start = schedule.startTimeSeconds
            val end = schedule.endTimeSeconds

            if (start < end) {
                // Daytime schedule: must be active on current day and within [start, end)
                if (schedule.isDayActive(currentDayIndex) && currentSeconds in start until end) {
                    return schedule
                }
            } else if (start == end) {
                // Equal start & end times: explicitly defined as 24-hour full-day restriction for selected day
                if (schedule.isDayActive(currentDayIndex)) {
                    return schedule
                }
            } else {
                // Overnight schedule (e.g. 22:00 to 06:00)
                // Case A: started yesterday evening and ends this morning
                val activeFromYesterday = schedule.isDayActive(previousDayIndex) && currentSeconds < end
                // Case B: started today evening and ends tomorrow morning
                val activeFromToday = schedule.isDayActive(currentDayIndex) && currentSeconds >= start

                if (activeFromYesterday || activeFromToday) {
                    return schedule
                }
            }
        }
        return null
    }

    fun isProfileCurrentlyEnforcing(
        profile: Profile,
        currentTime: LocalTime,
        currentDay: DayOfWeek,
        accumulatedForegroundTimeMillis: Map<String, Long> = emptyMap()
    ): Boolean {
        if (!profile.isEnabled) return false

        // 1. Direct active profile (no schedules configured) is immediately enforcing when enabled
        if (profile.schedules.isEmpty()) {
            return profile.targetApps.isNotEmpty() || profile.targetWebsites.isNotEmpty() || profile.usageLimits.isNotEmpty()
        }

        // 2. Scheduled profile: active if current moment is inside scheduled window
        if (findActiveSchedule(profile.schedules, currentTime, currentDay) != null) {
            return true
        }

        // 3. Profile with usage limits: limits govern target apps even outside schedule windows
        if (profile.usageLimits.any { it.isEnabled }) {
            return true
        }

        return false
    }

    private fun calculateRemainingScheduleSeconds(schedule: Schedule, currentTime: LocalTime): Long {
        val currentSeconds = currentTime.toSecondOfDay()
        val end = schedule.endTimeSeconds
        return if (end >= currentSeconds) {
            (end - currentSeconds).toLong()
        } else {
            (86400 - currentSeconds + end).toLong()
        }
    }

    /**
     * Evaluates daily, hourly, and per-session usage limits.
     * Enforces strict shared pool aggregation when targetPackageName is null.
     *
     * Data-availability policy (Defect 1):
     * - PERMISSION_DENIED / QUERY_FAILED / UNKNOWN → data is completely absent; allow the app
     *   UNLESS the accumulated map already contains a value >= limit for this package (which means
     *   it was supplied from a last-known state before availability was lost).
     * - STALE → allow ONLY if no prior exceeded-limit signal exists in the accumulated map.
     * - RECONSTRUCTED_PARTIAL / PROVISIONAL / RECONSTRUCTED_COMPLETE → enforce normally.
     */
    private fun evaluateUsageLimits(
        profile: Profile,
        packageName: String,
        snapshot: SystemContextSnapshot
    ): BlockDecision {
        if (profile.usageLimits.isEmpty()) return BlockDecision.Allowed

        // Build 8 Issue 2 & 3: Check data availability before evaluating usage limits.
        // When data is unavailable or unverified, missing entries must not be silently treated
        // as zero usage. However, innocent apps must also not be blocked on query failure (fail-open),
        // while apps that already reached their limit on the same day remain restricted (last-known state).
        val dataAbsent = !snapshot.isUsageDataAvailable || when (snapshot.usageDataQuality) {
            UsageDataQuality.PERMISSION_DENIED,
            UsageDataQuality.QUERY_FAILED,
            UsageDataQuality.UNKNOWN,
            UsageDataQuality.HISTORY_EXPIRED,
            UsageDataQuality.STALE -> true
            else -> false
        }

        for (limit in profile.usageLimits) {
            if (!limit.isEnabled) continue

            // Determine if this limit targets this package:
            // null targetPackageName means shared pool limit for all profile's non-allowlisted target apps
            val isTargeted = if (limit.targetPackageName == null) {
                profile.targetApps.any { it.packageName == packageName && !it.isAllowlist }
            } else {
                limit.targetPackageName == packageName
            }
            if (!isTargeted) continue

            // Defect 1 & Build 7 Issue 2: If data is absent or stale, only block if the
            // corresponding measurement already exceeds the limit (last-known state carried forward).
            // Uses distinct measurements:
            // - FULL_DAY: last-known daily usage from accumulatedForegroundTimeMillis
            // - PER_HOUR: last-known hourly usage from hourlyForegroundTimeMillis
            // - PER_SESSION: last-known session duration from currentSessionDurationMillis
            // Never compares daily usage against hourly or session thresholds!
            if (dataAbsent) {
                val poolPackages = if (limit.targetPackageName == null) {
                    profile.targetApps.filter { !it.isAllowlist }.map { it.packageName }
                } else {
                    listOf(packageName)
                }

                val lastKnownMillis: Long = when (limit.windowType) {
                    LimitWindowType.FULL_DAY -> {
                        poolPackages.sumOf { snapshot.accumulatedForegroundTimeMillis[it] ?: 0L }
                    }
                    LimitWindowType.PER_HOUR -> {
                        poolPackages.sumOf { snapshot.hourlyForegroundTimeMillis[it] ?: 0L }
                    }
                    LimitWindowType.PER_SESSION -> {
                        snapshot.currentSessionDurationMillis
                    }
                }

                val lastKnownMinutes = (lastKnownMillis / (1000 * 60)).toInt()
                if (lastKnownMinutes < limit.dailyLimitMinutes) {
                    // Cannot verify limit is exceeded → allow (do not penalise due to data absence)
                    continue
                }
                // Last-known data shows limit was already exceeded; block conservatively.
                val reason = when (limit.windowType) {
                    LimitWindowType.FULL_DAY -> BlockReason.DailyLimitExceeded(
                        limitMinutes = limit.dailyLimitMinutes,
                        usedMinutes = lastKnownMinutes
                    )
                    LimitWindowType.PER_HOUR -> BlockReason.HourlyQuotaExceeded(
                        limitMinutes = limit.dailyLimitMinutes
                    )
                    LimitWindowType.PER_SESSION -> BlockReason.SessionQuotaExceeded(
                        limitMinutes = limit.dailyLimitMinutes,
                        usedMinutes = lastKnownMinutes
                    )
                }
                return BlockDecision.Blocked(
                    target = BlockTarget.Application(packageName),
                    triggeringProfileId = profile.id,
                    triggeringProfileName = profile.name,
                    reason = reason,
                    remainingDurationMillis = 0L
                )
            }

            val usedMillis: Long = when (limit.windowType) {
                LimitWindowType.FULL_DAY -> {
                    if (limit.targetPackageName == null) {
                        // Shared pool limit: aggregate usage of ALL target apps in this profile
                        val poolPackages = profile.targetApps.filter { !it.isAllowlist }.map { it.packageName }
                        poolPackages.sumOf { snapshot.accumulatedForegroundTimeMillis[it] ?: 0L }
                    } else {
                        snapshot.accumulatedForegroundTimeMillis[packageName] ?: 0L
                    }
                }
                LimitWindowType.PER_HOUR -> {
                    if (limit.targetPackageName == null) {
                        val poolPackages = profile.targetApps.filter { !it.isAllowlist }.map { it.packageName }
                        poolPackages.sumOf { snapshot.hourlyForegroundTimeMillis[it] ?: 0L }
                    } else {
                        snapshot.hourlyForegroundTimeMillis[packageName] ?: 0L
                    }
                }
                LimitWindowType.PER_SESSION -> {
                    snapshot.currentSessionDurationMillis
                }
            }

            val usedMinutes = (usedMillis / (1000 * 60)).toInt()

            if (usedMinutes >= limit.dailyLimitMinutes) {
                val reason = when (limit.windowType) {
                    LimitWindowType.FULL_DAY -> BlockReason.DailyLimitExceeded(
                        limitMinutes = limit.dailyLimitMinutes,
                        usedMinutes = usedMinutes
                    )
                    LimitWindowType.PER_HOUR -> BlockReason.HourlyQuotaExceeded(
                        limitMinutes = limit.dailyLimitMinutes
                    )
                    LimitWindowType.PER_SESSION -> BlockReason.SessionQuotaExceeded(
                        limitMinutes = limit.dailyLimitMinutes,
                        usedMinutes = usedMinutes
                    )
                }
                return BlockDecision.Blocked(
                    target = BlockTarget.Application(packageName),
                    triggeringProfileId = profile.id,
                    triggeringProfileName = profile.name,
                    reason = reason,
                    remainingDurationMillis = 0L // Blocked until window reset
                )
            }
        }
        return BlockDecision.Allowed
    }

    private fun evaluateWebFiltering(
        profiles: List<Profile>,
        rawUrl: String,
        localTime: LocalTime,
        dayOfWeek: DayOfWeek
    ): BlockDecision {
        val cleanUrl = rawUrl.lowercase().trim()
        val extractedHost = extractHost(cleanUrl)

        val activeProfiles = profiles.filter { isProfileCurrentlyEnforcing(it, localTime, dayOfWeek) }

        // Section 5: Allowlist rules take precedence over blocklist rules (exact host & domain boundary matching)
        for (profile in activeProfiles) {
            for (rule in profile.targetWebsites.filter { it.isAllowlist }) {
                val pattern = rule.domainPattern.lowercase().trim()
                val isMatch = when (rule.ruleType) {
                    WebsiteRuleType.EXACT_DOMAIN -> {
                        val ruleHost = extractHost(pattern) ?: pattern.trimEnd('.')
                        extractedHost != null && extractedHost == ruleHost
                    }
                    WebsiteRuleType.WILDCARD -> {
                        val baseDomain = pattern.removePrefix("*").removePrefix(".").trimEnd('.')
                        extractedHost != null && (extractedHost == baseDomain || extractedHost.endsWith(".$baseDomain"))
                    }
                    WebsiteRuleType.KEYWORD -> cleanUrl.contains(pattern)
                }
                if (isMatch) {
                    return BlockDecision.Allowed
                }
            }
        }

        // Section 5: Blocklist evaluation using robust host & wildcard boundary matching
        for (profile in activeProfiles) {
            for (rule in profile.targetWebsites.filter { !it.isAllowlist }) {
                val pattern = rule.domainPattern.lowercase().trim()
                var matchedDomain: String? = null
                val isMatch = when (rule.ruleType) {
                    WebsiteRuleType.EXACT_DOMAIN -> {
                        val ruleHost = extractHost(pattern) ?: pattern.trimEnd('.')
                        matchedDomain = ruleHost
                        extractedHost != null && extractedHost == ruleHost
                    }
                    WebsiteRuleType.WILDCARD -> {
                        val baseDomain = pattern.removePrefix("*").removePrefix(".").trimEnd('.')
                        matchedDomain = baseDomain
                        extractedHost != null && (extractedHost == baseDomain || extractedHost.endsWith(".$baseDomain"))
                    }
                    WebsiteRuleType.KEYWORD -> cleanUrl.contains(pattern)
                }

                if (isMatch) {
                    val reason = if (rule.ruleType == WebsiteRuleType.KEYWORD) {
                        BlockReason.KeywordMatch(pattern)
                    } else {
                        BlockReason.DomainBlock(matchedDomain ?: pattern)
                    }
                    return BlockDecision.Blocked(
                        target = BlockTarget.Website(rawUrl),
                        triggeringProfileId = profile.id,
                        triggeringProfileName = profile.name,
                        reason = reason
                    )
                }
            }
        }
        return BlockDecision.Allowed
    }
}
