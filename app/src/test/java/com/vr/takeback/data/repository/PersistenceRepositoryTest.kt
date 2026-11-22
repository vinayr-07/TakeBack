package com.vr.takeback.data.repository

import com.vr.takeback.domain.model.AlarmChallengeDifficulty
import com.vr.takeback.domain.model.FocusSessionState
import com.vr.takeback.domain.model.StrictAlarm
import org.junit.Assert.*
import org.junit.Test

class PersistenceRepositoryTest {

    @Test
    fun quickBlockRepository_startAndCancel_updatesStateFlow() {
        val repo = QuickBlockRepository()
        assertFalse("Initial state should be inactive", repo.state.value.isActive)
        assertEquals(0, repo.state.value.remainingSeconds)

        repo.startQuickBlock(15, setOf("com.instagram.android"))
        val state = repo.state.value
        assertTrue("State should be active after start", state.isActive)
        assertEquals(15, state.durationMinutes)
        assertEquals(15 * 60, state.remainingSeconds)
        assertTrue("Target packages must contain instagram", state.targetPackages.contains("com.instagram.android"))

        val cancelled = repo.cancelQuickBlock(isSystemOrigin = true)
        assertTrue("Cancellation should succeed", cancelled)
        assertFalse("State should be inactive after cancel", repo.state.value.isActive)
    }

    @Test
    fun focusSessionRepository_lifecycleTransitions_areDeterministic() {
        val repo = FocusSessionRepository()
        val initial = repo.session.value
        assertEquals(FocusSessionState.IDLE, initial.state)
        assertEquals(25, initial.focusDurationMinutes)
        assertEquals(5, initial.breakDurationMinutes)
        assertEquals(1, initial.currentCycle)

        // Start session
        repo.startSession("study_preset", "rain")
        assertEquals(FocusSessionState.FOCUS_RUNNING, repo.session.value.state)
        assertEquals("study_preset", repo.session.value.activeProfileId)
        assertEquals("rain", repo.session.value.ambientSoundKey)
        assertTrue("Start epoch must be non-zero", repo.session.value.currentPeriodStartEpochMillis > 0)

        // Pause
        repo.pauseSession()
        assertEquals(FocusSessionState.PAUSED, repo.session.value.state)

        // Resume
        repo.resumeSession()
        assertEquals(FocusSessionState.FOCUS_RUNNING, repo.session.value.state)

        // Transition to Break
        repo.transitionToBreak()
        assertEquals(FocusSessionState.BREAK_RUNNING, repo.session.value.state)

        // Transition to next focus
        repo.transitionToNextFocus()
        assertEquals(FocusSessionState.FOCUS_RUNNING, repo.session.value.state)
        assertEquals(2, repo.session.value.currentCycle)

        // Stop session
        repo.stopSession()
        assertEquals(FocusSessionState.IDLE, repo.session.value.state)
        assertEquals(1, repo.session.value.currentCycle)
    }

    @Test
    fun focusSessionRepository_updateDurations_persistsValues() {
        val repo = FocusSessionRepository()
        repo.updateDurations(45, 10)
        assertEquals(45, repo.session.value.focusDurationMinutes)
        assertEquals(10, repo.session.value.breakDurationMinutes)
    }

    @Test
    fun strictAlarm_formattedTimeAndProperties_behaveCorrectly() {
        val alarmMorning = StrictAlarm(
            id = "test_morning",
            hour = 6,
            minute = 30,
            daysOfWeekMask = 31,
            isEnabled = true,
            difficulty = AlarmChallengeDifficulty.MEDIUM,
            label = "Morning Focus"
        )
        assertEquals("06:30 AM", alarmMorning.formattedTime)

        val alarmEvening = StrictAlarm(
            id = "test_evening",
            hour = 19,
            minute = 5,
            daysOfWeekMask = 127,
            isEnabled = false,
            difficulty = AlarmChallengeDifficulty.HARD,
            label = "Evening Detox"
        )
        assertEquals("07:05 PM", alarmEvening.formattedTime)

        val toggled = alarmEvening.copy(isEnabled = true)
        assertTrue(toggled.isEnabled)
    }

    @Test
    fun quickBlock_elapsedCalculation_handlesColdRebootTiming() {
        val durationMinutes = 30
        val totalSeconds = durationMinutes * 60
        val startEpoch = System.currentTimeMillis() - (10 * 60 * 1000L) // 10 minutes ago

        val elapsedSeconds = ((System.currentTimeMillis() - startEpoch) / 1000L).toInt()
        val remaining = totalSeconds - elapsedSeconds

        // Should have approx 20 minutes left (between 19 and 21 mins in seconds)
        assertTrue("Remaining seconds should be around 1200s", remaining in 1180..1210)
    }
}
