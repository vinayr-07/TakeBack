package com.vr.takeback.domain.usage

import org.junit.Assert.*
import org.junit.Test

/**
 * Build 7 Issue 3: Comprehensive unit tests for ForegroundSessionTracker.
 * Tests exact lifecycles: screen-off, lock, home launcher, and service restarts.
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
        tracker.onScreenStateChanged(isInteractive = false, timestampMillis = t35)
        assertEquals("Duration must be 0 while display is off", 0L, tracker.getCurrentSessionDuration(t35 + 5 * 60 * 1000L))

        // 5. Screen on and resume Instagram (10 min later)
        val t45 = t35 + 10 * 60 * 1000L
        tracker.onScreenStateChanged(isInteractive = true, timestampMillis = t45)
        tracker.onKeyguardChanged(isLocked = false, timestampMillis = t45)
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
        tracker.onKeyguardChanged(isLocked = true, timestampMillis = t0 + 10 * 60 * 1000L)
        assertEquals("Keyguard locked must halt session duration", 0L, tracker.getCurrentSessionDuration(t0 + 12 * 60 * 1000L))

        // Keyguard unlocked 5 minutes later
        tracker.onKeyguardChanged(isLocked = false, timestampMillis = t0 + 15 * 60 * 1000L)
        assertEquals(2 * 60 * 1000L, tracker.getCurrentSessionDuration(t0 + 17 * 60 * 1000L))
    }
}
