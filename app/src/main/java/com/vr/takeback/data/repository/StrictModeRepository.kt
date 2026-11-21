package com.vr.takeback.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.vr.takeback.domain.model.StrictMechanism
import com.vr.takeback.domain.security.MutationType
import com.vr.takeback.domain.security.StrictModeManager
import com.vr.takeback.domain.security.StrictMutationGate
import com.vr.takeback.platform.receiver.TakeBackDeviceAdminReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Base64

enum class StrictProtectionStatus {
    DISABLED,
    ACTIVE,
    COMPROMISED // Armed by user, but Android Device Administrator was deactivated
}

data class StrictProtectionState(
    val isGlobalStrictActive: Boolean = false,
    val isDeviceAdminActive: Boolean = false,
    val status: StrictProtectionStatus = StrictProtectionStatus.DISABLED,
    val mechanism: StrictMechanism = StrictMechanism.TEXT_CHALLENGE,
    val saltBase64: String? = null,
    val passwordHash: String? = null,
    val temporaryUnlockUntilEpochMillis: Long = 0L,
    val temporarySettingsUnlockUntilEpochMillis: Long = 0L
) {
    val isCurrentlyLocked: Boolean
        get() = isGlobalStrictActive && status != StrictProtectionStatus.DISABLED

    val isSettingsLocked: Boolean
        get() = isCurrentlyLocked && System.currentTimeMillis() > temporarySettingsUnlockUntilEpochMillis

    val isCompromised: Boolean
        get() = status == StrictProtectionStatus.COMPROMISED
}

