package com.vr.takeback.domain.engine

import com.vr.takeback.domain.model.BlockDecision
import com.vr.takeback.domain.model.BlockReason
import com.vr.takeback.domain.model.BlockTarget
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class SocialMediaCooldownEngineTest {

    private lateinit var engine: CentralRestrictionEngine

    @Before
    fun setUp() {
        engine = CentralRestrictionEngine()
    }

    @Test
    fun testSocialApp_blockedDuringActiveSocialCooldown() {
        val snapshot = SystemContextSnapshot(
            timestampMillis = 1_000_000L,
            localTime = LocalTime.of(12, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.instagram.android",
            isSocialCooldownActive = true,
            socialCooldownRemainingMinutes = 48
        )

        val decision = engine.evaluate(emptyList(), snapshot)
        assertTrue("Instagram must be blocked when social cooldown is active", decision is BlockDecision.Blocked)

        val blocked = decision as BlockDecision.Blocked
        assertEquals(BlockTarget.Application("com.instagram.android"), blocked.target)
        assertTrue("Block reason must be CooldownActive", blocked.reason is BlockReason.CooldownActive)

        val cooldownReason = blocked.reason as BlockReason.CooldownActive
        assertEquals("Social Media", cooldownReason.targetCategory)
        assertEquals(48, cooldownReason.cooldownRemainingMinutes)
    }

    @Test
    fun testAnotherSocialApp_alsoBlockedDuringSocialCooldown() {
        // Even if user hasn't opened Twitter or Reddit before, cooldown applies to ALL social media
        val snapshot = SystemContextSnapshot(
            timestampMillis = 1_000_000L,
            localTime = LocalTime.of(12, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.reddit.frontpage",
            isSocialCooldownActive = true,
            socialCooldownRemainingMinutes = 55
        )

        val decision = engine.evaluate(emptyList(), snapshot)
        assertTrue("Reddit must be blocked during social cooldown", decision is BlockDecision.Blocked)
        val blocked = decision as BlockDecision.Blocked
        assertTrue(blocked.reason is BlockReason.CooldownActive)
    }

    @Test
    fun testNonSocialApp_allowedDuringActiveSocialCooldown() {
        // Essential utility / payment apps must never be blocked by social cooldown
        val snapshot = SystemContextSnapshot(
            timestampMillis = 1_000_000L,
            localTime = LocalTime.of(12, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.phonepe.app",
            isSocialCooldownActive = true,
            socialCooldownRemainingMinutes = 50
        )

        val decision = engine.evaluate(emptyList(), snapshot)
        assertTrue("Non-social payment app must be allowed during social cooldown", decision is BlockDecision.Allowed)
    }

    @Test
    fun testSocialApp_blockedWhenSessionDurationReaches5Minutes() {
        val snapshot = SystemContextSnapshot(
            timestampMillis = 1_000_000L,
            localTime = LocalTime.of(12, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.twitter.android",
            isSocialCooldownActive = false,
            socialSessionDurationMillis = 5 * 60 * 1000L // 5 minutes exactly
        )

        val decision = engine.evaluate(emptyList(), snapshot)
        assertTrue("Twitter must be blocked when session duration reaches 5m", decision is BlockDecision.Blocked)
        val blocked = decision as BlockDecision.Blocked
        assertTrue(blocked.reason is BlockReason.CooldownActive)
    }

    @Test
    fun testSocialApp_allowedWhenSessionUnder5MinutesAndNoCooldown() {
        val snapshot = SystemContextSnapshot(
            timestampMillis = 1_000_000L,
            localTime = LocalTime.of(12, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.instagram.android",
            isSocialCooldownActive = false,
            socialSessionDurationMillis = 2 * 60 * 1000L // 2 minutes (under 5m)
        )

        val decision = engine.evaluate(emptyList(), snapshot)
        assertTrue("Instagram must be allowed when session is under 5m and no cooldown active", decision is BlockDecision.Allowed)
    }
}
