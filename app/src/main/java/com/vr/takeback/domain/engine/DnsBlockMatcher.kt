package com.vr.takeback.domain.engine

/**
 * High-performance, pure Kotlin DNS and domain matcher.
 * Evaluates hostnames and URLs against blocklists, allowlists, and adult filter rules.
 * Zero Android framework dependencies.
 */
object DnsBlockMatcher {

    // Curated high-confidence adult domains for local sinkhole blocking
    val DEFAULT_ADULT_DOMAINS = setOf(
        "pornhub.com",
        "xvideos.com",
        "xnxx.com",
        "redtube.com",
        "youporn.com",
        "xhamster.com",
        "chaturbate.com",
        "onlyfans.com",
        "cam4.com",
        "stripchat.com",
        "livejasmin.com",
        "bongaCams.com",
        "eporner.com",
        "tube8.com",
        "beeg.com",
        "spankbang.com"
    )

    // Curated adult keywords that flag domain hostnames
    val DEFAULT_ADULT_KEYWORDS = setOf(
        "porn",
        "xxx",
        "hentai",
        "nsfw",
        "erotic",
        "cams",
        "sex",
        "adult"
    )

    /**
     * Extracts and normalizes the canonical hostname from a raw URL or domain string.
     */
    fun normalizeHost(raw: String): String {
        var clean = raw.trim().lowercase()

        // Strip URI protocol if present
        if (clean.startsWith("http://")) {
            clean = clean.substring("http://".length)
        } else if (clean.startsWith("https://")) {
            clean = clean.substring("https://".length)
        }

        // Strip path, query params, and fragments
        val slashIndex = clean.indexOf('/')
        if (slashIndex != -1) {
            clean = clean.substring(0, slashIndex)
        }

        val questionIndex = clean.indexOf('?')
        if (questionIndex != -1) {
            clean = clean.substring(0, questionIndex)
        }

        val hashIndex = clean.indexOf('#')
        if (hashIndex != -1) {
            clean = clean.substring(0, hashIndex)
        }

        // Strip port if present
        val colonIndex = clean.indexOf(':')
        if (colonIndex != -1) {
            clean = clean.substring(0, colonIndex)
        }

        // Strip leading www.
        if (clean.startsWith("www.")) {
            clean = clean.substring("www.".length)
        }

        return clean.trimEnd('.')
    }

    /**
     * Determines whether a host matches a pattern (exact, wildcard, or parent domain).
     */
    fun matchesDomainPattern(host: String, pattern: String): Boolean {
        val normHost = normalizeHost(host)
        val normPattern = normalizeHost(pattern.replace("*.", "").replace("*", ""))

        if (normHost.isEmpty() || normPattern.isEmpty()) return false

        // Exact match
        if (normHost == normPattern) return true

        // Subdomain match: e.g. "m.facebook.com" matches "facebook.com"
        if (normHost.endsWith(".$normPattern")) return true

        return false
    }

    /**
     * Checks if a domain contains adult keywords or is on the known adult domains list.
     */
    fun isAdultContent(host: String): Boolean {
        val normHost = normalizeHost(host)
        if (normHost.isEmpty()) return false

        // Check against known domains
        for (adultDomain in DEFAULT_ADULT_DOMAINS) {
            if (matchesDomainPattern(normHost, adultDomain)) {
                return true
            }
        }

        // Check against adult keywords in hostname segments
        val segments = normHost.split('.', '-', '_')
        for (segment in segments) {
            if (DEFAULT_ADULT_KEYWORDS.contains(segment)) {
                return true
            }
        }

        return false
    }

    /**
     * Evaluates whether a domain should be blocked by local DNS sinkhole.
     *
     * @param host Hostname extracted from DNS query or browser address bar
     * @param blockedDomains Set of explicitly blocked domain patterns
     * @param allowlistedDomains Set of explicitly allowed domain patterns (takes precedence)
     * @param isAdultFilterEnabled Whether adult content sinkholing is active
     */
    fun shouldBlockDomain(
        host: String,
        blockedDomains: Set<String>,
        allowlistedDomains: Set<String> = emptySet(),
        isAdultFilterEnabled: Boolean = false
    ): Boolean {
        val normHost = normalizeHost(host)
        if (normHost.isEmpty()) return false

        // Allowlist always has absolute precedence over blocklists
        for (allow in allowlistedDomains) {
            if (matchesDomainPattern(normHost, allow)) {
                return false
            }
        }

        // Check adult filter if enabled
        if (isAdultFilterEnabled && isAdultContent(normHost)) {
            return true
        }

        // Check blocked domains
        for (blocked in blockedDomains) {
            if (matchesDomainPattern(normHost, blocked)) {
                return true
            }
        }

        return false
    }
}
