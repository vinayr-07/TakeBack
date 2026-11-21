package com.vr.takeback

import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.engine.SystemContextSnapshot
import com.vr.takeback.domain.model.*
import com.vr.takeback.domain.security.*
import com.vr.takeback.platform.alarm.ScheduleCoordinator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Comprehensive Integration and System Test Suite for TakeBack.
 *
 * Verifies multi-module interactions and full end-to-end system lifecycles:
 * 1. Integration: StrictModeManager + StrictMutationGate + Profile Mutation Lifecycle
 * 2. Integration: ScheduleCoordinator Alarm Boundaries + CentralRestrictionEngine State Transitions
 * 3. Integration: SettingsProtectionManager + StrictMutationGate Maintenance Access
 * 4. System: End-to-End 24-Hour Digital Wellbeing & Distraction Control Lifecycle
 */
class SystemAndIntegrationFlowTest {

    private lateinit var engine: CentralRestrictionEngine
    private val utcZone = ZoneId.of("UTC")

    @Before
    fun setUp() {
        engine = CentralRestrictionEngine()
        StrictMutationGate.clearAllTokens()
    }

    // =========================================================================
    // 1. INTEGRATION TEST: StrictMode + StrictMutationGate + Profile Lifecycle
    // =========================================================================

    @Test
    fun testIntegration_strictMode_tokenGate_profileMutationLifecycle() {
        // 1. User configures Strict Mode with a 6-digit PIN
        val userPin = "482910"
        val salt = StrictModeManager.generateSalt()
        val saltBase64 = java.util.Base64.getEncoder().encodeToString(salt)
        val pinHash = StrictModeManager.hashPin(userPin, salt)

        // Verify cryptographic verification contract
        assertTrue("PIN verification must succeed for valid PIN", StrictModeManager.verifyPin(userPin, saltBase64, pinHash))
        assertFalse("PIN verification must fail for invalid PIN", StrictModeManager.verifyPin("000000", saltBase64, pinHash))

        // 2. Strict Mode is armed; user has an active study profile
        val profileId = "study_profile_1"
        var profile = Profile(
            id = profileId,
            name = "Deep Study",
            isEnabled = true,
            targetApps = listOf(TargetApp("com.instagram.android", "Instagram"))
        )

        // 3. User attempts to toggle profile WITHOUT a token while strict mode is armed
        val isStrictActive = true
        var canMutateWithoutToken = !isStrictActive || StrictMutationGate.isTokenValid(null, MutationType.TOGGLE_PROFILE, profileId)
        assertFalse("Toggling profile without token during Strict Mode must be blocked", canMutateWithoutToken)

        // 4. User solves Strict Challenge: correct PIN entered -> Token issued
        val token = StrictMutationGate.issueToken(MutationType.TOGGLE_PROFILE, profileId)
        assertTrue("Token must be issued upon successful challenge solution", token.isNotBlank())
        assertTrue("Token must be valid for the specified mutation type and target",
            StrictMutationGate.isTokenValid(token, MutationType.TOGGLE_PROFILE, profileId))

        // 5. User applies token to perform profile toggle mutation
        val consumed = StrictMutationGate.consumeToken(token, MutationType.TOGGLE_PROFILE, profileId)
        assertTrue("Valid token must be successfully consumed", consumed)
        profile = profile.copy(isEnabled = !profile.isEnabled)
        assertFalse("Profile was successfully toggled to disabled", profile.isEnabled)

        // 6. Security Invariant: Replay Attack Prevention (Single-use token destroyed)
        val replayed = StrictMutationGate.consumeToken(token, MutationType.TOGGLE_PROFILE, profileId)
        assertFalse("Consumed token must not be reusable (Replay Attack blocked)", replayed)

        // 7. Security Invariant: Type Confusion Prevention
        val deleteToken = StrictMutationGate.issueToken(MutationType.TOGGLE_PROFILE, profileId)
        val wrongTypeAttempt = StrictMutationGate.consumeToken(deleteToken, MutationType.DELETE_PROFILE, profileId)
        assertFalse("Token for TOGGLE_PROFILE must not authorize DELETE_PROFILE", wrongTypeAttempt)

        // 8. Security Invariant: Target ID Confusion Prevention
        val wrongTargetAttempt = StrictMutationGate.consumeToken(deleteToken, MutationType.TOGGLE_PROFILE, "other_profile")
        assertFalse("Token issued for profile_1 must not authorize other_profile", wrongTargetAttempt)
    }

    // =========================================================================
    // 2. INTEGRATION TEST: ScheduleCoordinator & CentralRestrictionEngine Sync
    // =========================================================================

