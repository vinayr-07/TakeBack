package com.vr.takeback.domain.usage

import com.vr.takeback.domain.security.SocialMediaResolver
import kotlin.math.ceil

/**
 * High-precision, deterministic tracker for cross-app social media sessions and mandatory cooldowns.
 * Pure JVM domain implementation with zero Android framework dependencies.
 *
 * ARCHITECTURAL SPECIFICATIONS & INVARIANTS:
 * 1. Category-Wide Shared Session:
 *    - All social media applications share a single 5-minute (300,000 ms) continuous/accumulated
 *      active session pool. Switching between social apps does not grant a new session.
 * 2. Mandatory 55-Minute Cooldown:
 *    - Triggered immediately once 5 minutes of cumulative social media usage is consumed, OR
 *      when the user concludes an active social media session (exits to non-social app/launcher/screen off)
 *      for longer than the inter-app switch grace period (45 seconds).
 *    - During the 55-minute cooldown, all social media applications are strictly blocked.
 * 3. Monotonic Elapsed Time:
 *    - Active foreground duration is measured with monotonic elapsed realtime to prevent
 *      clock tampering or NTP jumps from bypassing the 5-minute session limit.
 * 4. Reboot & Tamper Resistance:
 *    - Cooldown expiration timestamp (wall-clock epoch) is persisted and verified on every evaluation.
 */
