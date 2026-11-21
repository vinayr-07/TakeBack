package com.vr.takeback

import com.vr.takeback.domain.security.StrictModeManager
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class StrictModeManagerTest {

    @Test
    fun hashPin_generatesConsistentHash_forSamePinAndSalt() {
        val pin = "8492"
        val salt = StrictModeManager.generateSalt()

        val hash1 = StrictModeManager.hashPin(pin, salt)
        val hash2 = StrictModeManager.hashPin(pin, salt)

        assertEquals("Same PIN and salt must produce identical PBKDF2 hashes", hash1, hash2)
        assertTrue("Hash must be non-empty base64 string", hash1.isNotEmpty())
    }

    @Test
    fun verifyPin_returnsTrue_forCorrectPin() {
        val pin = "192837"
        val salt = StrictModeManager.generateSalt()
        val saltBase64 = Base64.getEncoder().encodeToString(salt)
        val hash = StrictModeManager.hashPin(pin, salt)

        val isValid = StrictModeManager.verifyPin(pin, saltBase64, hash)
        assertTrue("Verification must succeed with correct PIN", isValid)
    }

    @Test
    fun verifyPin_returnsFalse_forIncorrectPin() {
        val correctPin = "554433"
        val wrongPin = "554432"
        val salt = StrictModeManager.generateSalt()
        val saltBase64 = Base64.getEncoder().encodeToString(salt)
        val hash = StrictModeManager.hashPin(correctPin, salt)

        val isValid = StrictModeManager.verifyPin(wrongPin, saltBase64, hash)
        assertFalse("Verification must fail with incorrect PIN", isValid)
    }

    @Test
    fun generateTextChallenge_producesRequestedLength_andValidCharset() {
        val length = 65
        val challenge = StrictModeManager.generateTextChallenge(length)

        assertEquals("Challenge must have exact requested length", length, challenge.length)
        assertTrue("Challenge must contain alphanumeric characters", challenge.any { it.isLetterOrDigit() })
    }

    @Test
    fun verifyTextChallenge_validatesExactMatch() {
        val challenge = "Xk8#mQ29@Lp"
        assertTrue(StrictModeManager.verifyTextChallenge("Xk8#mQ29@Lp", challenge))
        assertFalse(StrictModeManager.verifyTextChallenge("Xk8#mQ29@Lp_extra", challenge))
        assertFalse(StrictModeManager.verifyTextChallenge("xk8#mQ29@Lp", challenge)) // Case sensitive
    }
}
