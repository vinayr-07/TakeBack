package com.vr.takeback.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.vr.takeback.domain.model.FocusSession
import com.vr.takeback.domain.model.FocusSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class FocusSessionRepository(private val context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("takeback_focus_session_prefs", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(loadPersistedSession())
    val session: StateFlow<FocusSession> = _session.asStateFlow()

    private fun loadPersistedSession(): FocusSession {
        if (prefs == null || !prefs.contains("session_id")) {
            return FocusSession(
                sessionId = UUID.randomUUID().toString(),
                focusDurationMinutes = 25,
                breakDurationMinutes = 5,
                totalCycles = 4,
                currentCycle = 1,
                state = FocusSessionState.IDLE,
                breakWhitelistApps = setOf("com.whatsapp", "com.google.android.talk", "org.telegram.messenger")
            )
        }

        val sessionId = prefs.getString("session_id", UUID.randomUUID().toString()) ?: UUID.randomUUID().toString()
        val focusMins = prefs.getInt("focus_duration_minutes", 25)
        val breakMins = prefs.getInt("break_duration_minutes", 5)
        val totalCycles = prefs.getInt("total_cycles", 4)
        val currentCycle = prefs.getInt("current_cycle", 1)
        val stateStr = prefs.getString("state", FocusSessionState.IDLE.name) ?: FocusSessionState.IDLE.name
        val savedState = try { FocusSessionState.valueOf(stateStr) } catch (e: Exception) { FocusSessionState.IDLE }
        val activeProfileId = prefs.getString("active_profile_id", null)
        val startEpochMillis = prefs.getLong("current_period_start_epoch_millis", 0L)
        val durationMillis = prefs.getLong("current_period_duration_millis", 0L)
        val whitelistRaw = prefs.getString("break_whitelist_apps", null)
        val whitelist = if (!whitelistRaw.isNullOrBlank()) {
            whitelistRaw.split(",").toSet()
        } else {
            setOf("com.whatsapp", "com.google.android.talk", "org.telegram.messenger")
        }
        val ambientSoundKey = prefs.getString("ambient_sound_key", null)

        val now = System.currentTimeMillis()
        val effectiveState = when (savedState) {
            FocusSessionState.FOCUS_RUNNING, FocusSessionState.BREAK_RUNNING -> {
                if (durationMillis > 0 && (now - startEpochMillis) >= durationMillis) {
                    FocusSessionState.IDLE
                } else {
                    savedState
                }
            }
            else -> savedState
        }

        return FocusSession(
            sessionId = sessionId,
            focusDurationMinutes = focusMins,
            breakDurationMinutes = breakMins,
            totalCycles = totalCycles,
            currentCycle = currentCycle,
            state = effectiveState,
            activeProfileId = activeProfileId,
            currentPeriodStartEpochMillis = startEpochMillis,
            currentPeriodDurationMillis = durationMillis,
            breakWhitelistApps = whitelist,
            ambientSoundKey = ambientSoundKey
        )
    }

    private fun persistSession(session: FocusSession) {
        prefs?.edit()?.apply {
            putString("session_id", session.sessionId)
            putInt("focus_duration_minutes", session.focusDurationMinutes)
            putInt("break_duration_minutes", session.breakDurationMinutes)
            putInt("total_cycles", session.totalCycles)
            putInt("current_cycle", session.currentCycle)
            putString("state", session.state.name)
            putString("active_profile_id", session.activeProfileId)
            putLong("current_period_start_epoch_millis", session.currentPeriodStartEpochMillis)
            putLong("current_period_duration_millis", session.currentPeriodDurationMillis)
            putString("break_whitelist_apps", session.breakWhitelistApps.joinToString(","))
            putString("ambient_sound_key", session.ambientSoundKey)
            apply()
        }
    }

    fun startSession(profileId: String? = "study_preset", soundKey: String? = null) {
        val current = _session.value
        val updated = current.copy(
            state = FocusSessionState.FOCUS_RUNNING,
            activeProfileId = profileId,
            ambientSoundKey = soundKey,
            currentPeriodStartEpochMillis = System.currentTimeMillis(),
            currentPeriodDurationMillis = current.focusDurationMinutes * 60 * 1000L
        )
        _session.value = updated
        persistSession(updated)
    }

    fun pauseSession() {
        val current = _session.value
        if (current.state == FocusSessionState.FOCUS_RUNNING || current.state == FocusSessionState.BREAK_RUNNING) {
            val updated = current.copy(state = FocusSessionState.PAUSED)
            _session.value = updated
            persistSession(updated)
        }
    }

    fun resumeSession() {
        val current = _session.value
        if (current.state == FocusSessionState.PAUSED) {
            val updated = current.copy(state = FocusSessionState.FOCUS_RUNNING)
            _session.value = updated
            persistSession(updated)
        }
    }

    fun transitionToBreak() {
        val current = _session.value
        val updated = current.copy(
            state = FocusSessionState.BREAK_RUNNING,
            currentPeriodStartEpochMillis = System.currentTimeMillis(),
            currentPeriodDurationMillis = current.breakDurationMinutes * 60 * 1000L
        )
        _session.value = updated
        persistSession(updated)
    }

    fun transitionToNextFocus() {
        val current = _session.value
        val nextCycle = current.currentCycle + 1
        val updated = if (nextCycle > current.totalCycles) {
            current.copy(state = FocusSessionState.COMPLETED)
        } else {
            current.copy(
                state = FocusSessionState.FOCUS_RUNNING,
                currentCycle = nextCycle,
                currentPeriodStartEpochMillis = System.currentTimeMillis(),
                currentPeriodDurationMillis = current.focusDurationMinutes * 60 * 1000L
            )
        }
        _session.value = updated
        persistSession(updated)
    }

    fun stopSession() {
        val updated = _session.value.copy(
            state = FocusSessionState.IDLE,
            currentCycle = 1
        )
        _session.value = updated
        persistSession(updated)
    }

    fun updateDurations(focusMins: Int, breakMins: Int) {
        val updated = _session.value.copy(
            focusDurationMinutes = focusMins,
            breakDurationMinutes = breakMins
        )
        _session.value = updated
        persistSession(updated)
    }
}
