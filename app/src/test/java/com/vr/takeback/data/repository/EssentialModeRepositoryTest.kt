package com.vr.takeback.data.repository

import com.vr.takeback.domain.model.StrictMechanism
import com.vr.takeback.domain.security.MutationType
import com.vr.takeback.domain.security.StrictModeManager
import com.vr.takeback.domain.security.StrictMutationGate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class EssentialModeRepositoryTest {

    private lateinit var repository: EssentialModeRepository
    private var isStrictActiveFlag: Boolean = false
    private var mockSalt: String? = null
    private var mockHash: String? = null

    @Before
    fun setUp() {
        StrictMutationGate.clearAllTokens()
        isStrictActiveFlag = false

        // Setup a mock StrictMode PIN "1234"
        val saltBytes = StrictModeManager.generateSalt()
        mockSalt = java.util.Base64.getEncoder().encodeToString(saltBytes)
        mockHash = StrictModeManager.hashPin("1234", saltBytes)

        val fakeState = StrictProtectionState(
            isGlobalStrictActive = true,
            isDeviceAdminActive = true,
            status = StrictProtectionStatus.ACTIVE,
            mechanism = StrictMechanism.PIN,
            saltBase64 = mockSalt,
            passwordHash = mockHash
        )
        val fakeStrictModeRepo = StrictModeRepository(initialState = fakeState)

        repository = EssentialModeRepository(
            context = null,
            strictModeRepoProvider = { fakeStrictModeRepo },
            isStrictActiveProvider = { isStrictActiveFlag }
        )
    }

    @Test
    fun testInitialState_isCorrect() {
        val state = repository.state.value
        assertFalse("Essential Mode should be disabled by default", state.isEnabled)
        assertEquals("Default daily allowance must be 360 minutes (6 hours)", 360, state.dailyAllowanceMinutes)
        assertEquals("Default additional package is ChatGPT", "com.openai.chatgpt", state.selectedAdditionalPackage)
        assertEquals("Default status is DISABLED", EssentialModeStatus.DISABLED, state.status)
        assertFalse("Emergency Exit is not active initially", state.isEmergencyExitActive)
    }

    @Test
    fun testSetEnabled_transitionsStatus() {
        val ok = repository.setEnabled(true)
        assertTrue(ok)
        val state = repository.state.value
        assertTrue(state.isEnabled)
        assertEquals(EssentialModeStatus.MONITORING, state.status)

        val disabledOk = repository.setEnabled(false)
        assertTrue(disabledOk)
        assertEquals(EssentialModeStatus.DISABLED, repository.state.value.status)
    }

    @Test
    fun testIsCurrentlyActive_triggersWhenAllowanceReached() {
        repository.setEnabled(true)
        repository.setDailyAllowanceMinutes(120) // 2 hours

        // Below allowance
        assertFalse("Should not be active at 119 minutes", repository.isCurrentlyActive(119))
        assertEquals(EssentialModeStatus.MONITORING, repository.state.value.status)

        // At allowance
        assertTrue("Should activate at 120 minutes", repository.isCurrentlyActive(120))
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)

        // Above allowance
        assertTrue("Should remain active at 150 minutes", repository.isCurrentlyActive(150))
    }

    @Test
    fun testIsCurrentlyActive_preservesActiveState_whenEnforcementTemporarilyUnavailable() {
        repository.setEnabled(true)
        repository.setDailyAllowanceMinutes(60)

        // Reach threshold
        assertTrue(repository.isCurrentlyActive(65))
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)

        // Temporary query failure or enforcement unavailable
        val stillActive = repository.isCurrentlyActive(0, isEnforcementAvailable = false)
        assertTrue("Temporary enforcement unavailability must NOT deactivate an already active Essential Mode", stillActive)
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)
    }

    @Test
    fun testEmergencyExit_withValidPin_deactivatesRestrictionsUntilMidnight() {
        repository.setEnabled(true)
        repository.setDailyAllowanceMinutes(60)
        assertTrue(repository.isCurrentlyActive(75))
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)

        // Attempt emergency exit with wrong PIN
        val wrongPinSuccess = repository.authenticateAndTriggerEmergencyExit("9999")
        assertFalse("Emergency Exit with invalid PIN must fail", wrongPinSuccess)
        assertTrue("Essential Mode must remain active after failed authentication", repository.isCurrentlyActive(75))

        // Attempt emergency exit with correct PIN "1234"
        val correctPinSuccess = repository.authenticateAndTriggerEmergencyExit("1234")
        assertTrue("Emergency Exit with correct PIN must succeed", correctPinSuccess)
        assertTrue(repository.state.value.isEmergencyExitActive)
        assertEquals(EssentialModeStatus.EMERGENCY_EXIT_ACTIVE, repository.state.value.status)

        // Restrictions must be suspended for the rest of the day
        assertFalse("Restrictions must be suspended while Emergency Exit is active", repository.isCurrentlyActive(80))
    }

    @Test
    fun testMidnightRollover_resetsEmergencyExit_andCycle() {
        val today = LocalDate.of(2026, 11, 22)
        repository.setEnabled(true)
        repository.setDailyAllowanceMinutes(60)
        repository.isCurrentlyActive(70, today = today)
        repository.authenticateAndTriggerEmergencyExit("1234")

        assertTrue(repository.state.value.isEmergencyExitActive)

        // Advance date to tomorrow
        val tomorrow = LocalDate.of(2026, 11, 23)
        val rolledOver = repository.checkAndResetDailyCycle(tomorrow)
        assertTrue("Daily cycle rollover must be recognized", rolledOver)

        val newState = repository.state.value
        assertFalse("Emergency Exit must expire at midnight rollover", newState.isEmergencyExitActive)
        assertEquals("Status must return to MONITORING after rollover", EssentialModeStatus.MONITORING, newState.status)
        assertEquals("2026-11-23", newState.dailyCycleDate)
    }

    @Test
    fun testConfigurationLocked_whileEssentialModeIsActive() {
        repository.setEnabled(true)
        repository.setDailyAllowanceMinutes(60)
        repository.isCurrentlyActive(65)
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)

        // Changing allowance while ACTIVE must be rejected
        val allowanceChanged = repository.setDailyAllowanceMinutes(120)
        assertFalse("Cannot modify allowance while Essential Mode is active", allowanceChanged)
        assertEquals(60, repository.state.value.dailyAllowanceMinutes)

        // Changing selected app while ACTIVE must be rejected
        val appChanged = repository.setSelectedAdditionalPackage("com.some.other.app")
        assertFalse("Cannot change additional app while Essential Mode is active", appChanged)
        assertEquals("com.openai.chatgpt", repository.state.value.selectedAdditionalPackage)
    }

    @Test
    fun testStrictModeGate_protectsEssentialModeMutations() {
        isStrictActiveFlag = true

        // Mutation without token must fail
        val withoutToken = repository.setEnabled(true, token = null)
        assertFalse("Mutation without token must be blocked by Strict Mode", withoutToken)

        // Issue valid token
        val token = StrictMutationGate.issueToken(MutationType.UPDATE_ESSENTIAL_MODE)
        val withToken = repository.setEnabled(true, token = token)
        assertTrue("Mutation with valid token must succeed", withToken)
        assertTrue(repository.state.value.isEnabled)
    }
}
