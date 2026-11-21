package com.vr.takeback.domain.security

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Types of mutations protected under Strict Mode.
 */
enum class MutationType {
    TOGGLE_PROFILE,
    CREATE_PROFILE,
    DUPLICATE_PROFILE,
    DELETE_PROFILE,
    SAVE_PROFILE,
    DISARM_STRICT_MODE,
    SETTINGS_MAINTENANCE
}

/**
 * Single-use authorization token issued upon solving a Strict Mode verification challenge.
 * Associated specifically with the intended mutation type and optional target entity ID.
 */
data class OneTimeMutationToken(
    val tokenId: String,
    val mutationType: MutationType,
    val targetId: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val expiresAtMillis: Long = createdAtMillis + 60_000L // Valid for 60 seconds
)

/**
 * Centralized Strict Mutation Gate.
 * Enforces that when Strict Mode is active, every user-initiated mutation requires
 * a verified, unconsumed one-time authorization token.
 *
 * Invariants:
 * 1. When Strict Mode is disabled: mutations pass freely.
 * 2. When Strict Mode is active: every user modification requires a valid, matching, unconsumed token.
 * 3. Tokens are single-use: consumed tokens are immediately destroyed to prevent reuse.
 * 4. Tokens expire after 60 seconds: no wide, open-ended maintenance windows.
 * 5. System-originated operations (e.g. schedule boundary transitions) bypass user PIN verification.
 */
object StrictMutationGate {

    private val activeTokens = ConcurrentHashMap<String, OneTimeMutationToken>()

    /**
     * Issues a single-use authorization token for a specific mutation upon successful challenge solution.
     */
    fun issueToken(
        mutationType: MutationType,
        targetId: String? = null,
        validityDurationMillis: Long = 60_000L
    ): String {
        purgeExpiredTokens()
        val tokenId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val token = OneTimeMutationToken(
            tokenId = tokenId,
            mutationType = mutationType,
            targetId = targetId,
            createdAtMillis = now,
            expiresAtMillis = now + validityDurationMillis
        )
        activeTokens[tokenId] = token
        return tokenId
    }

    /**
     * Validates and consumes a single-use token.
     * Returns true if the token is valid, unexpired, and matches the mutation type and target.
     * Does not consume the token if the mutation type or target does not match.
     */
    fun consumeToken(token: String?, mutationType: MutationType, targetId: String? = null): Boolean {
        if (token.isNullOrBlank()) return false
        val existing = activeTokens[token] ?: return false
        val now = System.currentTimeMillis()
        if (now > existing.expiresAtMillis) {
            activeTokens.remove(token)
            return false
        }
        if (existing.mutationType != mutationType) {
            return false
        }
        // Section 3.1: Token validation requires an exact target match, including nullability.
        // A token issued for a specific target must never authorize targetId = null, and vice versa.
        if (existing.targetId != targetId) {
            return false
        }
        return activeTokens.remove(token, existing)
    }

    /**
     * Checks if a token is valid without consuming it (e.g. for pre-validation).
     */
    fun isTokenValid(token: String?, mutationType: MutationType, targetId: String? = null): Boolean {
        if (token.isNullOrBlank()) return false
        val existing = activeTokens[token] ?: return false
        val now = System.currentTimeMillis()
        if (now > existing.expiresAtMillis) return false
        if (existing.mutationType != mutationType) return false
        if (existing.targetId != targetId) return false
        return true
    }

    fun purgeExpiredTokens() {
        val now = System.currentTimeMillis()
        activeTokens.entries.removeIf { now > it.value.expiresAtMillis }
    }

    fun clearAllTokens() {
        activeTokens.clear()
    }
}
