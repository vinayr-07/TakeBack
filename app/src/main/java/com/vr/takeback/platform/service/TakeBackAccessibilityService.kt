package com.vr.takeback.platform.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.vr.takeback.TakeBackApplication
import com.vr.takeback.data.repository.ValidatedUsageSnapshot
import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.engine.SystemContextSnapshot
import com.vr.takeback.domain.model.BlockDecision
import com.vr.takeback.domain.model.BlockReason
import com.vr.takeback.domain.model.BlockTarget
import com.vr.takeback.domain.model.EventType
import com.vr.takeback.domain.security.RealAccessibilityNodeSnapshot
import com.vr.takeback.domain.security.SettingsDestination
import com.vr.takeback.domain.security.SettingsProtectionManager
import com.vr.takeback.domain.usage.ForegroundSessionTracker
import com.vr.takeback.presentation.ui.shield.BlockShieldActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList

class TakeBackAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "TakeBackAccessibility"

        var isServiceRunning = false
            private set

        @Volatile
        private var activeServiceInstance: TakeBackAccessibilityService? = null

        /**
         * Called by ScheduleAlarmReceiver or ProfileRepository to immediately re-evaluate
         * the current foreground application when a schedule boundary triggers.
         */
        fun notifyScheduleBoundary(context: Context) {
            activeServiceInstance?.reEvaluateCurrentForegroundApp()
        }

        private const val MIN_REFRESH_INTERVAL_MILLIS = 30_000L
        private const val INITIAL_RETRY_BACKOFF_MILLIS = 10_000L
        private const val MAX_RETRY_BACKOFF_MILLIS = 60_000L

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
    private var currentBackoffMillis: Long = INITIAL_RETRY_BACKOFF_MILLIS
    private var lastBlockedPackage: String? = null
    private var lastBlockedTimestamp: Long = 0
    private var lastBlockedSettingsDestination: SettingsDestination? = null
    private var lastBlockedSettingsTimestamp: Long = 0

    // Build 7 Issue 3 & Build 8 Issue 4: Monotonic elapsed realtime session tracker
    val sessionTracker = ForegroundSessionTracker(
        isExcludedPackage = { pkg ->
            pkg == applicationContext.packageName ||
                    pkg == "com.android.systemui" ||
                    pkg.contains("launcher", ignoreCase = true)
        },
        elapsedRealtimeClock = { SystemClock.elapsedRealtime() }
    )

    // Build 8 Issue 1: Shared single-flight refresh queue
    @Volatile
    private var refreshJob: Job? = null
    private val pendingRefreshCallbacks = CopyOnWriteArrayList<(ValidatedUsageSnapshot) -> Unit>()

    private var screenReceiver: BroadcastReceiver? = null

    /**
     * Build 8 Issue 1: Shared single-flight refresh.
     * Only one repository query executes at a time. Callers register callbacks that are
     * preserved and delivered upon query completion.
     */
    fun triggerUsageSnapshotRefresh(
        force: Boolean = false,
        onFreshAvailable: ((ValidatedUsageSnapshot) -> Unit)? = null
    ) {
        if (onFreshAvailable != null) {
            pendingRefreshCallbacks.add(onFreshAvailable)
        }

        val now = System.currentTimeMillis()
        val elapsed = now - lastUsageSnapshotRefreshTime

        if (refreshJob?.isActive == true) {
            // Query already active; registered callback will receive result when done
            return
        }

        if (!force && elapsed < MIN_REFRESH_INTERVAL_MILLIS) {
            if (onFreshAvailable != null) {
                val active = TakeBackApplication.usageStatsRepository.getActiveUsageSnapshot()
                drainAndDeliverCallbacks(active)
            }
            return
        }
        if (!force && elapsed < currentBackoffMillis) {
            if (onFreshAvailable != null) {
                val active = TakeBackApplication.usageStatsRepository.getActiveUsageSnapshot()
                drainAndDeliverCallbacks(active)
            }
            return
        }

        refreshJob = serviceScope.launch {
            try {
                val fresh = TakeBackApplication.usageStatsRepository.refreshUsageSnapshot()
                lastUsageSnapshotRefreshTime = System.currentTimeMillis()
                currentBackoffMillis = INITIAL_RETRY_BACKOFF_MILLIS // Reset backoff on success
                drainAndDeliverCallbacks(fresh)
            } catch (e: Exception) {
                Log.w(TAG, "Failed usage snapshot refresh", e)
                lastUsageSnapshotRefreshTime = System.currentTimeMillis()
                currentBackoffMillis = minOf(MAX_RETRY_BACKOFF_MILLIS, currentBackoffMillis * 2)
                val fallback = TakeBackApplication.usageStatsRepository.getActiveUsageSnapshot()
                drainAndDeliverCallbacks(fallback)
            }
        }
    }

    private fun drainAndDeliverCallbacks(snapshot: ValidatedUsageSnapshot) {
        val callbacks = ArrayList(pendingRefreshCallbacks)
        pendingRefreshCallbacks.clear()
        for (callback in callbacks) {
            try {
                callback.invoke(snapshot)
            } catch (e: Exception) {
                Log.w(TAG, "Error invoking refresh callback", e)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        activeServiceInstance = this
        Log.i(TAG, "TakeBack Accessibility Service connected and active")

        // Build 8 Issue 4: Query actual device state from PowerManager and KeyguardManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isInteractive = powerManager?.isInteractive ?: true
        val isLocked = keyguardManager?.isKeyguardLocked ?: false

        sessionTracker.initializeState(
            isInteractive = isInteractive,
            isKeyguardLocked = isLocked,
            elapsedRealtimeMillis = SystemClock.elapsedRealtime()
        )

        // Build 7 Issue 3 & Build 8 Issue 4: Register broadcast receiver with monotonic timestamps
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val elapsed = SystemClock.elapsedRealtime()
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                        val isLocked = km?.isKeyguardLocked ?: false
                        sessionTracker.onKeyguardChanged(isLocked, elapsed)
                        sessionTracker.onScreenStateChanged(true, elapsed)
                    }
                    Intent.ACTION_SCREEN_OFF -> sessionTracker.onScreenStateChanged(false, elapsed)
                    Intent.ACTION_USER_PRESENT -> sessionTracker.onKeyguardChanged(false, elapsed)
                }
            }
        }
        screenReceiver = receiver
        try {
            registerReceiver(receiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screen state receiver", e)
        }

        // Initial refresh using the same throttled / single-flight guard
        triggerUsageSnapshotRefresh(force = true)
        startPeriodicUsageCheck()
    }

    private var periodicUsageCheckJob: Job? = null

    private fun startPeriodicUsageCheck() {
        periodicUsageCheckJob?.cancel()
        periodicUsageCheckJob = serviceScope.launch {
            while (isActive) {
                delay(45_000L) // Check every 45 seconds for active session threshold crossing
                try {
                    val essentialRepo = TakeBackApplication.essentialModeRepository
                    val essentialState = essentialRepo.state.value
                    if (essentialState.isEnabled && !essentialState.isEmergencyExitActive) {
                        triggerUsageSnapshotRefresh(force = false) { freshSnapshot ->
                            val totalDailyMinutes = (freshSnapshot.foregroundUsageByPackage.values.sumOf { it } / (60 * 1000L)).toInt()
                            if (totalDailyMinutes >= essentialState.dailyAllowanceMinutes) {
                                reEvaluateCurrentForegroundApp()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error in periodic usage check", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        periodicUsageCheckJob?.cancel()
        periodicUsageCheckJob = null
        if (activeServiceInstance == this) {
            activeServiceInstance = null
        }
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // ignore
            }
            screenReceiver = null
        }
        // Drain any pending callbacks with active cached snapshot so callers are not stranded
        val fallback = TakeBackApplication.usageStatsRepository.getActiveUsageSnapshot()
        drainAndDeliverCallbacks(fallback)
        serviceScope.cancel()
        Log.i(TAG, "TakeBack Accessibility Service destroyed")
    }

    /**
     * Immediately re-evaluates active restrictions against the currently visible foreground application.
     * Invoked at schedule boundaries so restrictions begin/end immediately without waiting for app switch.
     */
    fun reEvaluateCurrentForegroundApp() {
        val currentPkg = sessionTracker.currentPackage ?: return
        if (currentPkg == applicationContext.packageName ||
            currentPkg == "com.android.systemui" ||
            currentPkg.contains("launcher", ignoreCase = true)
        ) {
            return
        }

        val now = System.currentTimeMillis()
        val localTime = LocalTime.now()
        val dayOfWeek = LocalDate.now().dayOfWeek

        val activeProfiles = try {
            TakeBackApplication.profileRepository.profiles.value
        } catch (e: Exception) {
            emptyList()
        }

        val activeSnapshot = TakeBackApplication.usageStatsRepository.getActiveUsageSnapshot()
        val currentSessionDuration = sessionTracker.getCurrentSessionDuration(SystemClock.elapsedRealtime())

        val essentialRepo = TakeBackApplication.essentialModeRepository
        val essentialState = essentialRepo.state.value
        val totalDailyUsageMinutes = (activeSnapshot.foregroundUsageByPackage.values.sumOf { it } / (60 * 1000L)).toInt()
        val isEssentialActive = if (essentialState.isEnabled) {
            essentialRepo.isCurrentlyActive(totalDailyUsageMinutes, true)
        } else false
        val exempt = if (isEssentialActive) {
            TakeBackApplication.essentialModeExemptionResolver.getEffectiveExemptPackages(essentialState.selectedAdditionalPackage)
        } else emptySet()

        val snapshot = SystemContextSnapshot(
            timestampMillis = now,
            localTime = localTime,
            dayOfWeek = dayOfWeek,
            foregroundPackageName = currentPkg,
            accumulatedForegroundTimeMillis = activeSnapshot.foregroundUsageByPackage,
            hourlyForegroundTimeMillis = activeSnapshot.hourlyUsageByPackage,
            currentSessionDurationMillis = currentSessionDuration,
            isUsageDataAvailable = activeSnapshot.isAvailable,
            usageDataQuality = activeSnapshot.quality,
            isEssentialModeActive = isEssentialActive,
            essentialModeAllowanceMinutes = essentialState.dailyAllowanceMinutes,
            essentialModeTotalUsageMinutes = totalDailyUsageMinutes,
            essentialModeExemptPackages = exempt
        )

        val decision = engine.evaluate(activeProfiles, snapshot)
        if (decision is BlockDecision.Blocked) {
            Log.i(TAG, "Schedule boundary immediate block for $currentPkg: ${decision.reason} (Profile: ${decision.triggeringProfileName})")
            lastBlockedPackage = currentPkg
            lastBlockedTimestamp = now
            handleBlockDecision(decision)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // Ignore TakeBack itself
        if (packageName == applicationContext.packageName) return

        val now = System.currentTimeMillis()
        val eventType = event.eventType
        val isWindowTransition = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        // Build 8 Issue 4: Drive foreground transitions ONLY by verified window transitions,
        // ignoring arbitrary content-change or scroll events.
        if (isWindowTransition) {
            sessionTracker.onPackageChanged(packageName, SystemClock.elapsedRealtime())
        }

        val isLauncherOrSystemUi = packageName == "com.android.systemui" ||
                packageName.contains("launcher", ignoreCase = true)

        // Early-return for launcher/systemUI packages (these cannot be blocked by profiles).
        if (isLauncherOrSystemUi) {
            return
        }

        // Strict Mode Anti-Tamper: Restrict Android Settings, App Info, Device Admin, and Uninstall screens
        val strictState = try {
            TakeBackApplication.strictModeRepository.protectionState.value
        } catch (e: Exception) {
            null
        }

        if (strictState?.isSettingsLocked == true) {
            val rootNode = rootInActiveWindow
            val snapshot = if (rootNode != null) RealAccessibilityNodeSnapshot(rootNode) else null
            val decision = SettingsProtectionManager.evaluate(
                packageName = packageName,
                className = event.className?.toString(),
                rootNode = snapshot,
                isStrictLocked = strictState.isSettingsLocked,
                isMaintenanceUnlocked = !strictState.isSettingsLocked
            )

            if (decision.isRestricted) {
                // De-bounce rapid consecutive events for the same blocked settings destination (< 1.2s)
                if (decision.destination == lastBlockedSettingsDestination && (now - lastBlockedSettingsTimestamp) < 1200) {
                    return
                }
                lastBlockedSettingsDestination = decision.destination
                lastBlockedSettingsTimestamp = now

                performGlobalAction(GLOBAL_ACTION_HOME)
                val shieldIntent = Intent(applicationContext, BlockShieldActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("EXTRA_APP_NAME", "Android Settings")
                    putExtra("EXTRA_PROFILE_NAME", "Strict Mode")
                    putExtra("EXTRA_REASON", decision.warningMessage)
                    putExtra("EXTRA_IS_SETTINGS_SHIELD", true)
                    putExtra("EXTRA_DESTINATION", decision.destination.name)
                    putExtra("EXTRA_TARGET_LABEL", decision.targetLabel)
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

        // Core Invariance: If no profiles are enforcing, no usage limits apply, and Quick Block is off, allow all apps immediately
        val localTime = LocalTime.now()
        val dayOfWeek = LocalDate.now().dayOfWeek
        val hasEnforcingProfiles = enabledProfiles.any { profile ->
            engine.isProfileCurrentlyEnforcing(profile, localTime, dayOfWeek)
        }
        val hasUsageLimitForApp = enabledProfiles.any { profile ->
            profile.usageLimits.any { limit ->
                limit.isEnabled && (limit.targetPackageName == null || limit.targetPackageName == packageName)
            }
        }

        val essentialRepo = TakeBackApplication.essentialModeRepository
        val essentialState = essentialRepo.state.value
        val isEssentialEnabled = essentialState.isEnabled

        if (!hasEnforcingProfiles && !isQuickBlockActive && !hasUsageLimitForApp && !isEssentialEnabled) {
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

        val totalDailyUsageMinutes = ((activeSnapshot?.foregroundUsageByPackage?.values?.sumOf { it } ?: 0L) / (60 * 1000L)).toInt()
        val isEssentialModeActive = if (isEssentialEnabled) {
            essentialRepo.isCurrentlyActive(
                totalDailyUsageMinutes = totalDailyUsageMinutes,
                isEnforcementAvailable = true
            )
        } else false

        val essentialExemptPackages = if (isEssentialModeActive) {
            TakeBackApplication.essentialModeExemptionResolver.getEffectiveExemptPackages(essentialState.selectedAdditionalPackage)
        } else emptySet()

        val currentSessionDuration = sessionTracker.getCurrentSessionDuration(SystemClock.elapsedRealtime())

        // Build current context snapshot
        val snapshot = SystemContextSnapshot(
            timestampMillis = now,
            localTime = localTime,
            dayOfWeek = dayOfWeek,
            foregroundPackageName = packageName,
            activeBrowserUrl = browserUrl,
            accumulatedForegroundTimeMillis = activeSnapshot?.foregroundUsageByPackage ?: emptyMap(),
            hourlyForegroundTimeMillis = activeSnapshot?.hourlyUsageByPackage ?: emptyMap(),
            currentSessionDurationMillis = currentSessionDuration,
            isUsageDataAvailable = activeSnapshot?.isAvailable ?: false,
            usageDataQuality = activeSnapshot?.quality ?: com.vr.takeback.domain.usage.UsageDataQuality.UNKNOWN,
            isQuickBlockActive = isQuickBlockActive,
            quickBlockExpirationEpochMillis = quickBlockExpiry,
            quickBlockTargetPackages = quickBlockPackages,
            isEssentialModeActive = isEssentialModeActive,
            essentialModeAllowanceMinutes = essentialState.dailyAllowanceMinutes,
            essentialModeTotalUsageMinutes = totalDailyUsageMinutes,
            essentialModeExemptPackages = essentialExemptPackages
        )

        val immediateDecision = engine.evaluate(activeProfiles, snapshot)

        if (immediateDecision is BlockDecision.Blocked) {
            Log.i(TAG, "Blocking $packageName immediately because: ${immediateDecision.reason} (Profile: ${immediateDecision.triggeringProfileName})")
            lastBlockedPackage = packageName
            lastBlockedTimestamp = now
            handleBlockDecision(immediateDecision)
            return
        }

        // Build 7 Issue 4 & Build 8 Issue 1: Controlled refresh triggers.
        // Refresh only on window state changes (not on scrolling or content-changes).
        val isSnapshotFresh = activeSnapshot?.isAvailable == true && (now - activeSnapshot.capturedAtMillis <= 30_000L)
        if ((hasUsageLimitForApp || isEssentialEnabled) && !isSnapshotFresh && isWindowTransition) {
            triggerUsageSnapshotRefresh(force = false) { fresh ->
                if (sessionTracker.currentPackage == packageName) {
                    val freshSessionDuration = sessionTracker.getCurrentSessionDuration(SystemClock.elapsedRealtime())
                    val freshTotalDailyMinutes = (fresh.foregroundUsageByPackage.values.sumOf { it } / (60 * 1000L)).toInt()
                    val freshEssentialActive = if (isEssentialEnabled) {
                        essentialRepo.isCurrentlyActive(freshTotalDailyMinutes, true)
                    } else false
                    val freshExempt = if (freshEssentialActive) {
                        TakeBackApplication.essentialModeExemptionResolver.getEffectiveExemptPackages(essentialState.selectedAdditionalPackage)
                    } else emptySet()

                    val updatedSnapshot = snapshot.copy(
                        timestampMillis = System.currentTimeMillis(),
                        accumulatedForegroundTimeMillis = fresh.foregroundUsageByPackage,
                        // Build 7 Issue 1: Propagate fresh hourlyForegroundTimeMillis to delayed evaluation
                        hourlyForegroundTimeMillis = fresh.hourlyUsageByPackage,
                        currentSessionDurationMillis = freshSessionDuration,
                        isUsageDataAvailable = fresh.isAvailable,
                        usageDataQuality = fresh.quality,
                        isEssentialModeActive = freshEssentialActive,
                        essentialModeAllowanceMinutes = essentialState.dailyAllowanceMinutes,
                        essentialModeTotalUsageMinutes = freshTotalDailyMinutes,
                        essentialModeExemptPackages = freshExempt
                    )
                    val delayedDecision = engine.evaluate(activeProfiles, updatedSnapshot)
                    if (delayedDecision is BlockDecision.Blocked && sessionTracker.currentPackage == packageName) {
                        Log.i(TAG, "Delayed block on fresh snapshot for $packageName: ${delayedDecision.reason}")
                        lastBlockedPackage = packageName
                        lastBlockedTimestamp = System.currentTimeMillis()
                        handleBlockDecision(delayedDecision)
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted by system")
        sessionTracker.reset()
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
            is BlockReason.SessionQuotaExceeded -> "Session limit reached (${decision.reason.limitMinutes}m)"
            is BlockReason.DailyAllowanceExceeded -> "Daily allowance reached (${decision.reason.allowanceMinutes / 60}h ${decision.reason.allowanceMinutes % 60}m)"
            is BlockReason.FocusSessionActive -> "Deep Focus sprint in progress"
            is BlockReason.QuickBlockActive -> "Quick Block active"
            is BlockReason.DomainBlock -> "Restricted website (${decision.reason.domain})"
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
        val isEssentialModeShield = decision.reason is BlockReason.DailyAllowanceExceeded
        val allowanceMins = if (decision.reason is BlockReason.DailyAllowanceExceeded) {
            (decision.reason as BlockReason.DailyAllowanceExceeded).allowanceMinutes
        } else 0
        val usedMins = if (decision.reason is BlockReason.DailyAllowanceExceeded) {
            (decision.reason as BlockReason.DailyAllowanceExceeded).usedMinutes
        } else 0

        val shieldIntent = Intent(applicationContext, BlockShieldActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_APP_NAME", appName)
            putExtra("EXTRA_PROFILE_NAME", decision.triggeringProfileName)
            putExtra("EXTRA_REASON", reasonText)
            putExtra("EXTRA_IS_ESSENTIAL_SHIELD", isEssentialModeShield)
            putExtra("EXTRA_ALLOWANCE_MINS", allowanceMins)
            putExtra("EXTRA_USED_MINS", usedMins)
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