    @Test
    fun testIntegration_scheduleCoordinator_and_restrictionEngine_boundarySynchronization() {
        // Daytime Schedule: Monday from 09:00:00 to 17:00:00
        val schedule = Schedule(
            id = 501L,
            profileId = "work_sync_profile",
            daysOfWeekMask = 1, // Monday (1 shl 0 = 1)
            startTimeSeconds = 9 * 3600,  // 09:00
            endTimeSeconds = 17 * 3600,   // 17:00
            isEnabled = true
        )
        val profile = Profile(
            id = "work_sync_profile",
            name = "Work Sync",
            isEnabled = true,
            targetApps = listOf(TargetApp("com.reddit.frontpage", "Reddit")),
            schedules = listOf(schedule)
        )

        // Reference Date: Monday, Nov 23, 2026
        val mondayMorning = LocalDateTime.of(2026, 11, 23, 8, 30, 0)
        assertEquals(DayOfWeek.MONDAY, mondayMorning.dayOfWeek)

        // Coordinate Next Alarms via ScheduleCoordinator
        val nextStartEpoch = ScheduleCoordinator.computeNextStartOccurrenceMillis(schedule, mondayMorning, utcZone)
        assertNotNull(nextStartEpoch)
        val expectedStartEpoch = LocalDateTime.of(2026, 11, 23, 9, 0, 0).atZone(utcZone).toInstant().toEpochMilli()
        assertEquals("ScheduleCoordinator must calculate exact 09:00 start", expectedStartEpoch, nextStartEpoch)

        val nextEndEpoch = ScheduleCoordinator.computeNextEndOccurrenceMillis(schedule, mondayMorning, utcZone)
        assertNotNull(nextEndEpoch)
        val expectedEndEpoch = LocalDateTime.of(2026, 11, 23, 17, 0, 0).atZone(utcZone).toInstant().toEpochMilli()
        assertEquals("ScheduleCoordinator must calculate exact 17:00 end", expectedEndEpoch, nextEndEpoch)

        // Verify CentralRestrictionEngine flips EXACTLY at these boundaries:
        // 1 second before start (08:59:59) -> Allowed
        val snapBeforeStart = SystemContextSnapshot(
            timestampMillis = expectedStartEpoch - 1000L,
            localTime = LocalTime.of(8, 59, 59),
            dayOfWeek = DayOfWeek.MONDAY,
            foregroundPackageName = "com.reddit.frontpage"
        )
        val decisionBefore = engine.evaluate(listOf(profile), snapBeforeStart)
        assertTrue("Reddit must be ALLOWED 1 second before start boundary", decisionBefore is BlockDecision.Allowed)

        // At exact start boundary (09:00:00) -> Blocked
        val snapAtStart = snapBeforeStart.copy(
            timestampMillis = expectedStartEpoch,
            localTime = LocalTime.of(9, 0, 0)
        )
        val decisionStart = engine.evaluate(listOf(profile), snapAtStart)
        assertTrue("Reddit must be BLOCKED at exact 09:00:00 boundary", decisionStart is BlockDecision.Blocked)

        // 1 second before end boundary (16:59:59) -> Blocked
        val snapBeforeEnd = snapBeforeStart.copy(
            timestampMillis = expectedEndEpoch - 1000L,
            localTime = LocalTime.of(16, 59, 59)
        )
        val decisionBeforeEnd = engine.evaluate(listOf(profile), snapBeforeEnd)
        assertTrue("Reddit must remain BLOCKED 1 second before end boundary", decisionBeforeEnd is BlockDecision.Blocked)

        // At exact end boundary (17:00:00) -> Allowed
        val snapAtEnd = snapBeforeStart.copy(
            timestampMillis = expectedEndEpoch,
            localTime = LocalTime.of(17, 0, 0)
        )
        val decisionEnd = engine.evaluate(listOf(profile), snapAtEnd)
        assertTrue("Reddit must be ALLOWED at exact 17:00:00 boundary", decisionEnd is BlockDecision.Allowed)
    }

    // =========================================================================
    // 3. INTEGRATION TEST: SettingsProtectionManager & StrictMutationGate
    // =========================================================================

