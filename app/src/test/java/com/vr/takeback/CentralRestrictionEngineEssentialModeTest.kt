package com.vr.takeback

import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.engine.SystemContextSnapshot
import com.vr.takeback.domain.model.BlockDecision
import com.vr.takeback.domain.model.BlockReason
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class CentralRestrictionEngineEssentialModeTest {

    private lateinit var engine: CentralRestrictionEngine

    @Before
    fun setUp() {
        engine = CentralRestrictionEngine()
    }

    @Test
    fun testEssentialModeActive_blocksNonExemptApp_withDailyAllowanceExceeded() {
        val exemptPackages = setOf(
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.sec.android.app.camera",
            "com.whatsapp",
            "com.samsung.android.sidegesturepad",
            "com.samsung.android.goodlock",
            "com.openai.chatgpt"
        )

        val snapshot = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(15, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.instagram.android",
            isEssentialModeActive = true,
            essentialModeAllowanceMinutes = 360,
            essentialModeTotalUsageMinutes = 385,
            essentialModeExemptPackages = exemptPackages
        )

        val decision = engine.evaluate(profiles = emptyList(), snapshot = snapshot)
        assertTrue("Non-exempt app must be blocked when Essential Mode is active", decision is BlockDecision.Blocked)
        val blocked = decision as BlockDecision.Blocked
        assertTrue("Block reason must be DailyAllowanceExceeded", blocked.reason is BlockReason.DailyAllowanceExceeded)

        val reason = blocked.reason as BlockReason.DailyAllowanceExceeded
        assertEquals(360, reason.allowanceMinutes)
        assertEquals(385, reason.usedMinutes)
    }

    @Test
    fun testEssentialModeActive_allowsExemptApps() {
        val exemptPackages = setOf(
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.sec.android.app.camera",
            "com.whatsapp",
            "com.samsung.android.sidegesturepad",
            "com.samsung.android.goodlock",
            "com.openai.chatgpt"
        )

        // 1. One Hand Operation+
        val ohpSnapshot = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(15, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.samsung.android.sidegesturepad",
            isEssentialModeActive = true,
            essentialModeAllowanceMinutes = 360,
            essentialModeTotalUsageMinutes = 400,
            essentialModeExemptPackages = exemptPackages
        )
        val ohpDecision = engine.evaluate(emptyList(), ohpSnapshot)
        assertTrue("One Hand Operation+ must be allowed", ohpDecision is BlockDecision.Allowed)

        // 2. ChatGPT (User-selected)
        val gptSnapshot = ohpSnapshot.copy(foregroundPackageName = "com.openai.chatgpt")
        val gptDecision = engine.evaluate(emptyList(), gptSnapshot)
        assertTrue("User-selected additional app ChatGPT must be allowed", gptDecision is BlockDecision.Allowed)

        // 3. WhatsApp
        val waSnapshot = ohpSnapshot.copy(foregroundPackageName = "com.whatsapp")
        val waDecision = engine.evaluate(emptyList(), waSnapshot)
        assertTrue("WhatsApp must be allowed", waDecision is BlockDecision.Allowed)
    }

    @Test
    fun testEssentialModeActive_emergencyAllowlistTakesAbsolutePrecedence() {
        // Even if dialer was somehow not in exemptPackages set, Rule 0 Emergency Allowlist protects it
        val snapshot = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(15, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.google.android.dialer",
            isEssentialModeActive = true,
            essentialModeAllowanceMinutes = 180,
            essentialModeTotalUsageMinutes = 200,
            essentialModeExemptPackages = emptySet() // Empty exempt packages
        )

        val decision = engine.evaluate(emptyList(), snapshot)
        assertTrue("Default emergency dialer is always allowed under absolute Rule 0", decision is BlockDecision.Allowed)
    }

    @Test
    fun testEssentialModeInactive_doesNotBlockApp_withoutProfileRules() {
        val snapshot = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(15, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.instagram.android",
            isEssentialModeActive = false,
            essentialModeAllowanceMinutes = 360,
            essentialModeTotalUsageMinutes = 100,
            essentialModeExemptPackages = emptySet()
        )

        val decision = engine.evaluate(emptyList(), snapshot)
        assertTrue("When Essential Mode is inactive and no profile targets app, it is allowed", decision is BlockDecision.Allowed)
    }
}
