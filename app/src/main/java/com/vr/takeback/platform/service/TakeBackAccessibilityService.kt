package com.vr.takeback.platform.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.vr.takeback.TakeBackApplication
import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.engine.DnsBlockMatcher
import com.vr.takeback.domain.engine.SystemContextSnapshot
import com.vr.takeback.domain.model.BlockDecision
import com.vr.takeback.domain.model.BlockReason
import com.vr.takeback.domain.model.BlockTarget
import com.vr.takeback.domain.model.EventType
import com.vr.takeback.presentation.ui.shield.BlockShieldActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class TakeBackAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "TakeBackAccessibility"

        var isServiceRunning = false
            private set

        private val SUPPORTED_BROWSERS = setOf(
            "com.android.chrome",
            "com.sec.android.app.sbrowser",
            "com.brave.browser",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.kiwibrowser.browser",
            "com.vivaldi.browser"
        )
    }

    private val engine = CentralRestrictionEngine()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastUsageSnapshotRefreshTime: Long = 0L
    private var lastBlockedPackage: String? = null
    private var lastBlockedTimestamp: Long = 0
    @Volatile
    private var currentForegroundPackage: String? = null
    @Volatile
    private var currentSessionStartTime: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.i(TAG, "TakeBack Accessibility Service connected and active")
        serviceScope.launch {
            try {
                TakeBackApplication.usageStatsRepository.refreshUsageSnapshot()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceScope.cancel()
        Log.i(TAG, "TakeBack Accessibility Service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // Ignore TakeBack itself, system UI, and home launchers
        if (packageName == applicationContext.packageName) return
        if (packageName == "com.android.systemui" || packageName.contains("launcher", ignoreCase = true)) {
            return
        }

        val now = System.currentTimeMillis()
        if (packageName != currentForegroundPackage) {
            currentForegroundPackage = packageName
            currentSessionStartTime = now
        }

        // Strict Mode Anti-Tamper: Protect against Force Stop / Uninstall through Settings
        val strictState = try {
            TakeBackApplication.strictModeRepository.protectionState.value
        } catch (e: Exception) {
            null
        }

        if (strictState?.isCurrentlyLocked == true && packageName == "com.android.settings") {
            if (isTakeBackSettingsScreen(rootInActiveWindow)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                val shieldIntent = Intent(applicationContext, BlockShieldActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("EXTRA_APP_NAME", "Settings Protection")
                    putExtra("EXTRA_PROFILE_NAME", "Strict Mode")
                    putExtra("EXTRA_REASON", "Settings modification and uninstallation are locked by Strict Mode")
                }
                startActivity(shieldIntent)
                return
            }
        }

        // De-bounce rapid consecutive events for the same blocked package (< 1.2s)
        if (packageName == lastBlockedPackage && (now - lastBlockedTimestamp) < 1200) {
            return
        }

        // Fetch live shared states from TakeBackApplication
        val activeProfiles = try {
            TakeBackApplication.profileRepository.profiles.value
        } catch (e: Exception) {
            emptyList()
        }

        val quickBlockState = try {
            TakeBackApplication.quickBlockRepository.state.value
        } catch (e: Exception) {
            null
        }

        val isQuickBlockActive = quickBlockState?.isActive == true
        val enabledProfiles = activeProfiles.filter { it.isEnabled }

        // Core Invariance: If no profiles are enabled and Quick Block is off, allow all apps immediately
        val localTime = LocalTime.now()
        val dayOfWeek = LocalDate.now().dayOfWeek
        val hasEnforcingProfiles = enabledProfiles.any { profile ->
            engine.isProfileCurrentlyEnforcing(profile, localTime, dayOfWeek)
        }

        if (!hasEnforcingProfiles && !isQuickBlockActive) {
            return
        }

        // Selective Short-Form Content Filter (YouTube Shorts & Instagram Reels) - only active if app is targeted by an active profile
        val isTargetedByActiveProfile = enabledProfiles.any { profile ->
            engine.isProfileCurrentlyEnforcing(profile, localTime, dayOfWeek) &&
                    profile.targetApps.any { it.packageName == packageName && !it.isAllowlist }
        }

        if (isTargetedByActiveProfile && (packageName == "com.google.android.youtube" || packageName == "com.instagram.android")) {
            if (isShortsOrReelsContainer(packageName, rootInActiveWindow)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                lastBlockedPackage = packageName
                lastBlockedTimestamp = now
                TakeBackApplication.timelineRepository.logEvent(
                    eventType = EventType.APP_BLOCKED,
                    targetIdentifier = if (packageName.contains("youtube")) "YouTube Shorts" else "Instagram Reels",
                    extraMetadata = "Selective short-form feed filter active"
                )
                return
            }
        }

        // Extract browser URL if inside a supported browser
        val browserUrl = if (SUPPORTED_BROWSERS.contains(packageName)) {
            extractBrowserUrl(rootInActiveWindow)
        } else null

        // Web Shield Adult Content & Domain Inspection
        if (!browserUrl.isNullOrBlank()) {
            val isWebShieldOn = LocalDnsVpnService.isFilterRunning
            if (isWebShieldOn && DnsBlockMatcher.isAdultContent(browserUrl)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                lastBlockedPackage = packageName
                lastBlockedTimestamp = now
                val decision = BlockDecision.Blocked(
                    target = BlockTarget.Website(browserUrl),
                    triggeringProfileId = "web_shield",
                    triggeringProfileName = "Web Shield",
                    reason = BlockReason.AdultContentFilter
                )
                handleBlockDecision(decision)
                return
            }
        }

        val quickBlockExpiry = if (isQuickBlockActive) {
            quickBlockState!!.startEpochMillis + (quickBlockState.durationMinutes * 60 * 1000L)
        } else null

        val allConfiguredPackages = try {
            TakeBackApplication.profileRepository.getAllRestrictedPackages(onlyEnabled = false)
        } catch (e: Exception) {
            emptySet()
        }

        val quickBlockPackages = if (isQuickBlockActive) {
            if (quickBlockState?.targetPackages.isNullOrEmpty()) {
                allConfiguredPackages.ifEmpty {
                    setOf(
                        "com.instagram.android",
                        "com.zhiliaoapp.musically",
                        "com.google.android.youtube",
                        "com.reddit.frontpage",
                        "com.twitter.android",
                        "com.facebook.katana",
                        "com.snapchat.android"
                    )
                }
            } else {
                quickBlockState!!.targetPackages
            }
        } else emptySet()

        val activeSnapshot = try {
            TakeBackApplication.usageStatsRepository.getActiveUsageSnapshot()
        } catch (e: Exception) {
            null
        }

        val hasUsageLimitForApp = enabledProfiles.any { profile ->
            profile.usageLimits.any { limit ->
                limit.isEnabled && (limit.targetPackageName == null || limit.targetPackageName == packageName)
            }
        }

        val currentSessionDuration = maxOf(0L, now - currentSessionStartTime)

        // Build current context snapshot
        val snapshot = SystemContextSnapshot(
            timestampMillis = now,
            localTime = localTime,
            dayOfWeek = dayOfWeek,
            foregroundPackageName = packageName,
            activeBrowserUrl = browserUrl,
            accumulatedForegroundTimeMillis = activeSnapshot?.foregroundUsageByPackage ?: emptyMap(),
            currentSessionDurationMillis = currentSessionDuration,
            isUsageDataAvailable = activeSnapshot?.isAvailable ?: false,
            usageDataQuality = activeSnapshot?.quality ?: com.vr.takeback.domain.usage.UsageDataQuality.UNKNOWN,
            isQuickBlockActive = isQuickBlockActive,
            quickBlockExpirationEpochMillis = quickBlockExpiry,
            quickBlockTargetPackages = quickBlockPackages
        )

        val immediateDecision = engine.evaluate(activeProfiles, snapshot)

        if (immediateDecision is BlockDecision.Blocked) {
            Log.i(TAG, "Blocking $packageName immediately because: ${immediateDecision.reason} (Profile: ${immediateDecision.triggeringProfileName})")
            lastBlockedPackage = packageName
            lastBlockedTimestamp = now
            handleBlockDecision(immediateDecision)
            return
        }

        // Requirement 4: If usage limits apply and the snapshot is stale/unloaded,
        // refresh in background without blocking the main thread, and re-evaluate only if
        // the package is STILL foreground before executing any delayed block!
        val isSnapshotFresh = activeSnapshot?.isAvailable == true && (now - activeSnapshot.capturedAtMillis <= 30_000L)
        if (hasUsageLimitForApp && !isSnapshotFresh) {
            serviceScope.launch {
                try {
                    val fresh = TakeBackApplication.usageStatsRepository.refreshUsageSnapshot()
                    if (currentForegroundPackage == packageName) {
                        val updatedSnapshot = snapshot.copy(
                            timestampMillis = System.currentTimeMillis(),
                            accumulatedForegroundTimeMillis = fresh.foregroundUsageByPackage,
                            currentSessionDurationMillis = System.currentTimeMillis() - currentSessionStartTime,
                            isUsageDataAvailable = fresh.isAvailable,
                            usageDataQuality = fresh.quality
                        )
                        val delayedDecision = engine.evaluate(activeProfiles, updatedSnapshot)
                        if (delayedDecision is BlockDecision.Blocked && currentForegroundPackage == packageName) {
                            Log.i(TAG, "Delayed block on fresh snapshot for $packageName: ${delayedDecision.reason}")
                            lastBlockedPackage = packageName
                            lastBlockedTimestamp = System.currentTimeMillis()
                            handleBlockDecision(delayedDecision)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed background refresh for usage limits", e)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted by system")
    }

    private fun handleBlockDecision(decision: BlockDecision.Blocked) {
        val appName = when (val target = decision.target) {
            is BlockTarget.Application -> resolveAppLabel(target.packageName)
            is BlockTarget.Website -> target.domainOrUrl
            is BlockTarget.ContentArea -> target.areaType.name
        }

        val reasonText = when (decision.reason) {
            is BlockReason.ScheduleActive -> "Active schedule restriction"
            is BlockReason.DailyLimitExceeded -> "Daily usage limit reached (${decision.reason.limitMinutes}m)"
            is BlockReason.HourlyQuotaExceeded -> "Hourly allowance exhausted"
            is BlockReason.FocusSessionActive -> "Deep Focus sprint in progress"
            is BlockReason.QuickBlockActive -> "Quick Block active"
            is BlockReason.AdultContentFilter -> "Filtered by Web Shield content protection"
            is BlockReason.KeywordMatch -> "Blocked by keyword '${decision.reason.keyword}'"
            is BlockReason.GeofenceActive -> "Restricted at this location"
            is BlockReason.GoalNotMet -> "Productivity goal required first"
        }

        // Return user to home immediately to stop the restricted app
        performGlobalAction(GLOBAL_ACTION_HOME)

        // Log to Activity Timeline
        try {
            TakeBackApplication.timelineRepository.logEvent(
                eventType = if (decision.target is BlockTarget.Website) EventType.WEBSITE_BLOCKED else EventType.APP_BLOCKED,
                targetIdentifier = appName,
                profileId = decision.triggeringProfileId,
                extraMetadata = reasonText
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log event to timeline: ${e.message}")
        }

        // Launch full-screen BlockShieldActivity
        val shieldIntent = Intent(applicationContext, BlockShieldActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_APP_NAME", appName)
            putExtra("EXTRA_PROFILE_NAME", decision.triggeringProfileName)
            putExtra("EXTRA_REASON", reasonText)
        }
        startActivity(shieldIntent)
    }

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    private fun isTakeBackSettingsScreen(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        return try {
            findTakeBackInNode(rootNode)
        } catch (e: Exception) {
            false
        }
    }

    private fun findTakeBackInNode(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (text.contains("TakeBack", ignoreCase = true) || desc.contains("TakeBack", ignoreCase = true)) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findTakeBackInNode(child)
            if (found) return true
        }
        return false
    }

    private fun extractBrowserUrl(rootNode: AccessibilityNodeInfo?): String? {
        if (rootNode == null) return null
        return try {
            findAddressBarNode(rootNode)
        } catch (e: Exception) {
            null
        }
    }

    private fun findAddressBarNode(node: AccessibilityNodeInfo): String? {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()

        val isAddressNode = viewId.contains("url_bar") ||
                viewId.contains("search_box") ||
                viewId.contains("location_bar") ||
                viewId.contains("address_bar")

        if (isAddressNode && !text.isNullOrBlank()) {
            return text
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findAddressBarNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun isShortsOrReelsContainer(packageName: String, rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        return try {
            findShortsNode(packageName, rootNode)
        } catch (e: Exception) {
            false
        }
    }

    private fun findShortsNode(packageName: String, node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (packageName == "com.google.android.youtube") {
            if (viewId.contains("reel_player_fragment") || viewId.contains("shorts_container") || viewId.contains("reel_recycler")) {
                return true
            }
        } else if (packageName == "com.instagram.android") {
            if (viewId.contains("clips_video_container") || viewId.contains("reel_viewer")) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findShortsNode(packageName, child)
            if (found) return true
        }
        return false
    }
}
