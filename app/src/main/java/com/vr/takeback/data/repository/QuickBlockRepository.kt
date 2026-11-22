package com.vr.takeback.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QuickBlockState(
    val isActive: Boolean = false,
    val remainingSeconds: Int = 0,
    val durationMinutes: Int = 0,
    val startEpochMillis: Long = 0,
    val targetPackages: Set<String> = emptySet()
)

class QuickBlockRepository(
    private val context: Context? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("takeback_quickblock_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(QuickBlockState())
    val state: StateFlow<QuickBlockState> = _state.asStateFlow()

    private var timerJob: Job? = null

    init {
        loadPersistedState()
    }

    private fun loadPersistedState() {
        if (prefs == null) return
        val isActive = prefs.getBoolean("is_active", false)
        if (!isActive) return

        val durationMinutes = prefs.getInt("duration_minutes", 0)
        val startEpochMillis = prefs.getLong("start_epoch_millis", 0L)
        val targetPackagesRaw = prefs.getString("target_packages", "") ?: ""
        val targetPackages = if (targetPackagesRaw.isNotBlank()) targetPackagesRaw.split(",").toSet() else emptySet()

        val totalSeconds = durationMinutes * 60
        val elapsedSeconds = ((System.currentTimeMillis() - startEpochMillis) / 1000L).coerceAtLeast(0L).toInt()
        val remaining = totalSeconds - elapsedSeconds

        if (remaining > 0) {
            _state.value = QuickBlockState(
                isActive = true,
                remainingSeconds = remaining,
                durationMinutes = durationMinutes,
                startEpochMillis = startEpochMillis,
                targetPackages = targetPackages
            )
            resumeCountdown(remaining)
        } else {
            cancelQuickBlockInternal()
        }
    }

    fun startQuickBlock(durationMinutes: Int, targetPackages: Set<String> = emptySet()) {
        timerJob?.cancel()
        val totalSeconds = durationMinutes * 60
        val now = System.currentTimeMillis()

        _state.value = QuickBlockState(
            isActive = true,
            remainingSeconds = totalSeconds,
            durationMinutes = durationMinutes,
            startEpochMillis = now,
            targetPackages = targetPackages
        )

        prefs?.edit()?.apply {
            putBoolean("is_active", true)
            putInt("duration_minutes", durationMinutes)
            putLong("start_epoch_millis", now)
            putString("target_packages", targetPackages.joinToString(","))
            apply()
        }

        resumeCountdown(totalSeconds)
    }

    private fun resumeCountdown(initialSeconds: Int) {
        timerJob?.cancel()
        timerJob = coroutineScope.launch {
            var remaining = initialSeconds
            while (remaining > 0 && _state.value.isActive) {
                delay(1000L)
                remaining--
                _state.value = _state.value.copy(remainingSeconds = remaining)
            }
            if (remaining <= 0) {
                cancelQuickBlockInternal()
            }
        }
    }

    private fun cancelQuickBlockInternal() {
        timerJob?.cancel()
        _state.value = QuickBlockState()
        prefs?.edit()?.apply {
            putBoolean("is_active", false)
            putInt("duration_minutes", 0)
            putLong("start_epoch_millis", 0L)
            putString("target_packages", "")
            apply()
        }
    }

    fun cancelQuickBlock(token: String? = null, isSystemOrigin: Boolean = false): Boolean {
        val isStrict = try {
            com.vr.takeback.TakeBackApplication.strictModeRepository.protectionState.value.isCurrentlyLocked
        } catch (e: Exception) {
            false
        }

        if (isStrict && !isSystemOrigin) {
            val valid = com.vr.takeback.domain.security.StrictMutationGate.consumeToken(
                token,
                com.vr.takeback.domain.security.MutationType.CANCEL_QUICK_BLOCK,
                "quick_block"
            )
            if (!valid) {
                return false
            }
        }

        cancelQuickBlockInternal()
        return true
    }
}
