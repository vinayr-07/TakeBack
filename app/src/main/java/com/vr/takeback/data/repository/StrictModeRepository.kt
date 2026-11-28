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
    ACTIVATION_PENDING,
    ACTIVE,
    INTERRUPTED, // Armed by user, but Android Device Administrator was deactivated
    COMPROMISED, // Device Administrator deactivated while Strict Mode was armed
    RECOVERY_REQUIRED, // Explicit user recovery required to restore protection
    STORAGE_ERROR // Fail-closed when persistence or storage fails
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
    // Section 2.2: Fail-closed. Active, Interrupted, Compromised, Recovery, or Storage Error all lock mutations.
    val isCurrentlyLocked: Boolean
        get() = isGlobalStrictActive ||
                status == StrictProtectionStatus.ACTIVE ||
                status == StrictProtectionStatus.INTERRUPTED ||
                status == StrictProtectionStatus.COMPROMISED ||
                status == StrictProtectionStatus.RECOVERY_REQUIRED ||
                status == StrictProtectionStatus.STORAGE_ERROR

    val isSettingsLocked: Boolean
        get() = isCurrentlyLocked && System.currentTimeMillis() > temporarySettingsUnlockUntilEpochMillis

    val isCompromised: Boolean
        get() = status == StrictProtectionStatus.INTERRUPTED ||
                status == StrictProtectionStatus.COMPROMISED ||
                status == StrictProtectionStatus.RECOVERY_REQUIRED
}

class StrictModeRepository(private val context: Context? = null, initialState: StrictProtectionState? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("takeback_strict_prefs", Context.MODE_PRIVATE)

    private val _protectionState = MutableStateFlow(initialState ?: loadPersistedState())
    val protectionState: StateFlow<StrictProtectionState> = _protectionState.asStateFlow()

    private fun loadPersistedState(): StrictProtectionState {
        val adminActive = context?.let { TakeBackDeviceAdminReceiver.isAdminActive(it) } ?: false
        if (prefs == null) {
            return StrictProtectionState(
                isDeviceAdminActive = adminActive,
                status = StrictProtectionStatus.DISABLED
            )
        }

        return try {
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
                else -> StrictProtectionStatus.INTERRUPTED // Armed by user, but admin is deactivated
            }

            StrictProtectionState(
                isGlobalStrictActive = isActive,
                isDeviceAdminActive = adminActive,
                status = status,
                mechanism = mechanism,
                saltBase64 = salt,
                passwordHash = hash,
                temporaryUnlockUntilEpochMillis = unlockUntil,
                temporarySettingsUnlockUntilEpochMillis = settingsUnlockUntil
            )
        } catch (e: Exception) {
            // Section 2.2 & 3.3 Fail-closed: Persistence read failure must NOT allow sensitive mutations.
            android.util.Log.e("StrictModeRepository", "Failed to read Strict Mode state; failing closed with STORAGE_ERROR", e)
            StrictProtectionState(
                isGlobalStrictActive = true,
                isDeviceAdminActive = adminActive,
                status = StrictProtectionStatus.STORAGE_ERROR
            )
        }
    }

    private fun persistState(state: StrictProtectionState) {
        try {
            prefs?.edit()?.apply {
                putBoolean("is_strict_active", state.isGlobalStrictActive)
                putString("strict_mechanism", state.mechanism.name)
                putString("strict_salt", state.saltBase64)
                putString("strict_hash", state.passwordHash)
                putLong("unlock_until_epoch", state.temporaryUnlockUntilEpochMillis)
                putLong("unlock_settings_until_epoch", state.temporarySettingsUnlockUntilEpochMillis)
                apply()
            }
        } catch (e: Exception) {
            android.util.Log.e("StrictModeRepository", "Failed to persist Strict Mode state", e)
            _protectionState.value = _protectionState.value.copy(status = StrictProtectionStatus.STORAGE_ERROR)
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
            else -> StrictProtectionStatus.INTERRUPTED // Armed but admin revoked!
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

    fun markActivationPending() {
        val current = _protectionState.value
        _protectionState.value = current.copy(status = StrictProtectionStatus.ACTIVATION_PENDING)
    }

    fun cancelActivation() {
        val current = _protectionState.value
        if (current.status == StrictProtectionStatus.ACTIVATION_PENDING) {
            _protectionState.value = current.copy(status = StrictProtectionStatus.DISABLED)
        }
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

        val current = _protectionState.value
        val newState = StrictProtectionState(
            isGlobalStrictActive = true,
            isDeviceAdminActive = true,
            status = StrictProtectionStatus.ACTIVE,
            mechanism = StrictMechanism.TEXT_CHALLENGE,
            saltBase64 = current.saltBase64,
            passwordHash = current.passwordHash,
            temporaryUnlockUntilEpochMillis = 0L
        )
        _protectionState.value = newState
        persistState(newState)
        return true
    }

    /**
     * Stores or updates the persistent PIN/password credential without forcing Strict Mode armed.
     * Used when recovering an Essential Mode exit credential if a previous version cleared it on disarm.
     */
    fun setCredential(pin: String): Boolean {
        if (pin.length < 4) return false
        val salt = StrictModeManager.generateSalt()
        val hash = StrictModeManager.hashPin(pin, salt)
        val saltBase64 = Base64.getEncoder().encodeToString(salt)
        val updated = _protectionState.value.copy(
            saltBase64 = saltBase64,
            passwordHash = hash
        )
        _protectionState.value = updated
        persistState(updated)
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
            status = StrictProtectionStatus.DISABLED,
            mechanism = if (!current.saltBase64.isNullOrEmpty() && !current.passwordHash.isNullOrEmpty()) {
                current.mechanism
            } else {
                StrictMechanism.TEXT_CHALLENGE
            },
            saltBase64 = current.saltBase64,
            passwordHash = current.passwordHash
        )
        _protectionState.value = newState
        persistState(newState)
        return true
    }
}
