package com.vr.takeback.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.vr.takeback.domain.model.StrictMechanism
import com.vr.takeback.domain.security.StrictModeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Base64

data class StrictProtectionState(
    val isGlobalStrictActive: Boolean = false,
    val mechanism: StrictMechanism = StrictMechanism.TEXT_CHALLENGE,
    val saltBase64: String? = null,
    val passwordHash: String? = null,
    val temporaryUnlockUntilEpochMillis: Long = 0
) {
    val isCurrentlyLocked: Boolean
        get() = isGlobalStrictActive && System.currentTimeMillis() > temporaryUnlockUntilEpochMillis
}

class StrictModeRepository(private val context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("takeback_strict_prefs", Context.MODE_PRIVATE)

    private val _protectionState = MutableStateFlow(loadPersistedState())
    val protectionState: StateFlow<StrictProtectionState> = _protectionState.asStateFlow()

    private fun loadPersistedState(): StrictProtectionState {
        if (prefs == null) return StrictProtectionState()
        val isActive = prefs.getBoolean("is_strict_active", false)
        val mechanismName = prefs.getString("strict_mechanism", StrictMechanism.TEXT_CHALLENGE.name) ?: StrictMechanism.TEXT_CHALLENGE.name
        val mechanism = try { StrictMechanism.valueOf(mechanismName) } catch (e: Exception) { StrictMechanism.TEXT_CHALLENGE }
        val salt = prefs.getString("strict_salt", null)
        val hash = prefs.getString("strict_hash", null)
        val unlockUntil = prefs.getLong("unlock_until_epoch", 0L)

        return StrictProtectionState(
            isGlobalStrictActive = isActive,
            mechanism = mechanism,
            saltBase64 = salt,
            passwordHash = hash,
            temporaryUnlockUntilEpochMillis = unlockUntil
        )
    }

    private fun persistState(state: StrictProtectionState) {
        prefs?.edit()?.apply {
            putBoolean("is_strict_active", state.isGlobalStrictActive)
            putString("strict_mechanism", state.mechanism.name)
            putString("strict_salt", state.saltBase64)
            putString("strict_hash", state.passwordHash)
            putLong("unlock_until_epoch", state.temporaryUnlockUntilEpochMillis)
            apply()
        }
    }

    fun armStrictWithPin(pin: String) {
        val salt = StrictModeManager.generateSalt()
        val hash = StrictModeManager.hashPin(pin, salt)
        val saltBase64 = Base64.getEncoder().encodeToString(salt)

        val newState = StrictProtectionState(
            isGlobalStrictActive = true,
            mechanism = StrictMechanism.PIN,
            saltBase64 = saltBase64,
            passwordHash = hash,
            temporaryUnlockUntilEpochMillis = 0L
        )
        _protectionState.value = newState
        persistState(newState)
    }

    fun armStrictWithTextChallenge() {
        val newState = StrictProtectionState(
            isGlobalStrictActive = true,
            mechanism = StrictMechanism.TEXT_CHALLENGE,
            temporaryUnlockUntilEpochMillis = 0L
        )
        _protectionState.value = newState
        persistState(newState)
    }

    fun verifyPin(pin: String): Boolean {
        val state = _protectionState.value
        val salt = state.saltBase64 ?: return false
        val hash = state.passwordHash ?: return false

        val isValid = StrictModeManager.verifyPin(pin, salt, hash)
        if (isValid) {
            grantTemporaryMaintenanceWindow()
        }
        return isValid
    }

    fun verifyTextChallenge(input: String, expected: String): Boolean {
        val isValid = StrictModeManager.verifyTextChallenge(input, expected)
        if (isValid) {
            grantTemporaryMaintenanceWindow()
        }
        return isValid
    }

    fun grantTemporaryMaintenanceWindow(durationMinutes: Int = 10) {
        val until = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        val newState = _protectionState.value.copy(
            temporaryUnlockUntilEpochMillis = until
        )
        _protectionState.value = newState
        persistState(newState)
    }

    fun disarmStrict() {
        val newState = StrictProtectionState()
        _protectionState.value = newState
        persistState(newState)
    }
}