class SocialMediaSessionTracker(
    private val isSocialPackage: (String) -> Boolean = { SocialMediaResolver.isSocialMediaPackage(it) },
    val sessionLimitMillis: Long = 5 * 60 * 1000L, // 5 minutes
    val cooldownDurationMillis: Long = 55 * 60 * 1000L, // 55 minutes
    val interAppGracePeriodMillis: Long = 45 * 1000L, // 45 seconds
    private val elapsedRealtimeClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val onCooldownTriggered: ((cooldownUntilEpochMillis: Long) -> Unit)? = null
) {

    @Volatile
    var currentPackage: String? = null
        private set

    @Volatile
    var isScreenInteractive: Boolean = true
        private set

    @Volatile
    var isKeyguardLocked: Boolean = false
        private set

    @Volatile
    var accumulatedSocialSessionMillis: Long = 0L
        private set

    @Volatile
    var activeSocialSegmentStartElapsedMillis: Long? = null
        private set

    @Volatile
    var lastSocialExitElapsedMillis: Long? = null
        private set

    @Volatile
    var lastSocialExitEpochMillis: Long? = null
        private set

    @Volatile
    var cooldownUntilEpochMillis: Long = 0L
        private set

    /**
     * Initializes persisted cooldown timestamp from persistent storage (e.g. SharedPreferences).
     */
    fun setPersistedCooldown(cooldownUntilEpoch: Long) {
        val nowEpoch = wallClock()
        if (cooldownUntilEpoch > nowEpoch) {
            this.cooldownUntilEpochMillis = cooldownUntilEpoch
        }
    }

    /**
     * Initializes device display and keyguard state.
     */
    fun initializeState(
        isInteractive: Boolean,
        isKeyguardLocked: Boolean,
        elapsedRealtimeMillis: Long = elapsedRealtimeClock(),
        wallClockEpochMillis: Long = wallClock()
    ) {
        this.isScreenInteractive = isInteractive
        this.isKeyguardLocked = isKeyguardLocked
        if (!isInteractive || isKeyguardLocked) {
            handleSocialSegmentInterrupted(elapsedRealtimeMillis, wallClockEpochMillis)
        }
    }

    /**
     * Handles foreground package transitions driven by verified window events.
     */
    fun onPackageChanged(
        packageName: String,
        elapsedRealtimeMillis: Long = elapsedRealtimeClock(),
        wallClockEpochMillis: Long = wallClock()
    ) {
        // Step 1: Check if an idle pending session transition should commit a cooldown
        checkPendingSessionCooldown(elapsedRealtimeMillis, wallClockEpochMillis)

        val isTargetSocial = isSocialPackage(packageName)
        val wasSocialActive = activeSocialSegmentStartElapsedMillis != null

        currentPackage = packageName

        if (isTargetSocial) {
            // If cooldown is currently active, no new session or segment can start
            if (isCooldownActive(wallClockEpochMillis, elapsedRealtimeMillis)) {
                activeSocialSegmentStartElapsedMillis = null
                return
            }

            // Check if user is resuming within the inter-app grace period
            val exitElapsed = lastSocialExitElapsedMillis
            if (exitElapsed != null) {
                val idleDuration = elapsedRealtimeMillis - exitElapsed
                if (idleDuration <= interAppGracePeriodMillis) {
                    // Resuming existing session within grace period (e.g. Instagram -> Twitter)
                    lastSocialExitElapsedMillis = null
                    lastSocialExitEpochMillis = null
                } else if (accumulatedSocialSessionMillis > 0L) {
                    // Idle exceeded grace period -> previous session ended, trigger cooldown
                    commitSessionCooldown(wallClockEpochMillis - idleDuration)
                    return
                }
            }

            if (isScreenInteractive && !isKeyguardLocked) {
                if (!wasSocialActive) {
                    activeSocialSegmentStartElapsedMillis = elapsedRealtimeMillis
                }
            }
        } else {
            // User moved away from social media (to launcher, settings, or non-social app)
            if (wasSocialActive) {
                handleSocialSegmentInterrupted(elapsedRealtimeMillis, wallClockEpochMillis)
            }
        }

        // Check if accumulated time reached session threshold
        checkThresholdAndTriggerCooldown(elapsedRealtimeMillis, wallClockEpochMillis)
    }

    /**
     * Handles display on/off transitions.
     */
    fun onScreenStateChanged(
        isInteractive: Boolean,
        elapsedRealtimeMillis: Long = elapsedRealtimeClock(),
        wallClockEpochMillis: Long = wallClock()
    ) {
        this.isScreenInteractive = isInteractive
        if (!isInteractive) {
            handleSocialSegmentInterrupted(elapsedRealtimeMillis, wallClockEpochMillis)
        } else {
            val pkg = currentPackage
            if (pkg != null && isSocialPackage(pkg) && !isKeyguardLocked) {
                if (!isCooldownActive(wallClockEpochMillis, elapsedRealtimeMillis)) {
                    activeSocialSegmentStartElapsedMillis = elapsedRealtimeMillis
                }
            }
        }
        checkThresholdAndTriggerCooldown(elapsedRealtimeMillis, wallClockEpochMillis)
    }

    /**
     * Handles device lock/unlock transitions.
     */
    fun onKeyguardChanged(
        isLocked: Boolean,
        elapsedRealtimeMillis: Long = elapsedRealtimeClock(),
        wallClockEpochMillis: Long = wallClock()
    ) {
        this.isKeyguardLocked = isLocked
        if (isLocked) {
            handleSocialSegmentInterrupted(elapsedRealtimeMillis, wallClockEpochMillis)
        } else {
            val pkg = currentPackage
            if (pkg != null && isSocialPackage(pkg) && isScreenInteractive) {
                if (!isCooldownActive(wallClockEpochMillis, elapsedRealtimeMillis)) {
                    activeSocialSegmentStartElapsedMillis = elapsedRealtimeMillis
                }
            }
        }
        checkThresholdAndTriggerCooldown(elapsedRealtimeMillis, wallClockEpochMillis)
    }

    /**
     * Returns the current continuous/accumulated social session duration in milliseconds.
     */
    fun getCurrentSocialSessionDuration(elapsedRealtimeMillis: Long = elapsedRealtimeClock()): Long {
        var total = accumulatedSocialSessionMillis
        val segmentStart = activeSocialSegmentStartElapsedMillis
        if (segmentStart != null && isScreenInteractive && !isKeyguardLocked) {
            total += maxOf(0L, elapsedRealtimeMillis - segmentStart)
        }
        return total
    }

    /**
     * Checks whether the 55-minute cooldown is currently active.
     */
    fun isCooldownActive(
        wallClockEpochMillis: Long = wallClock(),
        elapsedRealtimeMillis: Long = elapsedRealtimeClock()
    ): Boolean {
        checkPendingSessionCooldown(elapsedRealtimeMillis, wallClockEpochMillis)
        return wallClockEpochMillis < cooldownUntilEpochMillis
    }

    /**
     * Returns the remaining cooldown duration in whole minutes (ceiling).
     */
    fun getCooldownRemainingMinutes(
        wallClockEpochMillis: Long = wallClock(),
        elapsedRealtimeMillis: Long = elapsedRealtimeClock()
    ): Int {
        if (!isCooldownActive(wallClockEpochMillis, elapsedRealtimeMillis)) return 0
        val remainingMillis = cooldownUntilEpochMillis - wallClockEpochMillis
        return ceil(remainingMillis / 60000.0).toInt().coerceAtLeast(1)
    }

    /**
     * Manually triggers the 55-minute cooldown (e.g. from CentralRestrictionEngine or UI).
     */
    fun triggerCooldown(
        wallClockEpochMillis: Long = wallClock(),
        durationMillis: Long = cooldownDurationMillis
    ) {
        val targetEpoch = wallClockEpochMillis + durationMillis
        cooldownUntilEpochMillis = maxOf(cooldownUntilEpochMillis, targetEpoch)
        accumulatedSocialSessionMillis = 0L
        activeSocialSegmentStartElapsedMillis = null
        lastSocialExitElapsedMillis = null
        lastSocialExitEpochMillis = null
        onCooldownTriggered?.invoke(cooldownUntilEpochMillis)
    }

    /**
     * Checks if active session duration reached or exceeded the 5-minute limit.
     */
    private fun checkThresholdAndTriggerCooldown(
        elapsedRealtimeMillis: Long,
        wallClockEpochMillis: Long
    ) {
        val totalActiveDuration = getCurrentSocialSessionDuration(elapsedRealtimeMillis)
        if (totalActiveDuration >= sessionLimitMillis) {
            triggerCooldown(wallClockEpochMillis, cooldownDurationMillis)
        }
    }

    /**
     * Checks if user has been away from social media longer than the grace period.
     */
    private fun checkPendingSessionCooldown(
        elapsedRealtimeMillis: Long,
        wallClockEpochMillis: Long
    ) {
        val exitElapsed = lastSocialExitElapsedMillis
        val exitEpoch = lastSocialExitEpochMillis
        if (exitElapsed != null && accumulatedSocialSessionMillis > 0L) {
            val idleDuration = elapsedRealtimeMillis - exitElapsed
            if (idleDuration > interAppGracePeriodMillis) {
                val sessionEndTime = exitEpoch ?: (wallClockEpochMillis - idleDuration)
                commitSessionCooldown(sessionEndTime)
            }
        }
    }

    private fun handleSocialSegmentInterrupted(
        elapsedRealtimeMillis: Long,
        wallClockEpochMillis: Long
    ) {
        val segmentStart = activeSocialSegmentStartElapsedMillis
        if (segmentStart != null) {
            val segmentDuration = maxOf(0L, elapsedRealtimeMillis - segmentStart)
            accumulatedSocialSessionMillis += segmentDuration
            activeSocialSegmentStartElapsedMillis = null
            lastSocialExitElapsedMillis = elapsedRealtimeMillis
            lastSocialExitEpochMillis = wallClockEpochMillis
        }
    }

    private fun commitSessionCooldown(sessionEndTimeEpochMillis: Long) {
        val targetEpoch = sessionEndTimeEpochMillis + cooldownDurationMillis
        cooldownUntilEpochMillis = maxOf(cooldownUntilEpochMillis, targetEpoch)
        accumulatedSocialSessionMillis = 0L
        activeSocialSegmentStartElapsedMillis = null
        lastSocialExitElapsedMillis = null
        lastSocialExitEpochMillis = null
        onCooldownTriggered?.invoke(cooldownUntilEpochMillis)
    }

    /**
     * Resets the tracker.
     */
    fun reset() {
        currentPackage = null
        accumulatedSocialSessionMillis = 0L
        activeSocialSegmentStartElapsedMillis = null
        lastSocialExitElapsedMillis = null
        lastSocialExitEpochMillis = null
    }

    /**
     * Clears any active cooldown.
     */
    fun clearCooldown() {
        cooldownUntilEpochMillis = 0L
        reset()
    }
}