    @Test
    fun testIntegration_settingsProtection_and_strictMutationGate() {
        // Setup simulated Settings Screen hierarchies using MockAccessibilityNodeSnapshot

        // 1. TakeBack Accessibility Details screen
        val accessibilityNode = MockAccessibilityNodeSnapshot(
            text = "TakeBack Accessibility Service",
            className = "android.widget.TextView",
            children = listOf(
                MockAccessibilityNodeSnapshot(text = "Use TakeBack", className = "android.widget.Switch"),
                MockAccessibilityNodeSnapshot(text = "Stop", className = "android.widget.Button")
            )
        )
        val destAccessibility = SettingsProtectionManager.classify(
            packageName = "com.android.settings",
            className = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment",
            rootNode = accessibilityNode
        )
        assertEquals(SettingsDestination.TAKEBACK_ACCESSIBILITY_DETAILS, destAccessibility)

        // Protection decision when Strict Mode is ACTIVE
        val decisionActive = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment",
            rootNode = accessibilityNode,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue("Attempt to access TakeBack Accessibility screen while Strict Mode is active must be restricted",
            decisionActive.isRestricted)

        // 2. User solves challenge and requests temporary Settings Maintenance
        val maintenanceToken = StrictMutationGate.issueToken(MutationType.SETTINGS_MAINTENANCE)
        assertTrue(StrictMutationGate.isTokenValid(maintenanceToken, MutationType.SETTINGS_MAINTENANCE))

        // Authorize maintenance mode
        val isMaintenanceAuthorized = StrictMutationGate.consumeToken(maintenanceToken, MutationType.SETTINGS_MAINTENANCE)
        assertTrue("Maintenance token consumed and verified", isMaintenanceAuthorized)

        // Re-evaluate Settings Protection with Maintenance Active
        val decisionMaintenance = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment",
            rootNode = accessibilityNode,
            isStrictLocked = true,
            isMaintenanceUnlocked = true // User is in authorized maintenance
        )
        assertFalse("Settings access must be permitted when Maintenance mode is authorized",
            decisionMaintenance.isRestricted)

        // 3. Package Installer uninstall screen
        val uninstallNode = MockAccessibilityNodeSnapshot(
            text = "Do you want to uninstall TakeBack?",
            children = listOf(MockAccessibilityNodeSnapshot(text = "OK"))
        )
        val destUninstall = SettingsProtectionManager.classify(
            packageName = "com.google.android.packageinstaller",
            className = "com.android.packageinstaller.UninstallerActivity",
            rootNode = uninstallNode
        )
        assertEquals(SettingsDestination.PACKAGE_INSTALLER_UNINSTALL, destUninstall)

        val uninstallDecision = SettingsProtectionManager.evaluate(
            packageName = "com.google.android.packageinstaller",
            className = "com.android.packageinstaller.UninstallerActivity",
            rootNode = uninstallNode,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue("Package uninstaller for TakeBack must be restricted during strict mode",
            uninstallDecision.isRestricted)
    }

    // =========================================================================
    // 4. SYSTEM TEST: Full 24-Hour Digital Wellbeing Lifecycle
    // =========================================================================