class StrictModeRepository(private val context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("takeback_strict_prefs", Context.MODE_PRIVATE)

    private val _protectionState = MutableStateFlow(loadPersistedState())
    val protectionState: StateFlow<StrictProtectionState> = _protectionState.asStateFlow()

    private fun loadPersistedState(): StrictProtectionState {
        val adminActive = context?.let { TakeBackDeviceAdminReceiver.isAdminActive(it) } ?: false
        if (prefs == null) return StrictProtectionState(isDeviceAdminActive = adminActive)

        val isActive = prefs.getBoolean("is_strict_active", false)
        val mechanismName = prefs.getString("strict_mechanism", StrictMechanism.TEXT_CHALLENGE.name) ?: StrictMechanism.TEXT_CHALLENGE.name
        val mechanism = try { StrictMechanism.valueOf(mechanismName) } catch (e: Exception) { StrictMechanism.TEXT_CHALLENGE }
        val salt = prefs.getString("strict_salt", null)
        val hash = prefs.getString("strict_hash", null)
        val unlockUntil = prefs.getLong("unlock_until_epoch", 0L)
        val settingsUnlockUntil = prefs.getLong("unlock_settings_until_epoch", 0L)

        val status = when {
            !isActive -> StrictProtectionStatus.DISABLED
            adminActive -> StrictProtectionStatus.ACTIVE
            else -> StrictProtectionStatus.COMPROMISED
        }

        return StrictProtectionState(
            isGlobalStrictActive = isActive,
            isDeviceAdminActive = adminActive,
            status = status,
            mechanism = mechanism,
            saltBase64 = salt,
            passwordHash = hash,
            temporaryUnlockUntilEpochMillis = unlockUntil,
            temporarySettingsUnlockUntilEpochMillis = settingsUnlockUntil
        )
    }

    private fun persistState(state: StrictProtectionState) {
        prefs?.edit()?.apply {
            putBoolean("is_strict_active", state.isGlobalStrictActive)
            putString("strict_mechanism", state.mechanism.name)
            putString("strict_salt", state.saltBase64)
            putString("strict_hash", state.passwordHash)
            putLong("unlock_until_epoch", state.temporaryUnlockUntilEpochMillis)
            putLong("unlock_settings_until_epoch", state.temporarySettingsUnlockUntilEpochMillis)
            apply()
        }
    }

    fun isDeviceAdminActive(): Boolean {
        return context?.let { TakeBackDeviceAdminReceiver.isAdminActive(it) } ?: false
    }

    /**
     * Synchronizes Device Administrator active status with actual DevicePolicyManager.
     */
    fun syncAdminState(isAdminActive: Boolean) {
        val current = _protectionState.value
        val newStatus = when {
            !current.isGlobalStrictActive -> StrictProtectionStatus.DISABLED
            isAdminActive -> StrictProtectionStatus.ACTIVE
            else -> StrictProtectionStatus.COMPROMISED // Armed but admin revoked!
        }
        val updated = current.copy(
            isDeviceAdminActive = isAdminActive,
            status = newStatus
        )
        _protectionState.value = updated
        persistState(updated)
    }

    fun refreshAdminStatus() {
        val adminActive = isDeviceAdminActive()
        syncAdminState(adminActive)
    }

    /**
     * Arms Strict Mode with a salted PIN.
     * Enforces that Android Device Administrator MUST be actively granted.
     * Returns false if Device Administrator is not active.
     */
    fun armStrictWithPin(pin: String): Boolean {
        val adminActive = isDeviceAdminActive()
        if (!adminActive) {
            return false
        }

        val salt = StrictModeManager.generateSalt()
        val hash = StrictModeManager.hashPin(pin, salt)
        val saltBase64 = Base64.getEncoder().encodeToString(salt)

        val newState = StrictProtectionState(
            isGlobalStrictActive = true,
            isDeviceAdminActive = true,
            status = StrictProtectionStatus.ACTIVE,
            mechanism = StrictMechanism.PIN,
            saltBase64 = saltBase64,
            passwordHash = hash,
            temporaryUnlockUntilEpochMillis = 0L
        )
        _protectionState.value = newState
        persistState(newState)
        return true
    }

    /**
     * Arms Strict Mode with a random text challenge.
     * Enforces that Android Device Administrator MUST be actively granted.
     * Returns false if Device Administrator is not active.
     */
    fun armStrictWithTextChallenge(): Boolean {
        val adminActive = isDeviceAdminActive()
        if (!adminActive) {
            return false
        }

        val newState = StrictProtectionState(
            isGlobalStrictActive = true,
            isDeviceAdminActive = true,
            status = StrictProtectionStatus.ACTIVE,
            mechanism = StrictMechanism.TEXT_CHALLENGE,
            temporaryUnlockUntilEpochMillis = 0L
        )
        _protectionState.value = newState
        persistState(newState)
        return true
    }

    /**
     * Verifies challenge input and issues a single-use mutation token via StrictMutationGate.
     */
    fun verifyAndIssueToken(
        input: String,
        mutationType: MutationType,
        targetId: String? = null,
        expectedChallenge: String? = null
    ): String? {
        val state = _protectionState.value
        val isValid = when (state.mechanism) {
            StrictMechanism.PIN -> {
                val salt = state.saltBase64 ?: return null
                val hash = state.passwordHash ?: return null
                StrictModeManager.verifyPin(input, salt, hash)
            }
            StrictMechanism.TEXT_CHALLENGE -> {
                val expected = expectedChallenge ?: return null
                StrictModeManager.verifyTextChallenge(input, expected)
            }
            else -> false
        }

        return if (isValid) {
            if (mutationType == MutationType.SETTINGS_MAINTENANCE) {
                grantTemporarySettingsUnlock(durationMinutes = 5)
            }
            StrictMutationGate.issueToken(mutationType, targetId)
        } else null
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

    fun grantTemporarySettingsUnlock(durationMinutes: Int = 5) {
        val until = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        val newState = _protectionState.value.copy(
            temporarySettingsUnlockUntilEpochMillis = until
        )
        _protectionState.value = newState
        persistState(newState)
    }

    fun grantTemporaryMaintenanceWindow(durationMinutes: Int = 10) {
        val until = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        val newState = _protectionState.value.copy(
            temporaryUnlockUntilEpochMillis = until,
            temporarySettingsUnlockUntilEpochMillis = until
        )
        _protectionState.value = newState
        persistState(newState)
    }

    /**
     * Disarms Strict Mode.
     * When locked, requires a valid token issued for DISARM_STRICT_MODE.
     */
    fun disarmStrict(token: String? = null): Boolean {
        val current = _protectionState.value
        if (current.isCurrentlyLocked) {
            val valid = StrictMutationGate.consumeToken(token, MutationType.DISARM_STRICT_MODE)
            if (!valid) {
                return false
            }
        }
        val newState = StrictProtectionState(
            isGlobalStrictActive = false,
            isDeviceAdminActive = isDeviceAdminActive(),
            status = StrictProtectionStatus.DISABLED
        )
        _protectionState.value = newState
        persistState(newState)
        return true
    }
}
