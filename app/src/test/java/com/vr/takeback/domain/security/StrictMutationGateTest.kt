package com.vr.takeback.domain.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StrictMutationGateTest {

    @Before
    fun setUp() {
        StrictMutationGate.clearAllTokens()
    }

    @Test
    fun testIssueToken_andConsumeSuccessfully() {
        val tokenId = StrictMutationGate.issueToken(MutationType.TOGGLE_PROFILE, "profile_123")
        assertTrue("Token must be non-empty UUID string", tokenId.isNotEmpty())

        val isValid = StrictMutationGate.isTokenValid(tokenId, MutationType.TOGGLE_PROFILE, "profile_123")
        assertTrue("Token must be valid before consumption", isValid)

        val consumed = StrictMutationGate.consumeToken(tokenId, MutationType.TOGGLE_PROFILE, "profile_123")
        assertTrue("Token must be successfully consumed", consumed)

        // Second consumption must fail (single-use invariant)
        val reused = StrictMutationGate.consumeToken(tokenId, MutationType.TOGGLE_PROFILE, "profile_123")
        assertFalse("Token must not be reusable after consumption", reused)
    }

    @Test
    fun testMismatchedMutationType_isRejected() {
        val tokenId = StrictMutationGate.issueToken(MutationType.TOGGLE_PROFILE, "profile_123")

        val wrongTypeConsumed = StrictMutationGate.consumeToken(tokenId, MutationType.DELETE_PROFILE, "profile_123")
        assertFalse("Token issued for TOGGLE_PROFILE must not authorize DELETE_PROFILE", wrongTypeConsumed)

        // Token should still exist because mismatched attempt failed
        val correctConsumed = StrictMutationGate.consumeToken(tokenId, MutationType.TOGGLE_PROFILE, "profile_123")
        assertTrue("Token remains available for its intended mutation type", correctConsumed)
    }

    @Test
    fun testMismatchedTargetId_isRejected() {
        val tokenId = StrictMutationGate.issueToken(MutationType.TOGGLE_PROFILE, "profile_1")

        val wrongTargetConsumed = StrictMutationGate.consumeToken(tokenId, MutationType.TOGGLE_PROFILE, "profile_2")
        assertFalse("Token issued for profile_1 must not authorize profile_2", wrongTargetConsumed)
    }

    @Test
    fun testNullOrEmptyToken_isRejected() {
        assertFalse(StrictMutationGate.consumeToken(null, MutationType.TOGGLE_PROFILE))
        assertFalse(StrictMutationGate.consumeToken("", MutationType.TOGGLE_PROFILE))
        assertFalse(StrictMutationGate.consumeToken("non_existent_token", MutationType.TOGGLE_PROFILE))
    }
}
