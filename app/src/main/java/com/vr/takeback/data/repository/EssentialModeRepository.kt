package com.vr.takeback.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.vr.takeback.TakeBackApplication
import com.vr.takeback.domain.security.MutationType
import com.vr.takeback.domain.security.StrictModeManager
import com.vr.takeback.domain.security.StrictMutationGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

enum class EssentialModeStatus {
    DISABLED,
    MONITORING,
    ALLOWANCE_REACHED,
    ACTIVE,
    EMERGENCY_EXIT_ACTIVE,
    ENFORCEMENT_UNAVAILABLE
}

data class EssentialModeState(
    val isEnabled: Boolean = false,
    val dailyAllowanceMinutes: Int = 360, // 6 hours default
    val selectedAdditionalPackage: String? = "com.openai.chatgpt",
    val status: EssentialModeStatus = EssentialModeStatus.DISABLED,
    val dailyCycleDate: String = "",
    val activationEpochMillis: Long = 0L,
    val usageAtActivationMinutes: Int = 0,
    val isEmergencyExitActive: Boolean = false,
    val emergencyExitEpochMillis: Long = 0L,
    val customGoodLockModules: Set<String> = emptySet()
)

/**
 * Authoritative persistent repository for Essential Mode configuration and lifecycle.
 * Manages daily allowance, activation thresholds, Emergency Exit authentication, and daily cycle resets.
 */
