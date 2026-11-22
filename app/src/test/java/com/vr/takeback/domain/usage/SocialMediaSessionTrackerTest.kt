package com.vr.takeback.domain.usage

import com.vr.takeback.domain.security.SocialMediaResolver
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SocialMediaSessionTrackerTest {

    private var fakeElapsedMillis = 100_000L
    private var fakeWallClockMillis = 1_700_000_000_000L
    private var triggeredCooldownEpoch: Long? = null

    private lateinit var tracker: SocialMediaSessionTracker

    @Before
    fun setUp() {
        fakeElapsedMillis = 100_000L
        fakeWallClockMillis = 1_700_000_000_000L
        triggeredCooldownEpoch = null

        tracker = SocialMediaSessionTracker(
            isSocialPackage = { SocialMediaResolver.isSocialMediaPackage(it) },
            sessionLimitMillis = 5 * 60 * 1000L, // 5m
            cooldownDurationMillis = 55 * 60 * 1000L, // 55m
            interAppGracePeriodMillis = 45 * 1000L, // 45s
            elapsedRealtimeClock = { fakeElapsedMillis },
            wallClock = { fakeWallClockMillis },
            onCooldownTriggered = { triggeredCooldownEpoch = it }
        )
    }

    @Test
    fun testSingleSocialApp_reaches5Minutes_triggersCooldownImmediately() {
        tracker.onPackageChanged("com.instagram.android", fakeElapsedMillis, fakeWallClockMillis)

        // Advance by 3 minutes (180,000 ms)
        fakeElapsedMillis += 180_000L
        fakeWallClockMillis += 180_000L
        assertEquals(180_000L, tracker.getCurrentSocialSessionDuration(fakeElapsedMillis))
        assertFalse("Cooldown must not be active yet", tracker.isCooldownActive(fakeWallClockMillis, fakeElapsedMillis))

        // Advance by another 2 minutes (120,000 ms) -> Total 5 minutes (300,000 ms)
        fakeElapsedMillis += 120_000L
        fakeWallClockMillis += 120_000L

        // Trigger package event or check threshold
        tracker.onPackageChanged("com.instagram.android", fakeElapsedMillis, fakeWallClockMillis)

        assertTrue("Cooldown must be active at 5m threshold", tracker.isCooldownActive(fakeWallClockMillis, fakeElapsedMillis))
        assertEquals(55, tracker.getCooldownRemainingMinutes(fakeWallClockMillis, fakeElapsedMillis))
        assertNotNull(triggeredCooldownEpoch)
        assertEquals(fakeWallClockMillis + 55 * 60 * 1000L, tracker.cooldownUntilEpochMillis)
    }

    @Test
    fun testCrossAppSwitch_accumulatesTimeAcrossDifferentSocialApps() {
        // User opens Instagram for 2 minutes
        tracker.onPackageChanged("com.instagram.android", fakeElapsedMillis, fakeWallClockMillis)
        fakeElapsedMillis += 120_000L
        fakeWallClockMillis += 120_000L
        assertEquals(120_000L, tracker.getCurrentSocialSessionDuration(fakeElapsedMillis))

        // User switches directly to Twitter (X)
        tracker.onPackageChanged("com.twitter.android", fakeElapsedMillis, fakeWallClockMillis)

        // Use Twitter for 2 minutes
        fakeElapsedMillis += 120_000L
        fakeWallClockMillis += 120_000L
        // Accumulated: 2m + 2m = 4m
        assertEquals(240_000L, tracker.getCurrentSocialSessionDuration(fakeElapsedMillis))
        assertFalse("Cooldown must not be active at 4m", tracker.isCooldownActive(fakeWallClockMillis, fakeElapsedMillis))

        // Use Twitter for 1 more minute -> Reaches 5m limit!
        fakeElapsedMillis += 60_000L
        fakeWallClockMillis += 60_000L
        tracker.onPackageChanged("com.twitter.android", fakeElapsedMillis, fakeWallClockMillis)

        assertTrue("Cross-app accumulation must trigger cooldown at 5m total", tracker.isCooldownActive(fakeWallClockMillis, fakeElapsedMillis))
    }

    @Test
    fun testInterAppSwitch_within45SecondGracePeriod_preservesSession() {
        // User uses Instagram for 2 minutes
        tracker.onPackageChanged("com.instagram.android", fakeElapsedMillis, fakeWallClockMillis)
        fakeElapsedMillis += 120_000L
        fakeWallClockMillis += 120_000L

        // User exits to Home launcher for 15 seconds (within 45s grace period)
        tracker.onPackageChanged("com.sec.android.app.launcher", fakeElapsedMillis, fakeWallClockMillis)
        fakeElapsedMillis += 15_000L
        fakeWallClockMillis += 15_000L

        // User opens Twitter
        tracker.onPackageChanged("com.twitter.android", fakeElapsedMillis, fakeWallClockMillis)

        // Must still have 2 minutes accumulated from Instagram session
        assertEquals(120_000L, tracker.getCurrentSocialSessionDuration(fakeElapsedMillis))
        assertFalse("Cooldown must not trigger during grace switch", tracker.isCooldownActive(fakeWallClockMillis, fakeElapsedMillis))
    }

    @Test
    fun testExitingSocialMedia_forLongerThan45Seconds_concludesSessionAndStarts55mCooldown() {
        val sessionStartWallClock = fakeWallClockMillis

        // User uses Instagram for 2 minutes
        tracker.onPackageChanged("com.instagram.android", fakeElapsedMillis, fakeWallClockMillis)
        fakeElapsedMillis += 120_000L
        fakeWallClockMillis += 120_000L

        // User exits to home launcher and stays there for 2 minutes (> 45s grace period)
        tracker.onPackageChanged("com.android.launcher", fakeElapsedMillis, fakeWallClockMillis)
        val exitWallClock = fakeWallClockMillis

        fakeElapsedMillis += 120_000L
        fakeWallClockMillis += 120_000L

        // User tries to open Reddit
        tracker.onPackageChanged("com.reddit.frontpage", fakeElapsedMillis, fakeWallClockMillis)

        // Session was concluded when they left Instagram! Cooldown started at exitWallClock
        assertTrue("Cooldown must be active after exiting social media beyond grace period", tracker.isCooldownActive(fakeWallClockMillis, fakeElapsedMillis))
        assertEquals(exitWallClock + 55 * 60 * 1000L, tracker.cooldownUntilEpochMillis)
        // 53 minutes remaining (55m - 2m idle)
        assertEquals(53, tracker.getCooldownRemainingMinutes(fakeWallClockMillis, fakeElapsedMillis))
    }

    @Test
    fun testDuringCooldown_openingAnySocialAppIsBlocked() {
        // Trigger 55m cooldown
        tracker.triggerCooldown(fakeWallClockMillis)

        assertTrue(tracker.isCooldownActive(fakeWallClockMillis, fakeElapsedMillis))

        // User tries opening Snapchat
        tracker.onPackageChanged("com.snapchat.android", fakeElapsedMillis, fakeWallClockMillis)
        assertNull("Segment start must remain null during cooldown", tracker.activeSocialSegmentStartElapsedMillis)
        assertTrue(tracker.isCooldownActive(fakeWallClockMillis, fakeElapsedMillis))
    }

    @Test
    fun testCooldownExpiration_after55Minutes_allowsNewSession() {
        tracker.triggerCooldown(fakeWallClockMillis)
        assertTrue(tracker.isCooldownActive(fakeWallClockMillis, fakeElapsedMillis))

        // Advance by 55 minutes + 1 second
        fakeWallClockMillis += (55 * 60 * 1000L) + 1000L
        fakeElapsedMillis += (55 * 60 * 1000L) + 1000L

        assertFalse("Cooldown must expire after 55 minutes", tracker.isCooldownActive(fakeWallClockMillis, fakeElapsedMillis))

        // Open Instagram -> allowed, fresh session starts
        tracker.onPackageChanged("com.instagram.android", fakeElapsedMillis, fakeWallClockMillis)
        assertEquals(0L, tracker.accumulatedSocialSessionMillis)
        assertNotNull(tracker.activeSocialSegmentStartElapsedMillis)
    }

    @Test
    fun testScreenOff_pausesSessionAccumulation() {
        tracker.onPackageChanged("com.instagram.android", fakeElapsedMillis, fakeWallClockMillis)
        fakeElapsedMillis += 60_000L // 1 minute
        fakeWallClockMillis += 60_000L

        // Screen turned off
        tracker.onScreenStateChanged(false, fakeElapsedMillis, fakeWallClockMillis)

        // While screen is off, 10 minutes pass
        fakeElapsedMillis += 600_000L
        fakeWallClockMillis += 600_000L

        // Current session duration must only be 1 minute, NOT 11 minutes
        assertEquals(60_000L, tracker.getCurrentSocialSessionDuration(fakeElapsedMillis))
    }
}
