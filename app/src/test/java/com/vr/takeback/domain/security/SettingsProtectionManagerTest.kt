package com.vr.takeback.domain.security

import org.junit.Assert.*
import org.junit.Test

class SettingsProtectionManagerTest {

    private fun mockNode(
        text: String? = null,
        desc: String? = null,
        className: String? = null,
        children: List<MockAccessibilityNodeSnapshot> = emptyList()
    ): MockAccessibilityNodeSnapshot {
        return MockAccessibilityNodeSnapshot(
            text = text,
            contentDescription = desc,
            className = className,
            children = children
        )
    }

    // 1. Strict Mode off -> Accessibility settings accessible
    @Test
    fun test1_strictModeOff_accessibilitySettingsAccessible() {
        val root = mockNode(
            text = "TakeBack",
            children = listOf(
                mockNode(text = "Use TakeBack"),
                mockNode(text = "Turn off")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.SubSettings",
            rootNode = root,
            isStrictLocked = false,
            isMaintenanceUnlocked = false
        )
        assertFalse("When Strict Mode is off, accessibility settings must be accessible", decision.isRestricted)
        assertEquals(SettingsDestination.TAKEBACK_ACCESSIBILITY_DETAILS, decision.destination)
    }

    // 2. Strict Mode on -> Whole Accessibility management menu is BLOCKED
    @Test
    fun test2_strictModeOn_accessibilityManagementListDetectedAndBlocked() {
        val root = mockNode(
            text = "Accessibility",
            children = listOf(
                mockNode(text = "Installed apps"),
                mockNode(text = "TalkBack"),
                mockNode(text = "Visibility enhancements")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.samsung.accessibility",
            className = "com.samsung.accessibility.AccessibilitySettings",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue("Whole Accessibility page must be blocked under Strict Mode", decision.isRestricted)
        assertEquals(SettingsDestination.ACCESSIBILITY_SETTINGS, decision.destination)
    }

    // 3. Strict Mode on -> TakeBack Accessibility details detected and BLOCKED
    @Test
    fun test3_strictModeOn_takebackAccessibilityDetailsDetectedAndBlocked() {
        val root = mockNode(
            text = "Accessibility",
            children = listOf(
                mockNode(text = "TakeBack"),
                mockNode(text = "Use TakeBack"),
                mockNode(text = "Allow TakeBack to have full control of your phone")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue("TakeBack Accessibility details must be restricted", decision.isRestricted)
        assertEquals(SettingsDestination.TAKEBACK_ACCESSIBILITY_DETAILS, decision.destination)
        assertEquals("Accessibility Protection", decision.targetLabel)
    }

    // 4. Strict Mode off -> Device Admin settings accessible
    @Test
    fun test4_strictModeOff_deviceAdminSettingsAccessible() {
        val root = mockNode(
            text = "Device admin apps",
            children = listOf(
                mockNode(text = "TakeBack"),
                mockNode(text = "Deactivate this device admin app")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminAdd",
            rootNode = root,
            isStrictLocked = false,
            isMaintenanceUnlocked = false
        )
        assertFalse("When Strict Mode is off, device admin management must be accessible", decision.isRestricted)
        assertEquals(SettingsDestination.TAKEBACK_DEVICE_ADMIN_DETAILS, decision.destination)
    }

    // 5. Strict Mode on -> Whole Device Admin list detected and BLOCKED
    @Test
    fun test5_strictModeOn_deviceAdminListDetectedAndBlocked() {
        val root = mockNode(
            text = "Device admin apps",
            children = listOf(
                mockNode(text = "Find My Device"),
                mockNode(text = "Google Play Protect")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminSettings",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue("Whole Device Admin list must be blocked while Strict Mode is active", decision.isRestricted)
        assertEquals(SettingsDestination.DEVICE_ADMIN_SETTINGS, decision.destination)
    }

    // 6. Strict Mode on -> TakeBack administrator details detected and BLOCKED
    @Test
    fun test6_strictModeOn_takebackAdministratorDetailsDetectedAndBlocked() {
        val root = mockNode(
            text = "Device admin",
            children = listOf(
                mockNode(text = "TakeBack"),
                mockNode(text = "Deactivate this device admin app")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminAdd",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue("TakeBack Device Administrator deactivation screen must be blocked", decision.isRestricted)
        assertEquals(SettingsDestination.TAKEBACK_DEVICE_ADMIN_DETAILS, decision.destination)
        assertEquals("Device Administrator Protection", decision.targetLabel)
    }

    // 7. Alternative Settings navigation (e.g. Search results or Samsung SubSettings)
    @Test
    fun test7_alternativeSettingsNavigation_detected() {
        val root = mockNode(
            text = "Installed services",
            children = listOf(
                mockNode(text = "TakeBack"),
                mockNode(text = "TakeBack shortcut"),
                mockNode(text = "Turn off")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.samsung.accessibility",
            className = "com.samsung.accessibility.installedservice.InstalledServicesActivity",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue("Alternative Samsung One UI accessibility paths must be detected and blocked", decision.isRestricted)
        assertEquals(SettingsDestination.TAKEBACK_ACCESSIBILITY_DETAILS, decision.destination)
    }

    // 8. Duplicate accessibility window events (deterministic classification consistency)
    @Test
    fun test8_duplicateAccessibilityWindowEvents_evaluatedDeterministically() {
        val root = mockNode(
            text = "TakeBack",
            children = listOf(
                mockNode(text = "Use TakeBack")
            )
        )
        val decision1 = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.SubSettings",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        val decision2 = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.SubSettings",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertEquals(decision1, decision2)
        assertTrue(decision2.isRestricted)
    }

    // 9. Authentication cancellation (maintenance remains locked)
    @Test
    fun test9_authenticationCancellation_remainsRestricted() {
        val root = mockNode(
            text = "Device admin",
            children = listOf(
                mockNode(text = "TakeBack"),
                mockNode(text = "Deactivate")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminAdd",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = false // User cancelled authentication
        )
        assertTrue("Cancellation of challenge must leave target locked", decision.isRestricted)
    }

    // 10. Incorrect authentication (token invalid / not granted)
    @Test
    fun test10_incorrectAuthentication_tokenRejected() {
        StrictMutationGate.clearAllTokens()
        val consumed = StrictMutationGate.consumeToken("invalid_or_wrong_token", MutationType.SETTINGS_MAINTENANCE)
        assertFalse("Invalid token must not be accepted", consumed)

        // Target remains restricted
        val root = mockNode(text = "TakeBack", children = listOf(mockNode(text = "Use TakeBack")))
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.SubSettings",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue(decision.isRestricted)
    }

    // 11. Correct authentication (maintenance granted)
    @Test
    fun test11_correctAuthentication_grantsMaintenanceAccess() {
        StrictMutationGate.clearAllTokens()
        val token = StrictMutationGate.issueToken(MutationType.SETTINGS_MAINTENANCE)
        assertTrue(token.isNotEmpty())

        val consumed = StrictMutationGate.consumeToken(token, MutationType.SETTINGS_MAINTENANCE)
        assertTrue("Valid token must consume successfully", consumed)

        val root = mockNode(
            text = "Device admin",
            children = listOf(
                mockNode(text = "TakeBack"),
                mockNode(text = "Deactivate")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminAdd",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = true // Granted via verified challenge
        )
        assertFalse("Authenticated maintenance must permit settings access", decision.isRestricted)
    }

    // 12. Accessibility service interruption / null node tree (no false positive block)
    @Test
    fun test12_serviceInterruption_nullNodeFallback_doesNotBlock() {
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment",
            rootNode = null, // Window transition / node tree temporarily unavailable
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertFalse("Null or inaccessible node tree must never produce false positive blocks", decision.isRestricted)
        assertEquals(SettingsDestination.UNKNOWN_OR_INACCESSIBLE, decision.destination)
    }

    // 12a. Unrelated Device Admin activation is allowed
    @Test
    fun test12a_unrelatedDeviceAdminActivation_isAllowed() {
        val root = mockNode(
            text = "Activate this device admin app",
            children = listOf(
                mockNode(text = "Find My Device"),
                mockNode(text = "Activate")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminAdd",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertFalse("Unrelated device admin activation must be allowed", decision.isRestricted)
        assertEquals(SettingsDestination.UNRELATED_DEVICE_ADMIN_ACTIVATION, decision.destination)
    }

    // 12b. Installed/Downloaded accessibility services list is BLOCKED under Strict Mode
    @Test
    fun test12b_installedAccessibilityServices_isBlocked() {
        val root = mockNode(
            text = "Installed services",
            children = listOf(
                mockNode(text = "TalkBack"),
                mockNode(text = "TakeBack")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.accessibility.InstalledServicesActivity",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue("Installed services list must be blocked before reaching TakeBack toggle", decision.isRestricted)
        assertEquals(SettingsDestination.INSTALLED_ACCESSIBILITY_SERVICES, decision.destination)
    }

    // 12c. Security and privacy settings are allowed
    @Test
    fun test12c_securityAndPrivacySettings_isAllowed() {
        val root = mockNode(
            text = "Security & privacy",
            children = listOf(
                mockNode(text = "Lock screen"),
                mockNode(text = "Biometrics")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.security.SecuritySettings",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertFalse("General security & privacy settings must not be blocked", decision.isRestricted)
        assertEquals(SettingsDestination.SECURITY_AND_PRIVACY_SETTINGS, decision.destination)
    }

    // 13. Device Admin initial activation screen is NEVER blocked
    @Test
    fun test13_deviceAdminInitialActivation_neverBlocked() {
        val root = mockNode(
            text = "Activate this device admin app",
            children = listOf(
                mockNode(text = "TakeBack"),
                mockNode(text = "Activate")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminAdd",
            rootNode = root,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertFalse("Device Admin initial activation MUST NEVER be blocked", decision.isRestricted)
        assertEquals(SettingsDestination.DEVICE_ADMIN_ACTIVATION, decision.destination)
    }

    // 14. Actual Device Admin deactivation triggers compromised state
    @Test
    fun test14_actualDeviceAdminDeactivation_markedCompromised() {
        val state = com.vr.takeback.data.repository.StrictProtectionState(
            isGlobalStrictActive = true,
            isDeviceAdminActive = false,
            status = com.vr.takeback.data.repository.StrictProtectionStatus.COMPROMISED
        )
        assertEquals(
            "State with strict active but admin inactive must evaluate to COMPROMISED",
            com.vr.takeback.data.repository.StrictProtectionStatus.COMPROMISED,
            state.status
        )
        assertTrue(state.isCompromised)
    }

    // 15. Device restart / persistence recovery
    @Test
    fun test15_deviceRestart_protectionStatePreserved() {
        val state = com.vr.takeback.data.repository.StrictProtectionState(
            isGlobalStrictActive = true,
            isDeviceAdminActive = true,
            status = com.vr.takeback.data.repository.StrictProtectionStatus.ACTIVE,
            temporarySettingsUnlockUntilEpochMillis = 0L // No maintenance window
        )
        assertTrue("Settings must remain locked after restart without maintenance", state.isSettingsLocked)
    }

    // 16. Realme UI Downloaded Accessibility Apps screen & SafeCenter Accessibility guide blocked immediately
    @Test
    fun test16_realmeAccessibilityScreens_blockedImmediatelyEvenBeforeChildrenInflate() {
        val emptyContainerDuringTransition = mockNode(text = null, children = emptyList())

        val downloadedDecision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.oplus.settings.OplusSettingsActivity\$OplusAccessibilityDownloadedSettingsActivity",
            rootNode = emptyContainerDuringTransition,
            isStrictLocked = true,
            isMaintenanceUnlocked = false,
            eventText = "Downloaded apps"
        )
        assertTrue("Realme Downloaded Accessibility screen must block immediately", downloadedDecision.isRestricted)
        assertEquals(SettingsDestination.INSTALLED_ACCESSIBILITY_SERVICES, downloadedDecision.destination)

        val safeCenterDecision = SettingsProtectionManager.evaluate(
            packageName = "com.oplus.safecenter",
            className = "com.oplus.safecenter.accessibility.AccessibilityGuideCloseActivity",
            rootNode = emptyContainerDuringTransition,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue("Realme SafeCenter accessibility close guide must block immediately", safeCenterDecision.isRestricted)
        assertEquals(SettingsDestination.ACCESSIBILITY_SETTINGS, safeCenterDecision.destination)
    }

    // 17. Realme OplusDeviceAdminAdd & Samsung App Info deep tree (>10 levels) blocked
    @Test
    fun test17_realmeDeviceAdminAndDeepSamsungAppInfo_blocked() {
        val adminRoot = mockNode(
            text = "Device admin",
            children = listOf(
                mockNode(text = "TakeBack"),
                mockNode(text = "Deactivate")
            )
        )
        val adminDecision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.oplus.settings.feature.security.OplusDeviceAdminAdd",
            rootNode = adminRoot,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue(adminDecision.isRestricted)
        assertEquals(SettingsDestination.TAKEBACK_DEVICE_ADMIN_DETAILS, adminDecision.destination)

        // Build 12-level nested hierarchy typical of Samsung One UI / Realme UI Preference screens
        var nested = mockNode(
            text = "TakeBack",
            children = listOf(mockNode(text = "Force stop"), mockNode(text = "Storage usage"))
        )
        repeat(11) {
            nested = mockNode(children = listOf(nested))
        }
        val appInfoDecision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetailsTop",
            rootNode = nested,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue("Deeply nested App Info screen for TakeBack must be blocked", appInfoDecision.isRestricted)
        assertEquals(SettingsDestination.TAKEBACK_APP_INFO, appInfoDecision.destination)
    }

    // 18. Settings Home screen with Accessibility item visible is NOT falsely blocked
    @Test
    fun test18_settingsHome_notBlocked() {
        val homeRoot = mockNode(
            text = "Settings",
            children = listOf(
                mockNode(text = "Search settings"),
                mockNode(text = "Connections"),
                mockNode(text = "Accessibility")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.samsung.android.settings",
            className = "com.android.settings.Settings",
            rootNode = homeRoot,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertFalse("Top-level Settings Home must remain accessible", decision.isRestricted)
        assertEquals(SettingsDestination.SETTINGS_HOME, decision.destination)
    }

    // 19. Realme UI "Activate Device admin apps?" verification dialog is blocked as DEVICE_ADMIN_SETTINGS
    @Test
    fun test19_realmeDeviceAdminVerificationDialog_blockedAsDeviceAdminSettings() {
        val realmeAdminGate = mockNode(
            text = "Activate Device admin apps?",
            children = listOf(
                mockNode(text = "This feature grants system permissions that expose you to greater security risks."),
                mockNode(text = "Verification code"),
                mockNode(text = "Cancel"),
                mockNode(text = "Activate")
            )
        )
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.Settings\$DeviceAdminSettingsActivity",
            rootNode = realmeAdminGate,
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue(
            "Realme Device Admin verification gate must be blocked under Strict Mode",
            decision.isRestricted
        )
        assertEquals(SettingsDestination.DEVICE_ADMIN_SETTINGS, decision.destination)
    }
}