class EssentialModeRepository(
    private val context: Context? = null,
    private val strictModeRepoProvider: (() -> StrictModeRepository)? = null,
    private val isStrictActiveProvider: (() -> Boolean)? = null
) {

    companion object {
        private const val TAG = "EssentialModeRepo"
        private const val PREFS_NAME = "takeback_essential_prefs"
        private const val KEY_ENABLED = "is_enabled"
        private const val KEY_ALLOWANCE_MINS = "daily_allowance_minutes"
        private const val KEY_SELECTED_ADDITIONAL = "selected_additional_package"
        private const val KEY_STATUS = "status"
        private const val KEY_CYCLE_DATE = "daily_cycle_date"
        private const val KEY_ACTIVATION_EPOCH = "activation_epoch_millis"
        private const val KEY_USAGE_AT_ACTIVATION = "usage_at_activation_minutes"
        private const val KEY_EMERGENCY_EXIT = "is_emergency_exit_active"
        private const val KEY_EMERGENCY_EXIT_EPOCH = "emergency_exit_epoch_millis"
        private const val KEY_CUSTOM_GOODLOCK = "custom_goodlock_modules"

        private fun safeLogI(msg: String) {
            try { Log.i(TAG, msg) } catch (t: Throwable) {}
        }

        private fun safeLogW(msg: String) {
            try { Log.w(TAG, msg) } catch (t: Throwable) {}
        }
    }

    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadPersistedState())
    val state: StateFlow<EssentialModeState> = _state.asStateFlow()

    private fun loadPersistedState(): EssentialModeState {
        if (prefs == null) {
            return EssentialModeState(
                dailyCycleDate = LocalDate.now().toString()
            )
        }
        val isEnabled = prefs.getBoolean(KEY_ENABLED, false)
        val allowance = prefs.getInt(KEY_ALLOWANCE_MINS, 360)
        val additional = prefs.getString(KEY_SELECTED_ADDITIONAL, "com.openai.chatgpt")
        val statusStr = prefs.getString(KEY_STATUS, EssentialModeStatus.DISABLED.name) ?: EssentialModeStatus.DISABLED.name
        val status = try {
            EssentialModeStatus.valueOf(statusStr)
        } catch (e: Exception) {
            if (isEnabled) EssentialModeStatus.MONITORING else EssentialModeStatus.DISABLED
        }
        val cycleDate = prefs.getString(KEY_CYCLE_DATE, LocalDate.now().toString()) ?: LocalDate.now().toString()
        val activationEpoch = prefs.getLong(KEY_ACTIVATION_EPOCH, 0L)
        val usageAtActivation = prefs.getInt(KEY_USAGE_AT_ACTIVATION, 0)
        val isEmergencyExit = prefs.getBoolean(KEY_EMERGENCY_EXIT, false)
        val emergencyExitEpoch = prefs.getLong(KEY_EMERGENCY_EXIT_EPOCH, 0L)
        val customModules = prefs.getStringSet(KEY_CUSTOM_GOODLOCK, emptySet()) ?: emptySet()

        return EssentialModeState(
            isEnabled = isEnabled,
            dailyAllowanceMinutes = allowance,
            selectedAdditionalPackage = additional,
            status = status,
            dailyCycleDate = cycleDate,
            activationEpochMillis = activationEpoch,
            usageAtActivationMinutes = usageAtActivation,
            isEmergencyExitActive = isEmergencyExit,
            emergencyExitEpochMillis = emergencyExitEpoch,
            customGoodLockModules = customModules
        )
    }

    @Synchronized
    private fun persistState(newState: EssentialModeState) {
        prefs?.edit()?.apply {
            putBoolean(KEY_ENABLED, newState.isEnabled)
            putInt(KEY_ALLOWANCE_MINS, newState.dailyAllowanceMinutes)
            putString(KEY_SELECTED_ADDITIONAL, newState.selectedAdditionalPackage)
            putString(KEY_STATUS, newState.status.name)
            putString(KEY_CYCLE_DATE, newState.dailyCycleDate)
            putLong(KEY_ACTIVATION_EPOCH, newState.activationEpochMillis)
            putInt(KEY_USAGE_AT_ACTIVATION, newState.usageAtActivationMinutes)
            putBoolean(KEY_EMERGENCY_EXIT, newState.isEmergencyExitActive)
            putLong(KEY_EMERGENCY_EXIT_EPOCH, newState.emergencyExitEpochMillis)
            putStringSet(KEY_CUSTOM_GOODLOCK, newState.customGoodLockModules)
            apply()
        }
    }

    private fun isStrictActive(): Boolean {
        if (isStrictActiveProvider != null) {
            return isStrictActiveProvider.invoke()
        }
        return try {
            TakeBackApplication.strictModeRepository.protectionState.value.isCurrentlyLocked
        } catch (e: Exception) {
            false
        }
    }

    @Volatile
    private var failedAttemptCount = 0
    @Volatile
    private var lockoutUntilEpochMillis = 0L

    fun isRateLimited(): Boolean = System.currentTimeMillis() < lockoutUntilEpochMillis

    fun getLockoutRemainingSeconds(): Int {
        val remaining = lockoutUntilEpochMillis - System.currentTimeMillis()
        return if (remaining > 0) ((remaining + 999) / 1000).toInt() else 0
    }

    private fun recordFailedAttempt() {
        failedAttemptCount++
        if (failedAttemptCount >= 5) {
            val lockoutSeconds = 30L * (failedAttemptCount - 4)
            lockoutUntilEpochMillis = System.currentTimeMillis() + (lockoutSeconds * 1000L)
        }
    }

    private fun resetFailedAttempts() {
        failedAttemptCount = 0
        lockoutUntilEpochMillis = 0L
    }

    fun hasConfiguredPin(): Boolean {
        val strictRepo = strictModeRepoProvider?.invoke() ?: runCatching { TakeBackApplication.strictModeRepository }.getOrNull()
        val strictState = strictRepo?.protectionState?.value
        return !strictState?.saltBase64.isNullOrEmpty() && !strictState?.passwordHash.isNullOrEmpty()
    }

    /**
     * Reconciles daily rollover.
     * When date advances past dailyCycleDate, Emergency Exit expires and cycle resets.
     */
    @Synchronized
    fun checkAndResetDailyCycle(today: LocalDate = LocalDate.now()): Boolean {
        val current = _state.value
        val todayStr = today.toString()
        if (current.dailyCycleDate != todayStr) {
            safeLogI("Daily cycle rollover detected: ${current.dailyCycleDate} -> $todayStr. Resetting Emergency Exit.")
            resetFailedAttempts()
            val newStatus = if (current.isEnabled) EssentialModeStatus.MONITORING else EssentialModeStatus.DISABLED
            val updated = current.copy(
                dailyCycleDate = todayStr,
                isEmergencyExitActive = false,
                emergencyExitEpochMillis = 0L,
                activationEpochMillis = 0L,
                usageAtActivationMinutes = 0,
                status = newStatus
            )
            _state.value = updated
            persistState(updated)
            return true
        }
        return false
    }

    /**
     * Determines whether Essential Mode is currently actively restricting non-exempt applications.
     * Invariant: Once activated (ACTIVE), Essential Mode remains locked active until:
     * 1. Authenticated Emergency Exit, or
     * 2. Next daily cycle rollover at midnight.
     * It NEVER deactivates due to lower usage snapshots, process restart, or reboot.
     */
    @Synchronized
    fun isCurrentlyActive(
        totalDailyUsageMinutes: Int,
        isEnforcementAvailable: Boolean = true,
        today: LocalDate = LocalDate.now()
    ): Boolean {
        checkAndResetDailyCycle(today)
        val current = _state.value

        if (!current.isEnabled) {
            return false
        }

        // Emergency Exit overrides until next daily cycle reset
        if (current.isEmergencyExitActive) {
            return false
        }

        // LATCH INVARIANT: Once ACTIVE, remain ACTIVE until Emergency Exit or midnight reset.
        if (current.status == EssentialModeStatus.ACTIVE) {
            return true
        }

        if (!isEnforcementAvailable) {
            return false
        }

        val reached = totalDailyUsageMinutes >= current.dailyAllowanceMinutes
        if (reached) {
            val updated = current.copy(
                status = EssentialModeStatus.ACTIVE,
                activationEpochMillis = System.currentTimeMillis(),
                usageAtActivationMinutes = totalDailyUsageMinutes
            )
            _state.value = updated
            persistState(updated)
            return true
        }

        return false
    }

    /**
     * Updates Essential Mode enabled state. Protected by StrictMutationGate when Strict Mode is active.
     */
    fun setEnabled(enabled: Boolean, token: String? = null): Boolean {
        val current = _state.value

        // Invariant: Cannot toggle off while ACTIVE. Must use Emergency Exit.
        if (!enabled && current.status == EssentialModeStatus.ACTIVE) {
            safeLogW("Cannot disable Essential Mode via toggle while ACTIVE. Must use Emergency Exit.")
            return false
        }

        // Invariant: Cannot enable without an existing configured Strict Mode PIN for Emergency Exit.
        if (enabled && !hasConfiguredPin()) {
            safeLogW("Cannot enable Essential Mode without a configured Strict Mode PIN.")
            return false
        }

        if (isStrictActive()) {
            val valid = StrictMutationGate.consumeToken(token, MutationType.UPDATE_ESSENTIAL_MODE)
            if (!valid) {
                safeLogW("Attempted to toggle Essential Mode without valid Strict Mode authorization token")
                return false
            }
        }

        val newStatus = if (enabled) {
            if (current.status == EssentialModeStatus.ACTIVE) EssentialModeStatus.ACTIVE else EssentialModeStatus.MONITORING
        } else {
            EssentialModeStatus.DISABLED
        }
        val updated = current.copy(
            isEnabled = enabled,
            status = newStatus
        )
        _state.value = updated
        persistState(updated)
        return true
    }

    /**
     * Updates daily usage allowance. Protected by StrictMutationGate when Strict Mode is active.
     * Prevents modification while Essential Mode is actively restricting.
     */
    fun setDailyAllowanceMinutes(minutes: Int, token: String? = null): Boolean {
        if (minutes < 15 || minutes > 1440) return false

        val current = _state.value
        // Part 11 Section 19: Once Essential Mode is active, prevent normal configuration changes
        if (current.status == EssentialModeStatus.ACTIVE) {
            safeLogW("Cannot modify daily allowance while Essential Mode is actively restricting")
            return false
        }

        if (isStrictActive()) {
            val valid = StrictMutationGate.consumeToken(token, MutationType.UPDATE_ESSENTIAL_MODE)
            if (!valid) {
                safeLogW("Attempted to modify daily allowance without valid Strict Mode authorization token")
                return false
            }
        }

        val updated = current.copy(dailyAllowanceMinutes = minutes)
        _state.value = updated
        persistState(updated)
        return true
    }

    /**
     * Updates the single user-selected additional allowed application.
     * Protected by StrictMutationGate when Strict Mode is active.
     * Prevents modification while Essential Mode is actively restricting.
     */
    fun setSelectedAdditionalPackage(packageName: String?, token: String? = null): Boolean {
        val current = _state.value
        if (current.status == EssentialModeStatus.ACTIVE) {
            safeLogW("Cannot change additional allowed application while Essential Mode is actively restricting")
            return false
        }

        if (isStrictActive()) {
            val valid = StrictMutationGate.consumeToken(token, MutationType.UPDATE_ESSENTIAL_MODE)
            if (!valid) {
                safeLogW("Attempted to modify additional application without valid Strict Mode authorization token")
                return false
            }
        }

        val updated = current.copy(selectedAdditionalPackage = packageName)
        _state.value = updated
        persistState(updated)
        return true
    }

    /**
     * Authenticates using the existing Strict Mode PIN/password and triggers Emergency Exit.
     * Deactivates Essential Mode for the remainder of the current local daily cycle.
     * Does NOT modify recorded usage, reset allowance, disable Strict Mode, or disable Device Admin.
     * Protected against rapid brute-force attempts with escalating lockout backoff.
     */
    fun authenticateAndTriggerEmergencyExit(pin: String): Boolean {
        if (isRateLimited()) {
            safeLogW("Emergency Exit rejected: Rate limited. Lockout remaining: ${getLockoutRemainingSeconds()}s")
            return false
        }

        val strictRepo = strictModeRepoProvider?.invoke() ?: runCatching { TakeBackApplication.strictModeRepository }.getOrNull()
        val strictState = strictRepo?.protectionState?.value

        val salt = strictState?.saltBase64
        val hash = strictState?.passwordHash

        if (salt.isNullOrEmpty() || hash.isNullOrEmpty()) {
            recordFailedAttempt()
            safeLogW("Emergency Exit attempted but no Strict Mode credential exists")
            return false
        }

        val isPinValid = StrictModeManager.verifyPin(pin, salt, hash)
        if (!isPinValid) {
            recordFailedAttempt()
            safeLogW("Emergency Exit authentication failed: incorrect credential (failed attempts: $failedAttemptCount)")
            return false
        }

        resetFailedAttempts()
        safeLogI("Emergency Exit authenticated successfully. Deactivating Essential Mode until next daily reset.")
        val current = _state.value
        val updated = current.copy(
            isEmergencyExitActive = true,
            emergencyExitEpochMillis = System.currentTimeMillis(),
            status = EssentialModeStatus.EMERGENCY_EXIT_ACTIVE
        )
        _state.value = updated
        persistState(updated)
        return true
    }

    /**
     * Authenticates Emergency Exit using a solved challenge token (supports both PIN and Random Text Challenge).
     */
    fun authenticateAndTriggerEmergencyExitWithToken(token: String): Boolean {
        if (StrictMutationGate.consumeToken(token, MutationType.UPDATE_ESSENTIAL_MODE)) {
            resetFailedAttempts()
            safeLogI("Emergency Exit authorized via challenge token. Deactivating Essential Mode until next daily reset.")
            val current = _state.value
            val updated = current.copy(
                isEmergencyExitActive = true,
                emergencyExitEpochMillis = System.currentTimeMillis(),
                status = EssentialModeStatus.EMERGENCY_EXIT_ACTIVE
            )
            _state.value = updated
            persistState(updated)
            return true
        }
        return false
    }

    fun addCustomGoodLockModule(packageName: String, token: String? = null): Boolean {
        val current = _state.value
        if (current.status == EssentialModeStatus.ACTIVE) {
            safeLogW("Cannot register custom Good Lock module while Essential Mode is ACTIVE")
            return false
        }

        if (isStrictActive()) {
            val valid = StrictMutationGate.consumeToken(token, MutationType.UPDATE_ESSENTIAL_MODE)
            if (!valid) {
                safeLogW("Attempted to register custom Good Lock module without valid Strict Mode authorization token")
                return false
            }
        }

        val pkg = packageName.trim()
        val resolver = runCatching { TakeBackApplication.essentialModeExemptionResolver }.getOrNull()
        if (resolver != null && !resolver.isValidGoodLockCandidate(pkg)) {
            safeLogW("Rejected registration: $pkg is not a valid installed Samsung Good Lock module")
            return false
        }

        val newSet = HashSet(current.customGoodLockModules).apply { add(pkg) }
        val updated = current.copy(customGoodLockModules = newSet)
        _state.value = updated
        persistState(updated)
        return true
    }

    fun removeCustomGoodLockModule(packageName: String, token: String? = null): Boolean {
        val current = _state.value
        if (current.status == EssentialModeStatus.ACTIVE) {
            safeLogW("Cannot remove custom Good Lock module while Essential Mode is ACTIVE")
            return false
        }

        if (isStrictActive()) {
            val valid = StrictMutationGate.consumeToken(token, MutationType.UPDATE_ESSENTIAL_MODE)
            if (!valid) {
                safeLogW("Attempted to remove custom Good Lock module without valid Strict Mode authorization token")
                return false
            }
        }

        val newSet = HashSet(current.customGoodLockModules).apply { remove(packageName.trim()) }
        val updated = current.copy(customGoodLockModules = newSet)
        _state.value = updated
        persistState(updated)
        return true
    }
}
