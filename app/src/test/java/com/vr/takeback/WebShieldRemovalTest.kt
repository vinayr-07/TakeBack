package com.vr.takeback

import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.engine.SystemContextSnapshot
import com.vr.takeback.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class WebShieldRemovalTest {

    private val engine = CentralRestrictionEngine()

    @Test
    fun testIndependentWebsiteFiltering_withoutDnsVpn() {
        val webProfile = Profile(
            id = "web_distraction_profile",
            name = "Web Filter",
            isEnabled = true,
            targetWebsites = listOf(
                TargetWebsite(
                    domainPattern = "instagram.com",
                    ruleType = WebsiteRuleType.EXACT_DOMAIN,
                    isAllowlist = false
                ),
                TargetWebsite(
                    domainPattern = "*tiktok.com",
                    ruleType = WebsiteRuleType.WILDCARD,
                    isAllowlist = false
                ),
                TargetWebsite(
                    domainPattern = "casino",
                    ruleType = WebsiteRuleType.KEYWORD,
                    isAllowlist = false
                ),
                TargetWebsite(
                    domainPattern = "edu.tiktok.com",
                    ruleType = WebsiteRuleType.EXACT_DOMAIN,
                    isAllowlist = true
                )
            )
        )

        val baseSnapshot = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(15, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.android.chrome"
        )

        // 1. EXACT_DOMAIN block
        val instaSnap = baseSnapshot.copy(activeBrowserUrl = "https://instagram.com/feed")
        val instaDecision = engine.evaluate(listOf(webProfile), instaSnap)
        assertTrue("Exact domain match must block", instaDecision is BlockDecision.Blocked)

        // 2. WILDCARD block
        val tiktokSnap = baseSnapshot.copy(activeBrowserUrl = "https://m.tiktok.com/v/123")
        val tiktokDecision = engine.evaluate(listOf(webProfile), tiktokSnap)
        assertTrue("Wildcard domain match must block", tiktokDecision is BlockDecision.Blocked)

        // 3. KEYWORD block
        val casinoSnap = baseSnapshot.copy(activeBrowserUrl = "https://online-casino-games.net")
        val casinoDecision = engine.evaluate(listOf(webProfile), casinoSnap)
        assertTrue("Keyword match must block", casinoDecision is BlockDecision.Blocked)

        // 4. ALLOWLIST exception overrides block
        val eduTiktokSnap = baseSnapshot.copy(activeBrowserUrl = "https://edu.tiktok.com/learn")
        val eduDecision = engine.evaluate(listOf(webProfile), eduTiktokSnap)
        assertTrue("Allowlist match must permit URL even when root domain is blocked", eduDecision is BlockDecision.Allowed)

        // 5. Unrestricted site
        val wikiSnap = baseSnapshot.copy(activeBrowserUrl = "https://wikipedia.org")
        val wikiDecision = engine.evaluate(listOf(webProfile), wikiSnap)
        assertTrue("Unrestricted URL must be allowed", wikiDecision is BlockDecision.Allowed)
    }
}
