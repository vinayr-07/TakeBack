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
 * 3. Monotonic Clock Requirement (Build 8 Issue 4):
 *    - Uses monotonic elapsed realtime (e.g. SystemClock.elapsedRealtime() on Android) rather
 *      than wall-clock timestamps (System.currentTimeMillis()).
 *    - Immunizes session duration calculations against user clock adjustments, NTP jumps, or timezone changes.
 *    - Uses injected clock provider for 100% deterministic JVM unit testing.
 * 4. Latency & Restart Documentation:
 *    - Window state transitions received via accessibility events carry unavoidable OS dispatch
 *      latency of approximately 50-150ms depending on system load.
 *    - If Android terminates the service, state restarts in an uninitialized condition.
 *      The service initializes state from PowerManager and KeyguardManager and starts measuring
 *      from the first verified post-restart window event without guessing past boundaries.
 */
class ForegroundSessionTracker(
    private val isExcludedPackage: (String) -> Boolean,
    private val elapsedRealtimeClock: () -> Long
) {
    /**
     * Convenience constructor using pure JVM monotonic clock default for tests and domain operations.
     */
    constructor(
        isExcludedPackage: (String) -> Boolean = { false }
    ) : this(
        isExcludedPackage = isExcludedPackage,
        elapsedRealtimeClock = { System.nanoTime() / 1_000_000L }
    )

    @Volatile
    var currentPackage: String? = null
        private set

    @Volatile
    var sessionStartElapsedMillis: Long? = null
        private set

    val sessionStartMillis: Long?
        get() = sessionStartElapsedMillis

    @Volatile
    var isScreenInteractive: Boolean = true
        private set

    @Volatile
    var isKeyguardLocked: Boolean = false
        private set

    /**
     * Initializes initial state from device system managers (PowerManager & KeyguardManager).
     */
    fun initializeState(
        isInteractive: Boolean,
        isKeyguardLocked: Boolean,
        elapsedRealtimeMillis: Long = elapsedRealtimeClock()
    ) {
        this.isScreenInteractive = isInteractive
        this.isKeyguardLocked = isKeyguardLocked
        if (!isInteractive || isKeyguardLocked) {
            sessionStartElapsedMillis = null
        }
    }

    /**
     * Handles foreground package transition.
     * Must ONLY be driven by verified window state changes (e.g. TYPE_WINDOW_STATE_CHANGED),
     * not arbitrary content-change or scroll events.
     */
    fun onPackageChanged(packageName: String, elapsedRealtimeMillis: Long = elapsedRealtimeClock()) {
        val isExcluded = isExcludedPackage(packageName)
        if (isExcluded) {
            // User returned to Home launcher, system UI, or TakeBack -> end active app session
            currentPackage = packageName
            sessionStartElapsedMillis = null
            return
        }

        if (packageName != currentPackage || sessionStartElapsedMillis == null) {
            currentPackage = packageName
            sessionStartElapsedMillis = if (isScreenInteractive && !isKeyguardLocked) elapsedRealtimeMillis else null
        }
    }

    /**
     * Handles screen interactive (display ON/OFF) transitions.
     */
    fun onScreenStateChanged(isInteractive: Boolean, elapsedRealtimeMillis: Long = elapsedRealtimeClock()) {
        this.isScreenInteractive = isInteractive
        if (!isInteractive) {
            // Screen off ends the active session so phone lock time is never accrued
            sessionStartElapsedMillis = null
        } else {
            // Screen turned on; if user is already in a non-excluded app and unlocked, start session
            if (currentPackage != null && !isExcludedPackage(currentPackage!!) && !isKeyguardLocked) {
                sessionStartElapsedMillis = elapsedRealtimeMillis
            }
        }
    }

    /**
     * Handles keyguard lock / unlock transitions.
     */
    fun onKeyguardChanged(isLocked: Boolean, elapsedRealtimeMillis: Long = elapsedRealtimeClock()) {
        this.isKeyguardLocked = isLocked
        if (isLocked) {
            sessionStartElapsedMillis = null
        } else {
            // Keyguard dismissed; if app is foreground and screen is on, start session
            if (currentPackage != null && !isExcludedPackage(currentPackage!!) && isScreenInteractive) {
                sessionStartElapsedMillis = elapsedRealtimeMillis
            }
        }
    }

    /**
     * Returns the duration of the current continuous session in milliseconds using monotonic time.
     * Returns 0L if no active session is running (e.g. screen off, locked, or launcher).
     */
    fun getCurrentSessionDuration(elapsedRealtimeMillis: Long = elapsedRealtimeClock()): Long {
        val start = sessionStartElapsedMillis ?: return 0L
        if (!isScreenInteractive || isKeyguardLocked) return 0L
        val pkg = currentPackage ?: return 0L
        if (isExcludedPackage(pkg)) return 0L
        return maxOf(0L, elapsedRealtimeMillis - start)
    }

    /**
     * Resets the tracker on service interruption or restart.
     */
    fun reset() {
        currentPackage = null
        sessionStartElapsedMillis = null
        isScreenInteractive = true
        isKeyguardLocked = false
    }
}