    @Test
    fun testSystem_completeEndToEndDailyDistractionLifecycle() {
        val instagramPkg = "com.instagram.android"
        val whatsappPkg = "com.whatsapp"
        val phoneDialerPkg = "com.android.phone"
        val emergencySosPkg = "com.android.emergency"

        // Define Work Profile with Scheduled Restriction and Daily Quota
        val workProfile = Profile(
            id = "work_profile",
            name = "Work Focus",
            isEnabled = true,
            targetApps = listOf(
                TargetApp(instagramPkg, "Instagram", isAllowlist = false)
            ),
            schedules = listOf(
                Schedule(
                    id = 1,
                    profileId = "work_profile",
                    daysOfWeekMask = 31, // Mon-Fri
                    startTimeSeconds = 9 * 3600,  // 09:00
                    endTimeSeconds = 17 * 3600,   // 17:00
                    isEnabled = true
                )
            ),
            usageLimits = listOf(
                UsageLimit(
                    id = 1,
                    profileId = "work_profile",
                    targetPackageName = instagramPkg,
                    dailyLimitMinutes = 30, // Max 30 minutes daily
                    windowType = LimitWindowType.FULL_DAY,
                    isEnabled = true
                )
            )
        )
        val profiles = listOf(workProfile)

        // Phase 1: Morning (08:30 AM) - Outside Work Schedule, 0 minutes used
        val morningSnap = SystemContextSnapshot(
            timestampMillis = 1000L,
            localTime = LocalTime.of(8, 30),
            dayOfWeek = DayOfWeek.WEDNESDAY,
            foregroundPackageName = instagramPkg,
            accumulatedForegroundTimeMillis = mapOf(instagramPkg to 10 * 60 * 1000L) // 10 mins used
        )
        val morningDecision = engine.evaluate(profiles, morningSnap)
        assertTrue("Instagram must be ALLOWED before work schedule starts (10 mins used < 30 mins quota)",
            morningDecision is BlockDecision.Allowed)

        // Phase 2: Work Schedule Active (10:15 AM)
        val workSnap = morningSnap.copy(
            localTime = LocalTime.of(10, 15),
            accumulatedForegroundTimeMillis = mapOf(instagramPkg to 10 * 60 * 1000L)
        )
        val workDecision = engine.evaluate(profiles, workSnap)
        assertTrue("Instagram must be BLOCKED during work schedule", workDecision is BlockDecision.Blocked)
        val workBlocked = workDecision as BlockDecision.Blocked
        assertTrue("Reason must be ScheduleActive", workBlocked.reason is BlockReason.ScheduleActive)

        // Phase 3: Emergency Invariance (Critical Life Safety Check during active work lock)
        val callSnap = workSnap.copy(foregroundPackageName = phoneDialerPkg)
        val callDecision = engine.evaluate(profiles, callSnap)
        assertTrue("Phone dialer MUST NEVER be blocked during work schedule (Emergency Priority 0)",
            callDecision is BlockDecision.Allowed)

        val sosSnap = workSnap.copy(foregroundPackageName = emergencySosPkg)
        val sosDecision = engine.evaluate(profiles, sosSnap)
        assertTrue("Emergency SOS MUST NEVER be blocked under any circumstances",
            sosDecision is BlockDecision.Allowed)

        // Phase 4: Focus Session with Pomodoro Break Whitelist (11:00 AM)
        val focusSessionSnap = workSnap.copy(
            localTime = LocalTime.of(11, 0),
            isFocusSessionActive = true,
            isBreakActive = false,
            activeFocusSessionProfileId = "work_profile"
        )
        val focusDecision = engine.evaluate(profiles, focusSessionSnap)
        assertTrue("Instagram is blocked during active Focus Session", focusDecision is BlockDecision.Blocked)

        // Pomodoro 5-Minute Break Begins
        val breakSnap = focusSessionSnap.copy(
            isBreakActive = true,
            breakWhitelistApps = setOf(whatsappPkg),
            foregroundPackageName = whatsappPkg
        )
        val whatsappBreakDecision = engine.evaluate(profiles, breakSnap)
        assertTrue("WhatsApp must be ALLOWED during break due to Break Whitelist",
            whatsappBreakDecision is BlockDecision.Allowed)

        val instagramBreakDecision = engine.evaluate(profiles, breakSnap.copy(foregroundPackageName = instagramPkg))
        assertTrue("Instagram is NOT in break whitelist and must remain BLOCKED",
            instagramBreakDecision is BlockDecision.Blocked)

        // Phase 5: Evening (18:00 PM) - Outside Work Schedule, Testing Daily Quota
        // Case 5A: 25 minutes used (< 30 minutes quota)
        val eveningSnap25 = morningSnap.copy(
            localTime = LocalTime.of(18, 0),
            accumulatedForegroundTimeMillis = mapOf(instagramPkg to 25 * 60 * 1000L)
        )
        val eveningDecision25 = engine.evaluate(profiles, eveningSnap25)
        assertTrue("Instagram must be ALLOWED in evening when usage is 25m < 30m quota",
            eveningDecision25 is BlockDecision.Allowed)

        // Case 5B: User uses Instagram for 6 more minutes (total 31 minutes > 30 minutes quota)
        val eveningSnap31 = eveningSnap25.copy(
            accumulatedForegroundTimeMillis = mapOf(instagramPkg to 31 * 60 * 1000L)
        )
        val eveningDecision31 = engine.evaluate(profiles, eveningSnap31)
        assertTrue("Instagram must be BLOCKED when daily quota of 30m is exceeded",
            eveningDecision31 is BlockDecision.Blocked)
        val quotaBlocked = eveningDecision31 as BlockDecision.Blocked
        assertTrue("Block reason must be DailyLimitExceeded", quotaBlocked.reason is BlockReason.DailyLimitExceeded)
        assertEquals(30, (quotaBlocked.reason as BlockReason.DailyLimitExceeded).limitMinutes)

        // Phase 6: Midnight Rollover (00:00:01 Next Day)
        val midnightRolloverSnap = morningSnap.copy(
            localTime = LocalTime.of(0, 0, 1),
            dayOfWeek = DayOfWeek.THURSDAY,
            accumulatedForegroundTimeMillis = mapOf(instagramPkg to 0L) // Usage resets at midnight
        )
        val rolloverDecision = engine.evaluate(profiles, midnightRolloverSnap)
        assertTrue("Instagram must be ALLOWED immediately after midnight reset with fresh allowance",
            rolloverDecision is BlockDecision.Allowed)
    }
}
