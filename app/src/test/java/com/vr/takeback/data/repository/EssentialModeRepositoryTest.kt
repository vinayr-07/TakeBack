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
    private lateinit var fakeStrictModeRepo: StrictModeRepository
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
        fakeStrictModeRepo = StrictModeRepository(initialState = fakeState)

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
        assertTrue("Default additional packages list starts empty (AI chatbots are exempt by default)", state.selectedAdditionalPackages.isEmpty())
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

        // Changing selected apps while ACTIVE must be rejected
        val appChanged = repository.setSelectedAdditionalPackages(listOf("notion.id"))
        assertFalse("Cannot change additional apps while Essential Mode is active", appChanged)
        assertTrue(repository.state.value.selectedAdditionalPackages.isEmpty())

        // Toggling off while ACTIVE must be rejected
        val toggledOff = repository.setEnabled(false)
        assertFalse("Cannot toggle off Essential Mode while ACTIVE", toggledOff)
        assertTrue(repository.state.value.isEnabled)
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)
    }

    @Test
    fun testLatchInvariant_remainsActive_evenIfReportedUsageDrops() {
        repository.setEnabled(true)
        repository.setDailyAllowanceMinutes(60)

        // Threshold reached -> ACTIVE
        assertTrue(repository.isCurrentlyActive(65))
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)

        // Subsequent query reports 0 or low delta (e.g. cold restart / transient error)
        assertTrue("Latch invariant: must stay ACTIVE when reported usage drops to 0", repository.isCurrentlyActive(0))
        assertTrue("Latch invariant: must stay ACTIVE when reported usage drops below allowance", repository.isCurrentlyActive(30))
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)
    }

    @Test
    fun testSetEnabled_requiresConfiguredPin() {
        // Setup repository without a configured PIN
        val unconfiguredState = StrictProtectionState(
            isGlobalStrictActive = false,
            saltBase64 = null,
            passwordHash = null
        )
        val unconfiguredStrictRepo = StrictModeRepository(initialState = unconfiguredState)
        val repoWithoutPin = EssentialModeRepository(
            context = null,
            strictModeRepoProvider = { unconfiguredStrictRepo },
            isStrictActiveProvider = { false }
        )

        assertFalse("hasConfiguredPin() must return false when no PIN is set", repoWithoutPin.hasConfiguredPin())
        val enableResult = repoWithoutPin.setEnabled(true)
        assertFalse("Enabling Essential Mode without a configured Strict Mode PIN must be rejected", enableResult)
        assertFalse(repoWithoutPin.state.value.isEnabled)
    }

    @Test
    fun testRateLimiting_lockoutAfterFiveFailedAttempts() {
        repository.setEnabled(true)
        repository.setDailyAllowanceMinutes(60)
        repository.isCurrentlyActive(70)

        assertFalse(repository.isRateLimited())
        assertEquals(0, repository.getLockoutRemainingSeconds())

        // 4 failed attempts: not yet locked out
        for (i in 1..4) {
            val ok = repository.authenticateAndTriggerEmergencyExit("wrong_$i")
            assertFalse(ok)
            assertFalse(repository.isRateLimited())
        }

        // 5th failed attempt: triggers lockout
        val fifthAttempt = repository.authenticateAndTriggerEmergencyExit("wrong_5")
        assertFalse(fifthAttempt)
        assertTrue("Must be rate limited after 5 failed attempts", repository.isRateLimited())
        assertTrue("Remaining lockout must be > 0", repository.getLockoutRemainingSeconds() > 0)

        // While locked out, even the correct PIN is rejected immediately
        val blockedCorrectPin = repository.authenticateAndTriggerEmergencyExit("1234")
        assertFalse("Must reject authentication during lockout period", blockedCorrectPin)
    }

    @Test
    fun testEmergencyExit_requiresPasswordOnly_rejectsBlankAndWrongPassword() {
        repository.setEnabled(true)
        repository.setDailyAllowanceMinutes(60)
        repository.isCurrentlyActive(70)
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)

        assertFalse("Blank password must fail", repository.authenticateAndTriggerEmergencyExit(""))
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)

        assertFalse("Wrong password must fail", repository.authenticateAndTriggerEmergencyExit("wrong"))
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)

        assertTrue("Valid password must trigger Emergency Exit", repository.authenticateAndTriggerEmergencyExit("1234"))
        assertTrue(repository.state.value.isEmergencyExitActive)
        assertEquals(EssentialModeStatus.EMERGENCY_EXIT_ACTIVE, repository.state.value.status)
    }

    @Test
    fun testCustomGoodLock_mutationGateAndActiveProtection() {
        // Disallowed while ACTIVE
        repository.setEnabled(true)
        repository.setDailyAllowanceMinutes(60)
        repository.isCurrentlyActive(70)
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)

        val addWhileActive = repository.addCustomGoodLockModule("com.samsung.android.test")
        assertFalse("Cannot add custom Good Lock module while ACTIVE", addWhileActive)
        val removeWhileActive = repository.removeCustomGoodLockModule("com.samsung.android.test")
        assertFalse("Cannot remove custom Good Lock module while ACTIVE", removeWhileActive)
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

    @Test
    fun testIsCurrentlyActive_requiresUsageMetricAvailable() {
        repository.setEnabled(true)
        repository.setDailyAllowanceMinutes(120)

        // Exceeded minutes but metric unavailable -> must NOT activate
        val inactiveResult = repository.isCurrentlyActive(
            totalDailyUsageMinutes = 150,
            isEnforcementAvailable = true,
            isUsageMetricAvailable = false
        )
        assertFalse("Should not activate when usage metric is unavailable", inactiveResult)
        assertEquals(EssentialModeStatus.MONITORING, repository.state.value.status)

        // Metric becomes available -> activates
        val activeResult = repository.isCurrentlyActive(
            totalDailyUsageMinutes = 150,
            isEnforcementAvailable = true,
            isUsageMetricAvailable = true
        )
        assertTrue("Should activate when usage metric is available and exceeds allowance", activeResult)
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)

        // Once ACTIVE, latch invariant holds even if metric is temporarily unavailable
        val stillActive = repository.isCurrentlyActive(
            totalDailyUsageMinutes = 0,
            isEnforcementAvailable = true,
            isUsageMetricAvailable = false
        )
        assertTrue("Latch invariant must keep Essential Mode active despite metric unavailability", stillActive)
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)
    }

    @Test
    fun testSetSelectedAdditionalPackages_validatesEligibilityAndLimit() {
        val fakeProvider = com.vr.takeback.domain.security.FakePackageInfoProvider()
        fakeProvider.installed.addAll(
            listOf("notion.id", "md.obsidian", "com.todoist", "com.openai.chatgpt", "com.supercell.clashofclans")
        )
        fakeProvider.appCategories["com.supercell.clashofclans"] = 0 // CATEGORY_GAME
        val resolver = com.vr.takeback.domain.security.EssentialModeExemptionResolver(fakeProvider)

        val repoWithResolver = EssentialModeRepository(
            context = null,
            strictModeRepoProvider = { fakeStrictModeRepo },
            isStrictActiveProvider = { false },
            exemptionResolverProvider = { resolver }
        )

        // 1. Prohibited game app must be rejected
        val gameResult = repoWithResolver.setSelectedAdditionalPackages(listOf("com.supercell.clashofclans"))
        assertFalse("Game app must be rejected as additional allowed package", gameResult)

        // 2. Already-exempt AI chatbot must be rejected so user doesn't waste one of 2 slots
        val chatGptResult = repoWithResolver.setSelectedAdditionalPackages(listOf("com.openai.chatgpt"))
        assertFalse("Default-exempt AI chatbot must not occupy a user-selected slot", chatGptResult)

        // 3. Up to 2 verified productivity apps must be accepted
        val twoAppsResult = repoWithResolver.setSelectedAdditionalPackages(listOf("notion.id", "md.obsidian"))
        assertTrue("Two verified productivity apps must be accepted", twoAppsResult)
        assertEquals(listOf("notion.id", "md.obsidian"), repoWithResolver.state.value.selectedAdditionalPackages)

        // 4. More than 2 apps must be rejected
        val threeAppsResult = repoWithResolver.setSelectedAdditionalPackages(listOf("notion.id", "md.obsidian", "com.todoist"))
        assertFalse("Selecting more than 2 additional apps must be rejected", threeAppsResult)
        assertEquals(listOf("notion.id", "md.obsidian"), repoWithResolver.state.value.selectedAdditionalPackages)

        // 5. Blank package name must be rejected
        val blankResult = repoWithResolver.setSelectedAdditionalPackages(listOf("   "))
        assertFalse("Blank package name must be rejected", blankResult)

        // 6. Clearing to empty list must be accepted
        val clearResult = repoWithResolver.setSelectedAdditionalPackages(emptyList())
        assertTrue("Clearing additional packages must be accepted", clearResult)
        assertTrue(repoWithResolver.state.value.selectedAdditionalPackages.isEmpty())

        // 7. Modification while ACTIVE must be rejected
        repoWithResolver.setEnabled(true)
        repoWithResolver.setDailyAllowanceMinutes(60)
        repoWithResolver.isCurrentlyActive(70)
        val activeModifyResult = repoWithResolver.setSelectedAdditionalPackages(listOf("notion.id"))
        assertFalse("Modifying additional packages while ACTIVE must be rejected", activeModifyResult)
    }

    @Test
    fun testFailClosedStrictMode_onStorageError() {
        val compromisedState = StrictProtectionState(
            isGlobalStrictActive = true,
            status = StrictProtectionStatus.STORAGE_ERROR,
            mechanism = StrictMechanism.PIN
        )
        val compromisedRepo = StrictModeRepository(initialState = compromisedState)
        val failClosedRepo = EssentialModeRepository(
            context = null,
            strictModeRepoProvider = { compromisedRepo },
            isStrictActiveProvider = null // Force repo to check strictModeRepoProvider
        )

        // Mutation without token must fail because STORAGE_ERROR is treated as active (fail-closed)
        val mutationResult = failClosedRepo.setDailyAllowanceMinutes(180, token = null)
        assertFalse("STORAGE_ERROR in Strict Mode must fail-closed and block unauthenticated mutation", mutationResult)
    }

    @Test
    fun testStrictMode_ratchet_cannotIncreaseDailyAllowance() {
        val repo = EssentialModeRepository(
            context = null,
            isStrictActiveProvider = { true }
        )
        // Default allowance is 360; attempt to increase to 420
        val result = repo.setDailyAllowanceMinutes(420)
        assertFalse("Increasing daily allowance in Strict Mode must be strictly rejected", result)
        assertEquals(360, repo.state.value.dailyAllowanceMinutes)
    }

    @Test
    fun testStrictMode_ratchet_canDecreaseDailyAllowance_withValidToken() {
        val repo = EssentialModeRepository(
            context = null,
            isStrictActiveProvider = { true }
        )
        val token = com.vr.takeback.domain.security.StrictMutationGate.issueToken(
            com.vr.takeback.domain.security.MutationType.UPDATE_ESSENTIAL_MODE
        )
        val result = repo.setDailyAllowanceMinutes(180, token = token)
        assertTrue("Decreasing daily allowance with valid token in Strict Mode must be allowed", result)
        assertEquals(180, repo.state.value.dailyAllowanceMinutes)
    }

    @Test
    fun testStrictMode_ratchet_cannotDecreaseDailyAllowance_withoutToken() {
        val repo = EssentialModeRepository(
            context = null,
            isStrictActiveProvider = { true }
        )
        val result = repo.setDailyAllowanceMinutes(180, token = null)
        assertFalse("Decreasing daily allowance without token in Strict Mode must be rejected", result)
        assertEquals(360, repo.state.value.dailyAllowanceMinutes)
    }

    @Test
    fun testStrictMode_ratchet_cannotDisableEssentialMode() {
        val repo = EssentialModeRepository(
            context = null,
            isStrictActiveProvider = { true }
        )
        val result = repo.setEnabled(false)
        assertFalse("Disabling Essential Mode in Strict Mode must be rejected", result)
    }

    @Test
    fun testDisarmStrict_preservesPinCredential_soEmergencyExitStillWorks() {
        val strictRepo = StrictModeRepository(
            initialState = StrictProtectionState(
                isGlobalStrictActive = true,
                isDeviceAdminActive = false,
                status = StrictProtectionStatus.ACTIVE,
                mechanism = com.vr.takeback.domain.model.StrictMechanism.PIN,
                saltBase64 = mockSalt,
                passwordHash = mockHash
            )
        )
        val essentialRepo = EssentialModeRepository(
            context = null,
            strictModeRepoProvider = { strictRepo },
            isStrictActiveProvider = { strictRepo.protectionState.value.isCurrentlyLocked }
        )

        assertTrue(essentialRepo.setEnabled(true, token = com.vr.takeback.domain.security.StrictMutationGate.issueToken(com.vr.takeback.domain.security.MutationType.UPDATE_ESSENTIAL_MODE)))
        assertTrue(essentialRepo.isCurrentlyActive(400))

        // Disarm Strict Mode with valid token
        val disarmToken = com.vr.takeback.domain.security.StrictMutationGate.issueToken(
            com.vr.takeback.domain.security.MutationType.DISARM_STRICT_MODE
        )
        assertTrue(strictRepo.disarmStrict(disarmToken))
        assertFalse(strictRepo.protectionState.value.isCurrentlyLocked)
        assertTrue("Saved PIN credential must survive disarming Strict Mode", essentialRepo.hasConfiguredPin())

        // User must still be able to exit Essential Mode with their password
        assertTrue(
            "Emergency Exit must accept the user's password even after Strict Mode was disarmed",
            essentialRepo.authenticateAndTriggerEmergencyExit("1234")
        )
        assertTrue(essentialRepo.state.value.isEmergencyExitActive)
    }

    @Test
    fun testRecoveryMissingPassword_allowedOnlyWhenStrictDisabledAndNoCredential() {
        val unconfiguredStrictRepo = StrictModeRepository(
            initialState = StrictProtectionState(
                isGlobalStrictActive = false,
                status = StrictProtectionStatus.DISABLED,
                saltBase64 = null,
                passwordHash = null
            )
        )
        val essentialRepo = EssentialModeRepository(
            context = null,
            strictModeRepoProvider = { unconfiguredStrictRepo },
            isStrictActiveProvider = { unconfiguredStrictRepo.protectionState.value.isCurrentlyLocked }
        )

        assertTrue(essentialRepo.canRecoverMissingPassword())
        assertFalse("Attempting password check when no credential exists must not lock out user", essentialRepo.authenticateAndTriggerEmergencyExit("1234"))
        assertFalse(essentialRepo.isRateLimited())

        assertFalse("Short recovery password must be rejected", essentialRepo.setRecoveryPasswordAndEmergencyExit("12"))
        assertTrue("Valid recovery password must save credential and exit Essential Mode", essentialRepo.setRecoveryPasswordAndEmergencyExit("myPass99"))
        assertTrue(essentialRepo.hasConfiguredPin())
        assertFalse(essentialRepo.canRecoverMissingPassword())
        assertTrue(essentialRepo.state.value.isEmergencyExitActive)
        assertTrue(essentialRepo.authenticateAndTriggerEmergencyExit("myPass99"))
    }

    @Test
    fun testToggleAfterEmergencyExit_clearsExitFlagAndAllowsReEnable() {
        isStrictActiveFlag = true
        val enableToken1 = StrictMutationGate.issueToken(MutationType.UPDATE_ESSENTIAL_MODE)
        assertTrue(repository.setEnabled(true, token = enableToken1))
        assertTrue(repository.setDailyAllowanceMinutes(60, token = StrictMutationGate.issueToken(MutationType.UPDATE_ESSENTIAL_MODE)))
        assertTrue(repository.isCurrentlyActive(90))
        assertEquals(EssentialModeStatus.ACTIVE, repository.state.value.status)

        // While ACTIVE, setEnabled(false) even with token must still be rejected in favor of password-only Exit
        val attemptedBypassToken = StrictMutationGate.issueToken(MutationType.UPDATE_ESSENTIAL_MODE)
        assertFalse("ACTIVE Essential Mode must require Emergency Exit password, not toggle", repository.setEnabled(false, token = attemptedBypassToken))

        // Exit Essential Mode with password -> EMERGENCY_EXIT_ACTIVE
        assertTrue(repository.authenticateAndTriggerEmergencyExit("1234"))
        assertTrue(repository.state.value.isEmergencyExitActive)
        assertEquals(EssentialModeStatus.EMERGENCY_EXIT_ACTIVE, repository.state.value.status)

        // User toggles Essential Mode BACK ON the same day -> must clear Emergency Exit pause & resume enforcement
        val resumeToken = StrictMutationGate.issueToken(MutationType.UPDATE_ESSENTIAL_MODE)
        assertTrue(repository.setEnabled(true, token = resumeToken))
        assertFalse("Re-enabling Essential Mode must clear isEmergencyExitActive", repository.state.value.isEmergencyExitActive)
        assertEquals(EssentialModeStatus.MONITORING, repository.state.value.status)
        assertTrue("Usage still over allowance must immediately reactivate enforcement", repository.isCurrentlyActive(90))

        // While MONITORING (after another Emergency Exit or before reaching allowance), valid Strict token can turn it off
        assertTrue(repository.authenticateAndTriggerEmergencyExit("1234"))
        val disableToken = StrictMutationGate.issueToken(MutationType.UPDATE_ESSENTIAL_MODE)
        assertTrue("Valid Strict Mode token must allow disabling auto-resume when not ACTIVE", repository.setEnabled(false, token = disableToken))
        assertFalse(repository.state.value.isEnabled)
        assertFalse(repository.state.value.isEmergencyExitActive)
        assertEquals(EssentialModeStatus.DISABLED, repository.state.value.status)
    }
}
