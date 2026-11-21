package com.vr.takeback

import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.engine.SystemContextSnapshot
import com.vr.takeback.domain.model.*
import com.vr.takeback.domain.security.StrictModeManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * End-to-End System Test verifying complete cross-module distraction control:
 * - Multi-profile union conflict resolution
 * - Digital Detox immediate enforcement
 * - Emergency invariance (Priority 0)
 * - Quick Block timeout & target packages
 * - Strict Mode verification friction
 * - Independent website rule blocking via CentralRestrictionEngine
 */
class TakeBackSystemTest {

    private lateinit var engine: CentralRestrictionEngine

    @Before
    fun setUp() {
        engine = CentralRestrictionEngine()
    }

    @Test
    fun fullSystemLifecycle_demonstratesDeterministicDistractionControl() {
        // Step 1: System has multiple profiles (Study, Detox, Personal)
        val studyProfile = Profile(
            id = "study_profile",
            name = "Deep Study",
            isEnabled = false,
            targetApps = listOf(TargetApp("com.instagram.android", "Instagram")),
            schedules = listOf(Schedule(profileId = "study_profile", daysOfWeekMask = 31, startTimeSeconds = 14 * 3600, endTimeSeconds = 18 * 3600))
        )
        val detoxProfile = Profile(
            id = "detox_profile",
            name = "Digital Detox",
            isEnabled = true, // User turned ON Digital Detox
            targetApps = listOf(
                TargetApp("com.instagram.android", "Instagram"),
                TargetApp("com.zhiliaoapp.musically", "TikTok"),
                TargetApp("com.twitter.android", "Twitter")
            ),
            schedules = emptyList() // Immediate block
        )
        val allProfiles = listOf(studyProfile, detoxProfile)

        // Step 2: System evaluates Digital Detox activation
        val detoxSnapshot = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(20, 30),
            dayOfWeek = DayOfWeek.FRIDAY,
            foregroundPackageName = "com.instagram.android"
        )
        val detoxDecision = engine.evaluate(allProfiles, detoxSnapshot)
        assertTrue("Instagram must be blocked by active Digital Detox profile", detoxDecision is BlockDecision.Blocked)
        assertEquals("detox_profile", (detoxDecision as BlockDecision.Blocked).triggeringProfileId)

        // Step 3: Emergency Invariance Check - Native dialer MUST NEVER be blocked
        val emergencySnapshot = detoxSnapshot.copy(foregroundPackageName = "com.google.android.dialer")
        val dialerDecision = engine.evaluate(allProfiles, emergencySnapshot)
        assertTrue("Emergency dialer must be allowed even with active Digital Detox", dialerDecision is BlockDecision.Allowed)

        // Step 4: Quick Block Instant Activation
        val quickBlockExpiry = 1000L + (15 * 60 * 1000L) // 15 mins
        val quickBlockSnapshot = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(10, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.reddit.frontpage",
            isQuickBlockActive = true,
            quickBlockExpirationEpochMillis = quickBlockExpiry,
            quickBlockTargetPackages = setOf("com.reddit.frontpage")
        )
        val quickBlockDecision = engine.evaluate(emptyList(), quickBlockSnapshot)
        assertTrue("Quick block must immediately block target app", quickBlockDecision is BlockDecision.Blocked)
        assertEquals("quick_block", (quickBlockDecision as BlockDecision.Blocked).triggeringProfileId)

        // Step 5: Strict Mode Text Challenge Verification
        val challenge = StrictModeManager.generateTextChallenge(60)
        assertEquals(60, challenge.length)
        assertTrue("Valid challenge response must match", StrictModeManager.verifyTextChallenge(challenge, challenge))
        assertFalse("Mismatched challenge response must be rejected", StrictModeManager.verifyTextChallenge("WrongInput", challenge))

        // Step 6: Independent Website Restriction via CentralRestrictionEngine (DNS-free)
        val webProfile = Profile(
            id = "web_distraction",
            name = "Web Filter",
            isEnabled = true,
            targetWebsites = listOf(
                TargetWebsite("instagram.com", WebsiteRuleType.EXACT_DOMAIN, isAllowlist = false),
                TargetWebsite("tiktok.com", WebsiteRuleType.EXACT_DOMAIN, isAllowlist = false),
                TargetWebsite("edu.tiktok.com", WebsiteRuleType.EXACT_DOMAIN, isAllowlist = true)
            )
        )

        val blockedWebSnap = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(14, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.android.chrome",
            activeBrowserUrl = "https://instagram.com/explore"
        )
        val webDecision = engine.evaluate(listOf(webProfile), blockedWebSnap)
        assertTrue("Restricted website must be blocked by CentralRestrictionEngine", webDecision is BlockDecision.Blocked)
        assertEquals(BlockTarget.Website("https://instagram.com/explore"), (webDecision as BlockDecision.Blocked).target)

        val allowedWebSnap = blockedWebSnap.copy(activeBrowserUrl = "https://wikipedia.org/wiki/Kotlin")
        val allowedDecision = engine.evaluate(listOf(webProfile), allowedWebSnap)
        assertTrue("Unrestricted website must be allowed", allowedDecision is BlockDecision.Allowed)
    }
}
