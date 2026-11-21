package com.vr.takeback.data.repository

import com.vr.takeback.domain.model.FocusSession
import com.vr.takeback.domain.model.FocusSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class FocusSessionRepository {

    private val _session = MutableStateFlow(
        FocusSession(
            sessionId = UUID.randomUUID().toString(),
            focusDurationMinutes = 25,
            breakDurationMinutes = 5,
            totalCycles = 4,
            currentCycle = 1,
            state = FocusSessionState.IDLE,
            breakWhitelistApps = setOf("com.whatsapp", "com.google.android.talk", "org.telegram.messenger")
        )
    )
    val session: StateFlow<FocusSession> = _session.asStateFlow()

    fun startSession(profileId: String? = "study_preset", soundKey: String? = null) {
        val current = _session.value
        _session.value = current.copy(
            state = FocusSessionState.FOCUS_RUNNING,
            activeProfileId = profileId,
            ambientSoundKey = soundKey,
            currentPeriodStartEpochMillis = System.currentTimeMillis(),
            currentPeriodDurationMillis = current.focusDurationMinutes * 60 * 1000L
        )
    }

    fun pauseSession() {
        val current = _session.value
        if (current.state == FocusSessionState.FOCUS_RUNNING || current.state == FocusSessionState.BREAK_RUNNING) {
            _session.value = current.copy(state = FocusSessionState.PAUSED)
        }
    }

    fun resumeSession() {
        val current = _session.value
        if (current.state == FocusSessionState.PAUSED) {
            _session.value = current.copy(state = FocusSessionState.FOCUS_RUNNING)
        }
    }

    fun transitionToBreak() {
        val current = _session.value
        _session.value = current.copy(
            state = FocusSessionState.BREAK_RUNNING,
            currentPeriodStartEpochMillis = System.currentTimeMillis(),
            currentPeriodDurationMillis = current.breakDurationMinutes * 60 * 1000L
        )
    }

    fun transitionToNextFocus() {
        val current = _session.value
        val nextCycle = current.currentCycle + 1
        if (nextCycle > current.totalCycles) {
            _session.value = current.copy(state = FocusSessionState.COMPLETED)
        } else {
            _session.value = current.copy(
                state = FocusSessionState.FOCUS_RUNNING,
                currentCycle = nextCycle,
                currentPeriodStartEpochMillis = System.currentTimeMillis(),
                currentPeriodDurationMillis = current.focusDurationMinutes * 60 * 1000L
            )
        }
    }

    fun stopSession() {
        _session.value = _session.value.copy(
            state = FocusSessionState.IDLE,
            currentCycle = 1
        )
    }

    fun updateDurations(focusMins: Int, breakMins: Int) {
        _session.value = _session.value.copy(
            focusDurationMinutes = focusMins,
            breakDurationMinutes = breakMins
        )
    }
}
