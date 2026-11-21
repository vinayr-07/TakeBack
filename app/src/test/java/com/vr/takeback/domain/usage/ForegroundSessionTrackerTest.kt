package com.vr.takeback.domain.usage

import org.junit.Assert.*
import org.junit.Test

/**
 * Build 7 & Build 8: Comprehensive unit tests for ForegroundSessionTracker.
 * Tests exact lifecycles: screen-off, lock, home launcher, service restarts,
 * monotonic clock invariance against wall-clock jumps, and manager initialization.
 */
class ForegroundSessionTrackerTest {

    private val INSTAGRAM = "com.instagram.android"
    private val LAUNCHER = "com.android.launcher3"
    private val YOUTUBE = "com.google.android.youtube"

    private fun createTracker() = ForegroundSessionTracker { pkg ->
        pkg == "com.vr.takeback" || pkg == "com.android.systemui" || pkg.contains("launcher")
    }

    @Test
    fun testFullSpecificationLifecycleSequence() {
        val tracker = createTracker()
        val t0 = 1_000_000L

        // 1. Instagram 20 min
        tracker.onPackageChanged(INSTAGRAM, t0)
        assertEquals(0L, tracker.getCurrentSessionDuration(t0))
        val t20 = t0 + 20 * 60 * 1000L
        assertEquals(20 * 60 * 1000L, tracker.getCurrentSessionDuration(t20))

        // 2. Home 10 min
        tracker.onPackageChanged(LAUNCHER, t20)
        assertEquals(0L, tracker.getCurrentSessionDuration(t20 + 5 * 60 * 1000L))

        // 3. Instagram 5 min
        val t30 = t0 + 30 * 60 * 1000L
        tracker.onPackageChanged(INSTAGRAM, t30)
        val t35 = t30 + 5 * 60 * 1000L
        assertEquals("Second session must be 5 minutes, not 25 or 35 minutes", 5 * 60 * 1000L, tracker.getCurrentSessionDuration(t35))

        // 4. Screen off for 10 min
        tracker.onScreenStateChanged(isInteractive = false, elapsedRealtimeMillis = t35)
        assertEquals("Duration must be 0 while display is off", 0L, tracker.getCurrentSessionDuration(t35 + 5 * 60 * 1000L))

        // 5. Screen on and resume Instagram (10 min later)
        val t45 = t35 + 10 * 60 * 1000L
        tracker.onScreenStateChanged(isInteractive = true, elapsedRealtimeMillis = t45)
        tracker.onKeyguardChanged(isLocked = false, elapsedRealtimeMillis = t45)
        // Resume Instagram for 3 minutes
        val t48 = t45 + 3 * 60 * 1000L
        assertEquals("Resumed session after screen off must start fresh and exclude phone lock time", 3 * 60 * 1000L, tracker.getCurrentSessionDuration(t48))

        // 6. Accessibility service interruption and restart
        tracker.reset()
        assertEquals(0L, tracker.getCurrentSessionDuration(t48))
        assertNull(tracker.currentPackage)
        assertNull(tracker.sessionStartMillis)

        // After restart, first window event arrives
        val tRestart = t48 + 1_000L
        tracker.onPackageChanged(YOUTUBE, tRestart)
        assertEquals(0L, tracker.getCurrentSessionDuration(tRestart))
        assertEquals(2 * 60 * 1000L, tracker.getCurrentSessionDuration(tRestart + 2 * 60 * 1000L))
    }

    @Test
    fun testKeyguardLock_stopsSessionAccrual() {
        val tracker = createTracker()
        val t0 = 1_000_000L

        tracker.onPackageChanged(INSTAGRAM, t0)
        assertEquals(10 * 60 * 1000L, tracker.getCurrentSessionDuration(t0 + 10 * 60 * 1000L))

        // Phone is locked with keyguard showing (even if screen briefly stays on)
        tracker.onKeyguardChanged(isLocked = true, elapsedRealtimeMillis = t0 + 10 * 60 * 1000L)
        assertEquals("Keyguard locked must halt session duration", 0L, tracker.getCurrentSessionDuration(t0 + 12 * 60 * 1000L))

        // Keyguard unlocked 5 minutes later
        tracker.onKeyguardChanged(isLocked = false, elapsedRealtimeMillis = t0 + 15 * 60 * 1000L)
        assertEquals(2 * 60 * 1000L, tracker.getCurrentSessionDuration(t0 + 17 * 60 * 1000L))
    }

    @Test
    fun testBuild8_monotonicClock_immuneToWallClockChanges() {
        // Mock monotonic elapsed realtime clock
        var monotonicElapsedMillis = 500_000L
        val tracker = ForegroundSessionTracker(
            isExcludedPackage = { it.contains("launcher") },
            elapsedRealtimeClock = { monotonicElapsedMillis }
        )

        // Start session in Instagram
        tracker.onPackageChanged(INSTAGRAM, monotonicElapsedMillis)
        assertEquals(0L, tracker.getCurrentSessionDuration())

        // 5 minutes elapse in monotonic time
        monotonicElapsedMillis += 5 * 60 * 1000L
        assertEquals(5 * 60 * 1000L, tracker.getCurrentSessionDuration())

        // Even if user changes wall clock (e.g. forward by 2 hours or back by 1 day),
        // monotonicElapsedMillis keeps progressing linearly and session duration is strictly accurate.
        monotonicElapsedMillis += 3 * 60 * 1000L
        assertEquals(8 * 60 * 1000L, tracker.getCurrentSessionDuration())
    }

    @Test
    fun testBuild8_serviceRestartWhileLocked_initializesWithZeroDuration() {
        val tracker = createTracker()
        val elapsedAtConnect = 1_200_000L

        // Service reconnects while phone is locked (PowerManager says interactive=false, KeyguardManager says locked=true)
        tracker.initializeState(isInteractive = false, isKeyguardLocked = true, elapsedRealtimeMillis = elapsedAtConnect)
        tracker.onPackageChanged(INSTAGRAM, elapsedAtConnect)

        assertEquals("Session duration must be 0 while locked upon service connection", 0L, tracker.getCurrentSessionDuration(elapsedAtConnect + 60_000L))

        // Screen turns on, but keyguard is still locked
        val tScreenOn = elapsedAtConnect + 100_000L
        tracker.onScreenStateChanged(isInteractive = true, elapsedRealtimeMillis = tScreenOn)
        assertEquals("Session duration must still be 0 while keyguard remains locked", 0L, tracker.getCurrentSessionDuration(tScreenOn + 30_000L))

        // User unlocks device
        val tUnlocked = tScreenOn + 60_000L
        tracker.onKeyguardChanged(isLocked = false, elapsedRealtimeMillis = tUnlocked)

        // Session starts counting from unlock timestamp
        assertEquals(45_000L, tracker.getCurrentSessionDuration(tUnlocked + 45_000L))
    }
}
