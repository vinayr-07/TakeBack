package com.vr.takeback.data.repository

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
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    private val _state = MutableStateFlow(QuickBlockState())
    val state: StateFlow<QuickBlockState> = _state.asStateFlow()

    private var timerJob: Job? = null

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

        timerJob = coroutineScope.launch {
            var remaining = totalSeconds
            while (remaining > 0 && _state.value.isActive) {
                delay(1000L)
                remaining--
                _state.value = _state.value.copy(remainingSeconds = remaining)
            }
            if (remaining <= 0) {
                cancelQuickBlock()
            }
        }
    }

    fun cancelQuickBlock() {
        timerJob?.cancel()
        _state.value = QuickBlockState()
    }
}
