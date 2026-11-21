package com.vr.takeback

import com.vr.takeback.domain.engine.DnsBlockMatcher
import org.junit.Assert.*
import org.junit.Test

class DnsBlockMatcherTest {

    @Test
    fun normalizeHost_cleansProtocolsPortsAndPaths() {
        assertEquals("instagram.com", DnsBlockMatcher.normalizeHost("https://instagram.com/feed"))
        assertEquals("instagram.com", DnsBlockMatcher.normalizeHost("http://www.instagram.com/p/123?ref=test"))
        assertEquals("facebook.com", DnsBlockMatcher.normalizeHost("http://facebook.com:8080/path#section"))
        assertEquals("sub.domain.org", DnsBlockMatcher.normalizeHost("https://sub.domain.org:443/"))
    }

    @Test
    fun matchesDomainPattern_matchesExactAndSubdomains() {
        assertTrue(DnsBlockMatcher.matchesDomainPattern("instagram.com", "instagram.com"))
        assertTrue(DnsBlockMatcher.matchesDomainPattern("api.instagram.com", "instagram.com"))
        assertTrue(DnsBlockMatcher.matchesDomainPattern("graph.facebook.com", "*.facebook.com"))
        assertFalse(DnsBlockMatcher.matchesDomainPattern("notinstagram.com", "instagram.com"))
        assertFalse(DnsBlockMatcher.matchesDomainPattern("google.com", "instagram.com"))
    }

    @Test
    fun isAdultContent_detectsKnownDomainsAndKeywords() {
        assertTrue(DnsBlockMatcher.isAdultContent("pornhub.com"))
        assertTrue(DnsBlockMatcher.isAdultContent("https://www.xvideos.com/video123"))
        assertTrue(DnsBlockMatcher.isAdultContent("explicit-hentai-site.org"))
        assertTrue(DnsBlockMatcher.isAdultContent("adult-hub.net"))
        assertFalse(DnsBlockMatcher.isAdultContent("wikipedia.org"))
        assertFalse(DnsBlockMatcher.isAdultContent("khanacademy.org"))
    }

    @Test
    fun shouldBlockDomain_enforcesAllowlistPrecedence() {
        val blocked = setOf("instagram.com", "twitter.com")
        val allowlist = setOf("work.instagram.com")

        // Blocked root domain
        assertTrue(DnsBlockMatcher.shouldBlockDomain("instagram.com", blocked, allowlist))
        assertTrue(DnsBlockMatcher.shouldBlockDomain("m.instagram.com", blocked, allowlist))

        // Specifically allowlisted subdomain must bypass block
        assertFalse(DnsBlockMatcher.shouldBlockDomain("work.instagram.com", blocked, allowlist))

        // Non-blocked site allowed
        assertFalse(DnsBlockMatcher.shouldBlockDomain("stackoverflow.com", blocked, allowlist))
    }

    @Test
    fun shouldBlockDomain_enforcesAdultFilterWhenActive() {
        val blocked = emptySet<String>()
        val allowlist = emptySet<String>()

        // When adult filter disabled
        assertFalse(DnsBlockMatcher.shouldBlockDomain("pornhub.com", blocked, allowlist, isAdultFilterEnabled = false))

        // When adult filter enabled
        assertTrue(DnsBlockMatcher.shouldBlockDomain("pornhub.com", blocked, allowlist, isAdultFilterEnabled = true))
        assertTrue(DnsBlockMatcher.shouldBlockDomain("some-xxx-cams.com", blocked, allowlist, isAdultFilterEnabled = true))

        // Safe domains still allowed
        assertFalse(DnsBlockMatcher.shouldBlockDomain("github.com", blocked, allowlist, isAdultFilterEnabled = true))
    }
}
