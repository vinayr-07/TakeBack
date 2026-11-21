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

    @Test
    fun testLookalikeAndPathAttackDomains_remainAllowedUnderExactDomain() {
        val webProfile = Profile(
            id = "exact_rule_profile",
            name = "Exact Rule",
            isEnabled = true,
            targetWebsites = listOf(
                TargetWebsite(
                    domainPattern = "instagram.com",
                    ruleType = WebsiteRuleType.EXACT_DOMAIN,
                    isAllowlist = false
                )
            )
        )

        val baseSnapshot = SystemContextSnapshot(
            timestampMillis = System.currentTimeMillis(),
            localTime = LocalTime.of(15, 0),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.android.chrome"
        )

        // 1. Prefix lookalike: notinstagram.com must be ALLOWED
        val prefixLookalike = baseSnapshot.copy(activeBrowserUrl = "https://notinstagram.com/feed")
        val d1 = engine.evaluate(listOf(webProfile), prefixLookalike)
        assertTrue("Prefix lookalike domain must be allowed", d1 is BlockDecision.Allowed)

        // 2. Attacker subdomain: instagram.com.attacker.test must be ALLOWED
        val attackerSubdomain = baseSnapshot.copy(activeBrowserUrl = "https://instagram.com.attacker.test")
        val d2 = engine.evaluate(listOf(webProfile), attackerSubdomain)
        assertTrue("Attacker domain using target as subdomain must be allowed", d2 is BlockDecision.Allowed)

        // 3. Target in URL path: safe.test/instagram.com must be ALLOWED
        val pathTarget = baseSnapshot.copy(activeBrowserUrl = "https://safe.test/path/to/instagram.com")
        val d3 = engine.evaluate(listOf(webProfile), pathTarget)
        assertTrue("Target in URL path must be allowed", d3 is BlockDecision.Allowed)

        // 4. Target in query param: safe.test?q=instagram.com must be ALLOWED
        val queryTarget = baseSnapshot.copy(activeBrowserUrl = "https://safe.test/search?q=instagram.com")
        val d4 = engine.evaluate(listOf(webProfile), queryTarget)
        assertTrue("Target in query parameter must be allowed", d4 is BlockDecision.Allowed)

        // 5. Genuine exact domain: instagram.com/profile must be BLOCKED with DomainBlock
        val genuineTarget = baseSnapshot.copy(activeBrowserUrl = "https://instagram.com/profile")
        val d5 = engine.evaluate(listOf(webProfile), genuineTarget)
        assertTrue("Genuine exact domain must be blocked", d5 is BlockDecision.Blocked)
        assertEquals(BlockReason.DomainBlock("instagram.com"), (d5 as BlockDecision.Blocked).reason)
    }
}
