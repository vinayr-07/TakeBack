package com.vr.takeback.domain.usage

/**
 * High-precision, deterministic tracker for active foreground application sessions.
 * Pure JVM domain implementation with zero Android framework dependencies.
 *
 * ARCHITECTURAL RULES & INVARIANTS:
 * 1. An active application session exists ONLY when:
 *    - A user application is in the foreground (not home launcher, system UI, or TakeBack itself).
 *    - The device display is interactive (screen ON).
 *    - The device is not locked (keyguard hidden / dismissed).
 * 2. Session time NEVER accrues across:
 *    - Display non-interactive intervals (screen off).
 *    - Device lock periods (keyguard showing).
 *    - Launcher / home / background transitions.
 *    - Accessibility service interruptions, crashes, or process restarts.
 * 3. Latency & Restart Documentation:
 *    - Window state transitions received via accessibility events carry unavoidable OS dispatch
 *      latency of approximately 50-150ms depending on system load.
 *    - If Android's Low Memory Killer (LMK) or OEM battery optimization terminates the service,
 *      state restarts in an uninitialized condition. The tracker initializes at the moment of the
 *      first observed post-restart window event and never guesses retroactive session boundaries.
 */
class ForegroundSessionTracker(
    private val isExcludedPackage: (String) -> Boolean = { false }
) {
    @Volatile
    var currentPackage: String? = null
        private set

    @Volatile
    var sessionStartMillis: Long? = null
        private set

    @Volatile
    var isScreenInteractive: Boolean = true
        private set

    @Volatile
    var isKeyguardLocked: Boolean = false
        private set

    /**
     * Handles foreground package transition.
     */
    fun onPackageChanged(packageName: String, timestampMillis: Long) {
        val isExcluded = isExcludedPackage(packageName)
        if (isExcluded) {
            // User returned to Home launcher, system UI, or TakeBack -> end active app session
            currentPackage = packageName
            sessionStartMillis = null
            return
        }

        if (packageName != currentPackage || sessionStartMillis == null) {
            currentPackage = packageName
            sessionStartMillis = if (isScreenInteractive && !isKeyguardLocked) timestampMillis else null
        }
    }

    /**
     * Handles screen interactive (display ON/OFF) transitions.
     */
    fun onScreenStateChanged(isInteractive: Boolean, timestampMillis: Long) {
        this.isScreenInteractive = isInteractive
        if (!isInteractive) {
            // Screen off ends the active session so phone lock time is never accrued
            sessionStartMillis = null
        } else {
            // Screen turned on; if user is already in a non-excluded app and unlocked, start session
            if (currentPackage != null && !isExcludedPackage(currentPackage!!) && !isKeyguardLocked) {
                sessionStartMillis = timestampMillis
            }
        }
    }

    /**
     * Handles keyguard lock / unlock transitions.
     */
    fun onKeyguardChanged(isLocked: Boolean, timestampMillis: Long) {
        this.isKeyguardLocked = isLocked
        if (isLocked) {
            sessionStartMillis = null
        } else {
            // Keyguard dismissed; if app is foreground and screen is on, start session
            if (currentPackage != null && !isExcludedPackage(currentPackage!!) && isScreenInteractive) {
                sessionStartMillis = timestampMillis
            }
        }
    }

    /**
     * Returns the duration of the current continuous session in milliseconds.
     * Returns 0L if no active session is running (e.g. screen off, locked, or launcher).
     */
    fun getCurrentSessionDuration(timestampMillis: Long): Long {
        val start = sessionStartMillis ?: return 0L
        if (!isScreenInteractive || isKeyguardLocked) return 0L
        val pkg = currentPackage ?: return 0L
        if (isExcludedPackage(pkg)) return 0L
        return maxOf(0L, timestampMillis - start)
    }

    /**
     * Resets the tracker on service interruption or restart.
     */
    fun reset() {
        currentPackage = null
        sessionStartMillis = null
        isScreenInteractive = true
        isKeyguardLocked = false
    }
}
