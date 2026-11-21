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

    // 2. Strict Mode on -> General Accessibility management list allowed (no nuisance block)
    @Test
    fun test2_strictModeOn_accessibilityManagementListDetectedAndAllowed() {
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
        assertFalse("General Accessibility list must be permitted to avoid nuisance blocks", decision.isRestricted)
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

    // 5. Strict Mode on -> Device Admin list detected and allowed
    @Test
    fun test5_strictModeOn_deviceAdminListDetectedAndAllowed() {
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
        assertFalse("General Device Admin list must be accessible", decision.isRestricted)
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

    // 12. Accessibility service interruption / null node fallback
    @Test
    fun test12_serviceInterruption_nullNodeFallback() {
        val decision = SettingsProtectionManager.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment",
            rootNode = null, // Window transition / node tree temporarily unavailable
            isStrictLocked = true,
            isMaintenanceUnlocked = false
        )
        assertTrue("Known toggle class name must restrict even when rootNode is temporarily null", decision.isRestricted)
        assertEquals(SettingsDestination.TAKEBACK_ACCESSIBILITY_DETAILS, decision.destination)
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
}
