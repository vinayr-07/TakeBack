package com.vr.takeback.platform.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.vr.takeback.TakeBackApplication
import com.vr.takeback.data.repository.ValidatedUsageSnapshot
import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.engine.SystemContextSnapshot
import com.vr.takeback.domain.model.BlockDecision
import com.vr.takeback.domain.model.BlockReason
import com.vr.takeback.domain.model.BlockTarget
import com.vr.takeback.domain.model.EventType
import com.vr.takeback.domain.model.FocusSessionState
import com.vr.takeback.domain.model.Profile
import com.vr.takeback.domain.security.ProtectionDecision
import com.vr.takeback.domain.security.RealAccessibilityNodeSnapshot
import com.vr.takeback.domain.security.RealRecentsNodeSnapshot
import com.vr.takeback.domain.security.RecentsCardMatch
import com.vr.takeback.domain.security.RecentsTaskInspector
import com.vr.takeback.domain.security.SettingsDestination
import com.vr.takeback.domain.security.SettingsProtectionManager
import com.vr.takeback.domain.security.SocialMediaResolver
import com.vr.takeback.domain.usage.ForegroundSessionTracker
import com.vr.takeback.domain.usage.SocialMediaSessionTracker
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
    private var serviceConnectedTimestamp: Long = 0L
    @Volatile
    private var awaitingPostEnableSettingsExit: Boolean = false
    @Volatile
    private var isStickyTakeBackAppInfoActive: Boolean = false
    @Volatile
    private var hasTamperedSettingsInRecents: Boolean = false
    private var lastRecentsDismissTimestamp: Long = 0L
    private val recentlyBlockedApps = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Build 7 Issue 3 & Build 8 Issue 4: Monotonic elapsed realtime session tracker
    val sessionTracker = ForegroundSessionTracker(
        isExcludedPackage = { pkg ->
            pkg == applicationContext.packageName ||
                    pkg == "com.android.systemui" ||
                    pkg.contains("launcher", ignoreCase = true)
        },
        elapsedRealtimeClock = { SystemClock.elapsedRealtime() }
    )

    // Cross-App Social Media 5-Minute Session Limit & 55-Minute Cooldown Tracker
    val socialSessionTracker = SocialMediaSessionTracker(
        isSocialPackage = { pkg ->
            SocialMediaResolver.isSocialMediaPackage(pkg) { targetPkg ->
                try {
                    val appInfo = packageManager.getApplicationInfo(targetPkg, 0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appInfo.category
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
        },
        elapsedRealtimeClock = { SystemClock.elapsedRealtime() },
        wallClock = { System.currentTimeMillis() },
        onCooldownTriggered = { cooldownUntilEpoch ->
            try {
                TakeBackApplication.socialMediaCooldownRepository.setCooldownUntilEpochMillis(cooldownUntilEpoch)
            } catch (e: Exception) {
                // ignore
            }
        }
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
        serviceConnectedTimestamp = SystemClock.elapsedRealtime()
        awaitingPostEnableSettingsExit = true
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

        try {
            val persistedCooldown = TakeBackApplication.socialMediaCooldownRepository.getCooldownUntilEpochMillis()
            socialSessionTracker.setPersistedCooldown(persistedCooldown)
        } catch (e: Exception) {
            // ignore
        }
        socialSessionTracker.initializeState(
            isInteractive = isInteractive,
            isKeyguardLocked = isLocked,
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            wallClockEpochMillis = System.currentTimeMillis()
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
                val now = System.currentTimeMillis()
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                        val isLocked = km?.isKeyguardLocked ?: false
                        sessionTracker.onKeyguardChanged(isLocked, elapsed)
                        sessionTracker.onScreenStateChanged(true, elapsed)
                        socialSessionTracker.onKeyguardChanged(isLocked, elapsed, now)
                        socialSessionTracker.onScreenStateChanged(true, elapsed, now)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        sessionTracker.onScreenStateChanged(false, elapsed)
                        socialSessionTracker.onScreenStateChanged(false, elapsed, now)
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        sessionTracker.onKeyguardChanged(false, elapsed)
                        socialSessionTracker.onKeyguardChanged(false, elapsed, now)
                    }
                }
            }
        }
        screenReceiver = receiver
        try {
            registerReceiver(receiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screen state receiver", e)
        }

        // Warm package info cache off main thread & trigger initial usage snapshot refresh
        serviceScope.launch {
            try {
                TakeBackApplication.packageInfoProvider.refreshSnapshotNow()
            } catch (_: Exception) {}
        }
        triggerUsageSnapshotRefresh(force = true)
        startPeriodicUsageCheck()
    }

    private var periodicUsageCheckJob: Job? = null

    private fun startPeriodicUsageCheck() {
        periodicUsageCheckJob?.cancel()
        periodicUsageCheckJob = serviceScope.launch {
            while (isActive) {
                delay(15_000L) // Check every 15 seconds for active session threshold crossing
                try {
                    // Check if active social media session crossed 5-minute threshold
                    val socialDuration = socialSessionTracker.getCurrentSocialSessionDuration(SystemClock.elapsedRealtime())
                    if (socialDuration >= 5 * 60 * 1000L) {
                        socialSessionTracker.triggerCooldown(System.currentTimeMillis())
                        reEvaluateCurrentForegroundApp()
                    }

                    val essentialRepo = TakeBackApplication.essentialModeRepository
                    val essentialState = essentialRepo.state.value
                    if (essentialState.isEnabled && !essentialState.isEmergencyExitActive) {
                        triggerUsageSnapshotRefresh(force = false) { freshSnapshot ->
                            val deviceScreenTime = freshSnapshot.deviceScreenTimeMillis
                            val totalDailyMinutes = if (freshSnapshot.isDeviceScreenTimeAvailable && deviceScreenTime != null) {
                                (deviceScreenTime / (60 * 1000L)).toInt()
                            } else {
                                (freshSnapshot.foregroundUsageByPackage.values.sumOf { it } / (60 * 1000L)).toInt()
                            }
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
        val isDeviceScreenTimeAvailable = activeSnapshot.isDeviceScreenTimeAvailable && activeSnapshot.deviceScreenTimeMillis != null
        val totalDailyUsageMinutes = if (isDeviceScreenTimeAvailable) {
            ((activeSnapshot.deviceScreenTimeMillis ?: 0L) / (60 * 1000L)).toInt()
        } else {
            (activeSnapshot.foregroundUsageByPackage.values.sumOf { it } / (60 * 1000L)).toInt()
        }
        val isEssentialActive = if (essentialState.isEnabled) {
            essentialRepo.isCurrentlyActive(totalDailyUsageMinutes, true, isDeviceScreenTimeAvailable || activeSnapshot.isAvailable)
        } else false
        val exempt = if (isEssentialActive) {
            getCachedEffectiveExemptPackages(essentialState.selectedAdditionalPackages, essentialState.customGoodLockModules)
        } else emptySet()

        val quickBlockState = try {
            TakeBackApplication.quickBlockRepository.state.value
        } catch (e: Exception) {
            null
        }
        val isQuickBlockActive = quickBlockState?.isActive == true
        val quickBlockExpiry = if (isQuickBlockActive) {
            (quickBlockState?.startEpochMillis ?: now) + ((quickBlockState?.durationMinutes ?: 0) * 60 * 1000L)
        } else null
        val quickBlockPackages = quickBlockState?.targetPackages ?: emptySet()

        val focusSession = try {
            TakeBackApplication.focusSessionRepository.session.value
        } catch (e: Exception) {
            null
        }
        val isFocusSessionActive = focusSession?.state == FocusSessionState.FOCUS_RUNNING
        val isBreakActive = focusSession?.state == FocusSessionState.BREAK_RUNNING
        val activeFocusProfileId = focusSession?.activeProfileId
        val breakWhitelistApps = focusSession?.breakWhitelistApps ?: emptySet()

        val currentPkg = sessionTracker.currentPackage ?: ""
        val isSocialCooldown = socialSessionTracker.isCooldownActive(now, SystemClock.elapsedRealtime())
        val socialRemainingMinutes = socialSessionTracker.getCooldownRemainingMinutes(now, SystemClock.elapsedRealtime())
        val socialSessionDuration = socialSessionTracker.getCurrentSocialSessionDuration(SystemClock.elapsedRealtime())

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
            isFocusSessionActive = isFocusSessionActive,
            isBreakActive = isBreakActive,
            activeFocusSessionProfileId = activeFocusProfileId,
            breakWhitelistApps = breakWhitelistApps,
            isQuickBlockActive = isQuickBlockActive,
            quickBlockExpirationEpochMillis = quickBlockExpiry,
            quickBlockTargetPackages = quickBlockPackages,
            isEssentialModeActive = isEssentialActive,
            essentialModeAllowanceMinutes = essentialState.dailyAllowanceMinutes,
            essentialModeTotalUsageMinutes = totalDailyUsageMinutes,
            essentialModeExemptPackages = exempt,
            isSocialCooldownActive = isSocialCooldown,
            socialCooldownRemainingMinutes = socialRemainingMinutes,
            socialSessionDurationMillis = socialSessionDuration
        )

        // Audit all active windows first (Multi-Window Split-Screen, Pop-up View, and PiP)
        if (auditAllActiveWindows(activeProfiles, snapshot, now, currentPkg)) {
            return
        }

        if (currentPkg.isEmpty() ||
            currentPkg == applicationContext.packageName ||
            currentPkg == "com.android.systemui" ||
            currentPkg.contains("launcher", ignoreCase = true)
        ) {
            return
        }

        val decision = engine.evaluate(activeProfiles, snapshot)
        if (decision is BlockDecision.Blocked) {
            Log.i(TAG, "Schedule boundary immediate block for $currentPkg: ${decision.reason} (Profile: ${decision.triggeringProfileName})")
            lastBlockedPackage = currentPkg
            lastBlockedTimestamp = now
            handleBlockDecision(decision)
        }
    }

    /**
     * Immediately pauses background media/video audio playback across the device
     * by requesting transient audio focus from AudioManager.
     */
    private fun requestMediaAudioFocusPause() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request media audio focus pause", e)
        }
    }

    /**
     * Audits all currently visible interactive windows (including Split-Screen, Freeform / Pop-up view,
     * and Picture-in-Picture) to ensure restricted applications cannot be run in multi-window or floating modes.
     *
     * Returns true if a blocked window was detected and handled.
     */
    private fun auditAllActiveWindows(
        activeProfiles: List<Profile>,
        baseSnapshot: SystemContextSnapshot,
        now: Long,
        currentForegroundPackage: String? = null
    ): Boolean {
        // Debounce: do not re-audit within 2500ms of any block action to prevent event storms during animations
        if ((now - lastBlockedTimestamp) < 2500L) {
            return false
        }

        val currentWindows = try {
            windows
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query interactive windows hierarchy", e)
            null
        }

        if (currentWindows.isNullOrEmpty()) {
            return false
        }

        // If TakeBack's own shield or UI is already the top/visible window, do not trigger duplicate blocks
        val isTakeBackShieldActive = currentWindows.any { window ->
            try {
                window.root?.packageName == applicationContext.packageName
            } catch (e: Exception) {
                false
            }
        }
        if (isTakeBackShieldActive) {
            return false
        }

        for (window in currentWindows) {
            val isPiP = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && window.isInPictureInPictureMode
            val isAppWindow = window.type == AccessibilityWindowInfo.TYPE_APPLICATION

            if (!isAppWindow && !isPiP) {
                continue
            }

            // Only inspect genuinely interactive/visible windows: must be active, focused, or in PiP
            val isInteractive = window.isActive || window.isFocused || isPiP
            if (!isInteractive) {
                continue
            }

            // Window must have non-empty visible dimensions on screen
            val bounds = android.graphics.Rect()
            window.getBoundsInScreen(bounds)
            if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) {
                continue
            }

            val rootNode = try {
                window.root
            } catch (e: Exception) {
                null
            } ?: continue

            val windowPkg = rootNode.packageName?.toString() ?: continue
            if (windowPkg.isBlank() ||
                windowPkg == applicationContext.packageName ||
                windowPkg == "android" ||
                windowPkg == "com.android.systemui" ||
                windowPkg.contains("launcher", ignoreCase = true)
            ) {
                continue
            }

            // If this window's package is the foreground package currently being evaluated, skip duplicate audit
            if (windowPkg == currentForegroundPackage) {
                continue
            }

            // De-bounce rapid consecutive blocks for the same window package (< 5.0s)
            if (windowPkg == lastBlockedPackage && (now - lastBlockedTimestamp) < 5000L) {
                continue
            }

            // Evaluate multi-window / pop-up / split-screen Settings anti-tamper first when Strict Mode is locked
            val strictState = try {
                TakeBackApplication.strictModeRepository.protectionState.value
            } catch (e: Exception) {
                null
            }
            if (strictState?.isSettingsLocked == true && SettingsProtectionManager.isProtectedSettingsPackage(windowPkg)) {
                val settingsDecision = SettingsProtectionManager.evaluate(
                    packageName = windowPkg,
                    className = null,
                    rootNode = RealAccessibilityNodeSnapshot(rootNode),
                    isStrictLocked = true,
                    isMaintenanceUnlocked = false
                )
                if (settingsDecision.isRestricted && !shouldBypassDuringInitialEnable(settingsDecision.destination)) {
                    if (settingsDecision.destination == lastBlockedSettingsDestination && (now - lastBlockedSettingsTimestamp) < 650L) {
                        return false
                    }
                    try {
                        rootNode.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                    } catch (_: Exception) {}
                    launchSettingsShield(settingsDecision, now)
                    return true
                }
            }

            // Evaluate this specific window's package against active restrictions
            val windowSnapshot = baseSnapshot.copy(
                foregroundPackageName = windowPkg,
                activeBrowserUrl = if (SUPPORTED_BROWSERS.contains(windowPkg)) extractBrowserUrl(rootNode) else null
            )

            val decision = engine.evaluate(activeProfiles, windowSnapshot)
            if (decision is BlockDecision.Blocked) {
                Log.i(TAG, "Multi-window / PiP restriction triggered for $windowPkg: ${decision.reason} (Profile: ${decision.triggeringProfileName}, isPiP: $isPiP)")

                // If it is a Picture-in-Picture window, dismiss the PiP window directly
                if (isPiP) {
                    try {
                        rootNode.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to dismiss PiP window for $windowPkg", e)
                    }
                } else {
                    // Freeform pop-up view or multi-window: dismiss or send back if focused
                    try {
                        rootNode.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                    } catch (e: Exception) {
                        // ignore
                    }
                    if (window.isFocused) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }

                // Halt background/video audio playback immediately
                requestMediaAudioFocusPause()

                lastBlockedPackage = windowPkg
                lastBlockedTimestamp = now
                handleBlockDecision(decision)
                return true
            }
        }

        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // Ignore TakeBack itself and core Android system framework; returning to TakeBack ends initial enable grace window.
        if (packageName == applicationContext.packageName) {
            awaitingPostEnableSettingsExit = false
            isStickyTakeBackAppInfoActive = false
            return
        }
        if (packageName == "android") return

        val now = System.currentTimeMillis()
        val eventType = event.eventType
        val isWindowTransition = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        if (isWindowTransition &&
            !SettingsProtectionManager.isProtectedSettingsPackage(packageName) &&
            packageName != "com.android.systemui"
        ) {
            awaitingPostEnableSettingsExit = false
            isStickyTakeBackAppInfoActive = false
        }

        // Build 8 Issue 4: Drive foreground transitions ONLY by verified window transitions,
        // ignoring arbitrary content-change or scroll events.
        if (isWindowTransition) {
            sessionTracker.onPackageChanged(packageName, SystemClock.elapsedRealtime())
            socialSessionTracker.onPackageChanged(packageName, SystemClock.elapsedRealtime(), now)
        }

        val isLauncherOrSystemUi = packageName == "com.android.systemui" ||
                packageName.contains("launcher", ignoreCase = true)

        // Fast-path 1: Ignore TakeBack's own UI events while TakeBack is active foreground,
        // without accidentally dropping incoming Settings or blocked-app transitions.
        if (packageName == applicationContext.packageName &&
            rootInActiveWindow?.packageName == applicationContext.packageName
        ) {
            return
        }

        val strictState = try {
            TakeBackApplication.strictModeRepository.protectionState.value
        } catch (e: Exception) {
            null
        }

        // Fast-path 2: Enforce Strict Mode Settings/Admin/AppInfo protection BEFORE any UsageStats or package work.
        if (strictState?.isSettingsLocked == true && SettingsProtectionManager.isProtectedSettingsPackage(packageName)) {
            if (evaluateAndHandleSettingsProtection(packageName, event, now)) {
                return
            }
        }

        // Fast-path 3: Drop high-frequency content-change noise for apps that aren't transitions, clicks, launchers, browsers, or video feeds.
        val isShortFormOrBrowser = packageName == "com.google.android.youtube" ||
                packageName == "com.instagram.android" ||
                SUPPORTED_BROWSERS.contains(packageName)
        if (!isWindowTransition &&
            eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            !isLauncherOrSystemUi &&
            !isShortFormOrBrowser
        ) {
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

        val focusSession = try {
            TakeBackApplication.focusSessionRepository.session.value
        } catch (e: Exception) {
            null
        }
        val isFocusSessionActive = focusSession?.state == FocusSessionState.FOCUS_RUNNING
        val isBreakActive = focusSession?.state == FocusSessionState.BREAK_RUNNING
        val activeFocusProfileId = focusSession?.activeProfileId
        val breakWhitelistApps = focusSession?.breakWhitelistApps ?: emptySet()

        val isSocialCooldownActive = socialSessionTracker.isCooldownActive(now, SystemClock.elapsedRealtime())
        val socialCooldownRemainingMins = socialSessionTracker.getCooldownRemainingMinutes(now, SystemClock.elapsedRealtime())
        val currentSocialSessionDuration = socialSessionTracker.getCurrentSocialSessionDuration(SystemClock.elapsedRealtime())
        val isTargetSocial = SocialMediaResolver.isSocialMediaPackage(packageName)

        val areAnyRestrictionsPotentiallyActive = hasEnforcingProfiles || isQuickBlockActive ||
                hasUsageLimitForApp || isEssentialEnabled || isFocusSessionActive || isBreakActive ||
                isSocialCooldownActive || isTargetSocial

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

        val deviceScreenTimeMillis = activeSnapshot?.deviceScreenTimeMillis
        val isDeviceScreenTimeAvailable = activeSnapshot?.isDeviceScreenTimeAvailable == true && deviceScreenTimeMillis != null
        val totalDailyUsageMinutes = if (isDeviceScreenTimeAvailable) {
            ((deviceScreenTimeMillis ?: 0L) / (60 * 1000L)).toInt()
        } else {
            ((activeSnapshot?.foregroundUsageByPackage?.values?.sumOf { it } ?: 0L) / (60 * 1000L)).toInt()
        }

        val isEssentialModeActive = if (isEssentialEnabled) {
            essentialRepo.isCurrentlyActive(
                totalDailyUsageMinutes = totalDailyUsageMinutes,
                isEnforcementAvailable = true,
                isUsageMetricAvailable = isDeviceScreenTimeAvailable || (activeSnapshot?.isAvailable == true)
            )
        } else false

        val essentialExemptPackages = if (isEssentialModeActive) {
            getCachedEffectiveExemptPackages(essentialState.selectedAdditionalPackages, essentialState.customGoodLockModules)
        } else emptySet()

        val currentSessionDuration = sessionTracker.getCurrentSessionDuration(SystemClock.elapsedRealtime())

        // Extract browser URL if inside a supported browser
        val browserUrl = if (SUPPORTED_BROWSERS.contains(packageName)) {
            extractBrowserUrl(rootInActiveWindow)
        } else null

        // Base context snapshot for restriction evaluation
        val baseSnapshot = SystemContextSnapshot(
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
            isFocusSessionActive = isFocusSessionActive,
            isBreakActive = isBreakActive,
            activeFocusSessionProfileId = activeFocusProfileId,
            breakWhitelistApps = breakWhitelistApps,
            isQuickBlockActive = isQuickBlockActive,
            quickBlockExpirationEpochMillis = quickBlockExpiry,
            quickBlockTargetPackages = quickBlockPackages,
            isEssentialModeActive = isEssentialModeActive,
            essentialModeAllowanceMinutes = essentialState.dailyAllowanceMinutes,
            essentialModeTotalUsageMinutes = totalDailyUsageMinutes,
            essentialModeExemptPackages = essentialExemptPackages,
            isSocialCooldownActive = isSocialCooldownActive,
            socialCooldownRemainingMinutes = socialCooldownRemainingMins,
            socialSessionDurationMillis = currentSocialSessionDuration
        )

        // Multi-Window & Floating Window (Split-Screen, Pop-up View, Picture-in-Picture) Audit:
        // Audit all interactive windows whenever a window transition occurs or launcher is active
        if ((areAnyRestrictionsPotentiallyActive || strictState?.isSettingsLocked == true) &&
            (isWindowTransition || isLauncherOrSystemUi)
        ) {
            if (auditAllActiveWindows(activeProfiles, baseSnapshot, now, packageName)) {
                return
            }
        }

        // On Launcher / SystemUI: inspect Recent Apps / Overview to clear tampered Settings or blocked apps from background
        if (isLauncherOrSystemUi) {
            handleRecentsOverview(
                packageName = packageName,
                className = event.className?.toString(),
                now = now,
                activeProfiles = activeProfiles,
                quickBlockPackages = quickBlockPackages,
                isQuickBlockActive = isQuickBlockActive,
                isSocialCooldownActive = isSocialCooldownActive,
                isStrictLocked = strictState?.isSettingsLocked == true
            )
            return
        }

        // De-bounce rapid consecutive events for the same blocked package (< 10.0s)
        if (packageName == lastBlockedPackage && (now - lastBlockedTimestamp) < 10000) {
            return
        }

        // Only evaluate full blocking on true window transitions or user clicks to prevent background event storms
        if (!isWindowTransition && eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return
        }

        if (!areAnyRestrictionsPotentiallyActive) {
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

        val immediateDecision = engine.evaluate(activeProfiles, baseSnapshot)

        if (immediateDecision is BlockDecision.Blocked) {
            Log.i(TAG, "Blocking $packageName immediately because: ${immediateDecision.reason} (Profile: ${immediateDecision.triggeringProfileName})")
            lastBlockedPackage = packageName
            lastBlockedTimestamp = now
            handleBlockDecision(immediateDecision)
            return
        }

        // Controlled refresh triggers on window transitions
        val isSnapshotFresh = activeSnapshot?.isAvailable == true && (now - activeSnapshot.capturedAtMillis <= 30_000L)
        if ((hasUsageLimitForApp || isEssentialEnabled) && !isSnapshotFresh && isWindowTransition) {
            triggerUsageSnapshotRefresh(force = false) { fresh ->
                if (sessionTracker.currentPackage == packageName) {
                    val freshSessionDuration = sessionTracker.getCurrentSessionDuration(SystemClock.elapsedRealtime())
                    val freshScreenTime = fresh.deviceScreenTimeMillis
                    val freshScreenTimeAvailable = fresh.isDeviceScreenTimeAvailable && freshScreenTime != null
                    val freshTotalDailyMinutes = if (freshScreenTimeAvailable) {
                        ((freshScreenTime ?: 0L) / (60 * 1000L)).toInt()
                    } else {
                        (fresh.foregroundUsageByPackage.values.sumOf { it } / (60 * 1000L)).toInt()
                    }
                    val freshEssentialActive = if (isEssentialEnabled) {
                        essentialRepo.isCurrentlyActive(
                            totalDailyUsageMinutes = freshTotalDailyMinutes,
                            isEnforcementAvailable = true,
                            isUsageMetricAvailable = freshScreenTimeAvailable || fresh.isAvailable
                        )
                    } else false
                    val freshExempt = if (freshEssentialActive) {
                        getCachedEffectiveExemptPackages(essentialState.selectedAdditionalPackages, essentialState.customGoodLockModules)
                    } else emptySet()

                    val updatedSnapshot = baseSnapshot.copy(
                        timestampMillis = System.currentTimeMillis(),
                        accumulatedForegroundTimeMillis = fresh.foregroundUsageByPackage,
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
        socialSessionTracker.reset()
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
            is BlockReason.CooldownActive -> "${decision.reason.targetCategory} cooldown active (${decision.reason.cooldownRemainingMinutes}m remaining)"
            is BlockReason.FocusSessionActive -> "Deep Focus sprint in progress"
            is BlockReason.QuickBlockActive -> "Quick Block active"
            is BlockReason.DomainBlock -> "Restricted website (${decision.reason.domain})"
            is BlockReason.KeywordMatch -> "Blocked by keyword '${decision.reason.keyword}'"
            is BlockReason.GeofenceActive -> "Restricted at this location"
            is BlockReason.GoalNotMet -> "Productivity goal required first"
        }

        // Halt background/video audio playback immediately
        requestMediaAudioFocusPause()

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

        val isCooldownShield = decision.reason is BlockReason.CooldownActive
        val cooldownRemainingMins = if (decision.reason is BlockReason.CooldownActive) {
            (decision.reason as BlockReason.CooldownActive).cooldownRemainingMinutes
        } else 0
        val targetCategory = if (decision.reason is BlockReason.CooldownActive) {
            (decision.reason as BlockReason.CooldownActive).targetCategory
        } else "Social Media"

        val shieldIntent = Intent(applicationContext, BlockShieldActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_APP_NAME", appName)
            putExtra("EXTRA_PROFILE_NAME", decision.triggeringProfileName)
            putExtra("EXTRA_REASON", reasonText)
            putExtra("EXTRA_IS_ESSENTIAL_SHIELD", isEssentialModeShield)
            putExtra("EXTRA_IS_COOLDOWN_SHIELD", isCooldownShield)
            putExtra("EXTRA_COOLDOWN_REMAINING_MINS", cooldownRemainingMins)
            putExtra("EXTRA_TARGET_CATEGORY", targetCategory)
            putExtra("EXTRA_ALLOWANCE_MINS", allowanceMins)
            putExtra("EXTRA_USED_MINS", usedMins)
        }
        startActivity(shieldIntent)

        // Track & clear the restricted app from background once it moves behind BlockShieldActivity
        val blockedTarget = decision.target
        if (blockedTarget is BlockTarget.Application) {
            recentlyBlockedApps[blockedTarget.packageName] = appName
            scheduleBackgroundProcessCleanup(blockedTarget.packageName)
        }
    }

    @Volatile
    private var cachedExemptPackages: Set<String> = emptySet()
    @Volatile
    private var cachedExemptAdditional: List<String> = emptyList()
    @Volatile
    private var cachedExemptGoodLock: Set<String> = emptySet()
    @Volatile
    private var cachedExemptTimestampElapsed: Long = 0L

    private fun getCachedEffectiveExemptPackages(
        selectedAdditional: List<String>,
        customGoodLock: Set<String>
    ): Set<String> {
        val elapsed = SystemClock.elapsedRealtime()
        val existing = cachedExemptPackages
        if (existing.isNotEmpty() &&
            (elapsed - cachedExemptTimestampElapsed) < 30_000L &&
            selectedAdditional == cachedExemptAdditional &&
            customGoodLock == cachedExemptGoodLock
        ) {
            return existing
        }
        val computed = TakeBackApplication.essentialModeExemptionResolver.getEffectiveExemptPackages(selectedAdditional)
        cachedExemptPackages = computed
        cachedExemptAdditional = selectedAdditional
        cachedExemptGoodLock = customGoodLock
        cachedExemptTimestampElapsed = elapsed
        return computed
    }

    private fun shouldBypassDuringInitialEnable(destination: SettingsDestination): Boolean {
        if (!awaitingPostEnableSettingsExit) return false
        val elapsedSinceConnect = SystemClock.elapsedRealtime() - serviceConnectedTimestamp
        if (elapsedSinceConnect >= 5_000L) {
            awaitingPostEnableSettingsExit = false
            return false
        }
        return destination == SettingsDestination.ACCESSIBILITY_SETTINGS ||
                destination == SettingsDestination.INSTALLED_ACCESSIBILITY_SERVICES ||
                destination == SettingsDestination.TAKEBACK_ACCESSIBILITY_DETAILS
    }

    private fun evaluateAndHandleSettingsProtection(
        packageName: String,
        event: AccessibilityEvent,
        now: Long
    ): Boolean {
        val rootNode = rootInActiveWindow ?: event.source
        val snapshot = if (rootNode != null) RealAccessibilityNodeSnapshot(rootNode) else null
        val className = event.className?.toString()
        val eventText = event.text?.joinToString(" ")

        var decision = SettingsProtectionManager.evaluate(
            packageName = packageName,
            className = className,
            rootNode = snapshot,
            isStrictLocked = true,
            isMaintenanceUnlocked = false,
            eventText = eventText
        )

        val clsLower = (className ?: "").lowercase()
        val isAppInfoSubscreen = clsLower.contains("installedappdetails") ||
                clsLower.contains("appinfo") ||
                clsLower.contains("appdetails") ||
                clsLower.contains("installedappopenbydefault") ||
                packageName.equals("com.oplus.appdetail", ignoreCase = true) ||
                packageName.equals("com.coloros.appdetail", ignoreCase = true)

        if (decision.destination == SettingsDestination.TAKEBACK_APP_INFO) {
            isStickyTakeBackAppInfoActive = true
        } else if (!isAppInfoSubscreen &&
            decision.destination != SettingsDestination.TAKEBACK_PERMISSION_SETTINGS &&
            decision.destination != SettingsDestination.UNKNOWN_OR_INACCESSIBLE
        ) {
            isStickyTakeBackAppInfoActive = false
        }

        // Prevent scroll-past-header bypass inside TakeBack's App Info on Samsung / Realme
        if (!decision.isRestricted && isStickyTakeBackAppInfoActive && isAppInfoSubscreen) {
            decision = ProtectionDecision(
                isRestricted = true,
                destination = SettingsDestination.TAKEBACK_APP_INFO,
                targetLabel = "App Info Protection",
                warningMessage = "TakeBack's App Info, Force Stop, Storage, and Uninstall settings are locked while Strict Mode is active."
            )
        }

        if (!decision.isRestricted) {
            return false
        }

        if (shouldBypassDuringInitialEnable(decision.destination)) {
            return false
        }

        if (decision.destination == lastBlockedSettingsDestination && (now - lastBlockedSettingsTimestamp) < 650L) {
            return true
        }

        Log.i(TAG, "Blocking restricted settings screen: pkg=$packageName cls=$className dest=${decision.destination}")
        launchSettingsShield(decision, now)
        return true
    }

    private fun launchSettingsShield(decision: ProtectionDecision, now: Long) {
        lastBlockedSettingsDestination = decision.destination
        lastBlockedSettingsTimestamp = now
        hasTamperedSettingsInRecents = true

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
    }

    private fun scheduleBackgroundProcessCleanup(packageName: String) {
        if (packageName == applicationContext.packageName ||
            packageName == "android" ||
            SettingsProtectionManager.isProtectedSettingsPackage(packageName)
        ) {
            return
        }
        serviceScope.launch {
            delay(350L)
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@launch
                am.killBackgroundProcesses(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to clear background process for $packageName", e)
            }
        }
    }

    private fun findRecentsRootNode(eventPackageName: String): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && RecentsTaskInspector.isLauncherOrRecentsPackage(activeRoot.packageName?.toString() ?: "")) {
            return activeRoot
        }
        try {
            for (window in windows) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString() ?: continue
                if (RecentsTaskInspector.isLauncherOrRecentsPackage(pkg) && pkg == eventPackageName) {
                    return root
                }
            }
            for (window in windows) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString() ?: continue
                if (RecentsTaskInspector.isLauncherOrRecentsPackage(pkg)) {
                    return root
                }
            }
        } catch (_: Exception) {
        }
        return activeRoot
    }

    private fun handleRecentsOverview(
        packageName: String,
        className: String?,
        now: Long,
        activeProfiles: List<Profile>,
        quickBlockPackages: Set<String>,
        isQuickBlockActive: Boolean,
        isSocialCooldownActive: Boolean,
        isStrictLocked: Boolean
    ) {
        if ((now - lastRecentsDismissTimestamp) < 450L) {
            return
        }
        val rootNode = findRecentsRootNode(packageName) ?: return
        val recentsPkg = rootNode.packageName?.toString() ?: packageName
        val recentsRoot = RealRecentsNodeSnapshot(rootNode)
        if (!RecentsTaskInspector.isRecentsOverview(recentsPkg, className, recentsRoot)) {
            return
        }

        val targetLabels = mutableSetOf<String>()
        recentlyBlockedApps.forEach { (pkg, label) ->
            targetLabels.add(label)
            scheduleBackgroundProcessCleanup(pkg)
        }
        if (isQuickBlockActive) {
            quickBlockPackages.forEach { pkg -> targetLabels.add(resolveAppLabel(pkg)) }
        }
        val localTime = LocalTime.now()
        val dayOfWeek = LocalDate.now().dayOfWeek
        activeProfiles.filter { it.isEnabled && engine.isProfileCurrentlyEnforcing(it, localTime, dayOfWeek) }
            .flatMap { it.targetApps }
            .filter { !it.isAllowlist }
            .forEach { app ->
                targetLabels.add(app.appName)
                targetLabels.add(resolveAppLabel(app.packageName))
            }
        if (isSocialCooldownActive) {
            SocialMediaResolver.KNOWN_SOCIAL_PACKAGES.forEach { pkg ->
                targetLabels.add(resolveAppLabel(pkg))
            }
        }
        if (isStrictLocked) {
            targetLabels.add("Accessibility")
            targetLabels.add("Downloaded apps")
            targetLabels.add("Installed services")
            targetLabels.add("Device administration settings")
            targetLabels.add("Device administration")
            targetLabels.add("Device admin")
            targetLabels.add("Device admin apps")
            targetLabels.add("Phone administrators")
            targetLabels.add("App info")
            if (hasTamperedSettingsInRecents) {
                targetLabels.add("Settings")
            }
        }

        if (targetLabels.isEmpty()) return

        val displayMetrics = resources.displayMetrics
        val match = RecentsTaskInspector.findRestrictedTaskCard(
            rootNode = recentsRoot,
            targetLabels = targetLabels,
            screenWidth = displayMetrics.widthPixels,
            screenHeight = displayMetrics.heightPixels
        )
        if (match == null) {
            Log.d(
                TAG,
                "Recents overview open (pkg=$recentsPkg, isStrict=$isStrictLocked, tampered=$hasTamperedSettingsInRecents, targetCount=${targetLabels.size})"
            )
            return
        }

        lastRecentsDismissTimestamp = now
        val dismissed = dismissRecentsCard(match)
        Log.i(TAG, "Recents restricted card match=${match.matchedLabel} bounds=${match.cardBounds} dismissed=$dismissed")
        if (dismissed) {
            val lowerLabel = match.matchedLabel.lowercase()
            if (lowerLabel.contains("settings") ||
                lowerLabel.contains("app info") ||
                lowerLabel.contains("accessibility") ||
                lowerLabel.contains("device admin") ||
                lowerLabel.contains("downloaded apps") ||
                lowerLabel.contains("installed services")
            ) {
                hasTamperedSettingsInRecents = false
            }
        }
    }

    private fun dismissRecentsCard(match: RecentsCardMatch): Boolean {
        val closeReal = (match.closeButtonNode as? RealRecentsNodeSnapshot)?.node
        if (closeReal != null) {
            try {
                if (closeReal.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            } catch (_: Exception) {}
        }

        val cardReal = (match.cardNode as? RealRecentsNodeSnapshot)?.node
        if (cardReal != null) {
            try {
                if (cardReal.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) {
                    return true
                }
            } catch (_: Exception) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && match.cardBounds.isValid) {
            val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(100)
            val startX = match.cardBounds.centerX.coerceIn(40, screenWidth - 40).toFloat()
            val startY = (match.cardBounds.bottom - (match.cardBounds.height * 0.25f)).coerceAtLeast(120f)
            val endY = (match.cardBounds.top - 260).coerceAtLeast(40).toFloat()
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(startX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 220L))
                .build()
            return try {
                dispatchGesture(gesture, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed swipe dismiss gesture on Recents card ${match.matchedLabel}", e)
                false
            }
        }
        return false
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
            findAddressBarNode(rootNode, 0)
        } catch (e: Exception) {
            null
        }
    }

    private fun findAddressBarNode(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 8) return null
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
            val found = findAddressBarNode(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun isShortsOrReelsContainer(packageName: String, rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        return try {
            findShortsNode(packageName, rootNode, 0)
        } catch (e: Exception) {
            false
        }
    }

    private fun findShortsNode(packageName: String, node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 8) return false
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
            val found = findShortsNode(packageName, child, depth + 1)
            if (found) return true
        }
        return false
    }
}
