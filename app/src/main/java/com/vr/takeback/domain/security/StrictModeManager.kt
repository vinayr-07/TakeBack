package com.vr.takeback.domain.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object StrictModeManager {

    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256
    private val secureRandom = SecureRandom()

    private val CHALLENGE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789#$@!"

    fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        return salt
    }

    fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(hash)
    }

    fun verifyPin(pin: String, saltBase64: String, expectedHashBase64: String): Boolean {
        return try {
            val salt = Base64.getDecoder().decode(saltBase64)
            val computedHash = hashPin(pin, salt)
            // Constant-time comparison to prevent timing attacks
            MessageDigest.isEqual(
                computedHash.toByteArray(Charsets.UTF_8),
                expectedHashBase64.toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            false
        }
    }

    fun generateTextChallenge(length: Int = 75): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val randomIndex = secureRandom.nextInt(CHALLENGE_CHARS.length)
            sb.append(CHALLENGE_CHARS[randomIndex])
        }
        return sb.toString()
    }

    fun verifyTextChallenge(input: String, expected: String): Boolean {
        return input.trim() == expected.trim()
    }
}
