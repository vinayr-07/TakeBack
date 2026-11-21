package com.vr.takeback.domain.security

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Clean abstraction over accessibility node hierarchies.
 * Allows pure JVM unit testing of settings classification without mocking Android framework classes.
 */
interface AccessibilityNodeSnapshot {
    val text: CharSequence?
    val contentDescription: CharSequence?
    val className: CharSequence?
    val viewIdResourceName: String?
    val childCount: Int
    fun getChild(index: Int): AccessibilityNodeSnapshot?
}

/**
 * Real implementation wrapping Android's AccessibilityNodeInfo.
 */
class RealAccessibilityNodeSnapshot(private val node: AccessibilityNodeInfo) : AccessibilityNodeSnapshot {
    override val text: CharSequence? get() = node.text
    override val contentDescription: CharSequence? get() = node.contentDescription
    override val className: CharSequence? get() = node.className
    override val viewIdResourceName: String? get() = node.viewIdResourceName
    override val childCount: Int get() = node.childCount
    override fun getChild(index: Int): AccessibilityNodeSnapshot? {
        val child = node.getChild(index) ?: return null
        return RealAccessibilityNodeSnapshot(child)
    }
}

/**
 * Pure test mock implementation for unit tests.
 */
data class MockAccessibilityNodeSnapshot(
    override val text: CharSequence? = null,
    override val contentDescription: CharSequence? = null,
    override val className: CharSequence? = null,
    override val viewIdResourceName: String? = null,
    val children: List<MockAccessibilityNodeSnapshot> = emptyList()
) : AccessibilityNodeSnapshot {
    override val childCount: Int get() = children.size
    override fun getChild(index: Int): AccessibilityNodeSnapshot? = children.getOrNull(index)
}

/**
 * Fine-grained classification of Android Settings destinations.
 * Section 2.3: Separate classification cases for all distinct settings areas.
 */
enum class SettingsDestination {
    SETTINGS_HOME,
    ACCESSIBILITY_SETTINGS,
    INSTALLED_ACCESSIBILITY_SERVICES,
    TAKEBACK_ACCESSIBILITY_DETAILS,
    DEVICE_ADMIN_SETTINGS,
    TAKEBACK_DEVICE_ADMIN_DETAILS,
    DEVICE_ADMIN_ACTIVATION, // Initial activation for TakeBack
    UNRELATED_DEVICE_ADMIN_ACTIVATION, // Administrator activation for other apps
    SECURITY_AND_PRIVACY_SETTINGS,
    UNKNOWN_OR_INACCESSIBLE,
    TAKEBACK_APP_INFO,
    TAKEBACK_PERMISSION_SETTINGS,
    PACKAGE_INSTALLER_UNINSTALL,
    OTHER_SETTINGS
}

/**
 * Protection decision produced by SettingsProtectionManager.
 */
data class ProtectionDecision(
    val isRestricted: Boolean,
    val destination: SettingsDestination,
    val targetLabel: String,
    val warningMessage: String
)

/**
 * Centralized Settings Protection Manager.
 *
 * Classifies Settings screens and protects against:
 * 1. Disabling TakeBack's Accessibility Service.
 * 2. Deactivating TakeBack's Standard Device Administrator.
 * 3. Force-stopping or uninstalling TakeBack via App Info or Package Installer.
 *
 * Invariants:
 * - When Strict Mode is OFF or Maintenance is active: All screens accessible.
 * - Initial Device Administrator activation is NEVER blocked (prevents onboarding deadlock).
 * - General Settings and unrelated Accessibility/Device Admin tools are NOT blocked (avoids nuisance blocks).
 * - Only targeted attempts to disable/deactivate TakeBack trigger enforcement.
 */
object SettingsProtectionManager {

    private const val MAX_SEARCH_DEPTH = 8

    /**
     * Classifies a foreground screen based on package, class name, and node hierarchy.
     */
    fun classify(
        packageName: String,
        className: String?,
        rootNode: AccessibilityNodeSnapshot?
    ): SettingsDestination {
        val pkg = packageName.lowercase().trim()
        val cls = (className ?: "").lowercase().trim()

        // 1. Package Installer uninstallation screen
        if (pkg.contains("packageinstaller")) {
            if (rootNode != null && containsText(rootNode, "TakeBack", MAX_SEARCH_DEPTH)) {
                return SettingsDestination.PACKAGE_INSTALLER_UNINSTALL
            }
            return SettingsDestination.OTHER_SETTINGS
        }

        // 2. Validate Settings package
        val isSettingsPkg = pkg == "com.android.settings" ||
                pkg == "com.samsung.android.settings" ||
                pkg == "com.samsung.accessibility" ||
                pkg.contains(".settings")

        if (!isSettingsPkg) {
            return SettingsDestination.OTHER_SETTINGS
        }

        if (rootNode == null) {
            // Section 2.3: Unknown and incomplete node trees must NOT produce false positive classifications.
            // Never guess TakeBack details when node hierarchy is unavailable or incomplete.
            return SettingsDestination.UNKNOWN_OR_INACCESSIBLE
        }

        val hasTakeBack = containsText(rootNode, "TakeBack", MAX_SEARCH_DEPTH)

        // 3. Initial Device Admin activation screen
        val isDeviceAdminActivation = isDeviceAdminActivationConfirmation(cls, rootNode)
        if (isDeviceAdminActivation) {
            return if (hasTakeBack) {
                SettingsDestination.DEVICE_ADMIN_ACTIVATION // TakeBack's own activation (MUST NOT BE BLOCKED)
            } else {
                SettingsDestination.UNRELATED_DEVICE_ADMIN_ACTIVATION // Other app activation
            }
        }

        // 4. Device Admin Details targeting TakeBack (Deactivation destination)
        val isDeviceAdminContext = cls.contains("deviceadminadd") ||
                cls.contains("deviceadminsettings") ||
                containsText(rootNode, "Device admin", MAX_SEARCH_DEPTH) ||
                containsText(rootNode, "Device administrators", MAX_SEARCH_DEPTH)

        if (isDeviceAdminContext && hasTakeBack) {
            val hasDeactivateAction = containsText(rootNode, "Deactivate", MAX_SEARCH_DEPTH) ||
                    containsText(rootNode, "Turn off", MAX_SEARCH_DEPTH) ||
                    containsText(rootNode, "Remove", MAX_SEARCH_DEPTH)
            if (hasDeactivateAction) {
                return SettingsDestination.TAKEBACK_DEVICE_ADMIN_DETAILS
            }
        }

        // 5. Device Admin list view (General device admin list)
        if (isDeviceAdminContext && !hasTakeBack) {
            return SettingsDestination.DEVICE_ADMIN_SETTINGS
        }

        // 6. Accessibility Details targeting TakeBack (Disabling destination)
        val hasAccessibilityControls = containsText(rootNode, "Use TakeBack", MAX_SEARCH_DEPTH) ||
                containsText(rootNode, "TakeBack shortcut", MAX_SEARCH_DEPTH) ||
                containsText(rootNode, "Allow TakeBack", MAX_SEARCH_DEPTH) ||
                (hasTakeBack && containsText(rootNode, "Turn off", MAX_SEARCH_DEPTH)) ||
                (hasTakeBack && cls.contains("toggleaccessibilityservice"))

        val isInstalledServicesList = (cls.contains("installedservice") ||
                rootNode.text?.contains("Installed services", ignoreCase = true) == true ||
                rootNode.text?.contains("Installed apps", ignoreCase = true) == true) &&
                !hasAccessibilityControls

        if (isInstalledServicesList) {
            return SettingsDestination.INSTALLED_ACCESSIBILITY_SERVICES
        }

        val isAccessibilityContext = pkg == "com.samsung.accessibility" ||
                cls.contains("accessibility") ||
                cls.contains("installedservice") ||
                cls.contains("toggleaccessibilityservice") ||
                containsText(rootNode, "Accessibility", MAX_SEARCH_DEPTH)

        if (hasAccessibilityControls) {
            return SettingsDestination.TAKEBACK_ACCESSIBILITY_DETAILS
        }

        // 7. General Accessibility settings list view
        if (isAccessibilityContext && !hasTakeBack) {
            return SettingsDestination.ACCESSIBILITY_SETTINGS
        }

        // 8. TakeBack App Info screen (Force stop / Uninstall / Storage)
        if (hasTakeBack) {
            val isAppInfo = containsText(rootNode, "App info", MAX_SEARCH_DEPTH) ||
                    containsText(rootNode, "Force stop", MAX_SEARCH_DEPTH) ||
                    containsText(rootNode, "Uninstall", MAX_SEARCH_DEPTH) ||
                    containsText(rootNode, "Storage", MAX_SEARCH_DEPTH) ||
                    cls.contains("installedappdetails")
            if (isAppInfo) {
                return SettingsDestination.TAKEBACK_APP_INFO
            }

            val isPermissionSetting = containsText(rootNode, "Usage access", MAX_SEARCH_DEPTH) ||
                    containsText(rootNode, "Appear on top", MAX_SEARCH_DEPTH) ||
                    containsText(rootNode, "Display over other apps", MAX_SEARCH_DEPTH) ||
                    containsText(rootNode, "Special app access", MAX_SEARCH_DEPTH)
            if (isPermissionSetting) {
                return SettingsDestination.TAKEBACK_PERMISSION_SETTINGS
            }
        }

        // 9. Security and Privacy settings
        val isSecurityPrivacy = containsText(rootNode, "Security and privacy", MAX_SEARCH_DEPTH) ||
                containsText(rootNode, "Biometrics and security", MAX_SEARCH_DEPTH) ||
                containsText(rootNode, "Privacy", MAX_SEARCH_DEPTH) ||
                cls.contains("securitysettings")
        if (isSecurityPrivacy && !hasTakeBack) {
            return SettingsDestination.SECURITY_AND_PRIVACY_SETTINGS
        }

        // 10. General Settings Home
        val isSettingsHome = containsText(rootNode, "Connections", MAX_SEARCH_DEPTH) ||
                containsText(rootNode, "Sound and vibration", MAX_SEARCH_DEPTH) ||
                containsText(rootNode, "Notifications", MAX_SEARCH_DEPTH) ||
                containsText(rootNode, "Display", MAX_SEARCH_DEPTH) ||
                containsText(rootNode, "Search settings", MAX_SEARCH_DEPTH)
        if (isSettingsHome) {
            return SettingsDestination.SETTINGS_HOME
        }

        return SettingsDestination.OTHER_SETTINGS
    }

    /**
     * Evaluates whether a destination should be restricted based on Strict Mode state and maintenance status.
     */
    fun evaluate(
        packageName: String,
        className: String?,
        rootNode: AccessibilityNodeSnapshot?,
        isStrictLocked: Boolean,
        isMaintenanceUnlocked: Boolean
    ): ProtectionDecision {
        val destination = classify(packageName, className, rootNode)

        // Invariant 1: If Strict Mode is not active, all settings navigation is permitted
        if (!isStrictLocked) {
            return ProtectionDecision(
                isRestricted = false,
                destination = destination,
                targetLabel = destination.name,
                warningMessage = "Strict Mode is disabled."
            )
        }

        // Invariant 2: If temporary maintenance window is active, navigation is permitted
        if (isMaintenanceUnlocked) {
            return ProtectionDecision(
                isRestricted = false,
                destination = destination,
                targetLabel = destination.name,
                warningMessage = "Temporary maintenance access is active."
            )
        }

        // Invariant 3: Device Administrator initial activation is NEVER blocked
        if (destination == SettingsDestination.DEVICE_ADMIN_ACTIVATION) {
            return ProtectionDecision(
                isRestricted = false,
                destination = destination,
                targetLabel = "Device Admin Activation",
                warningMessage = "Initial Device Administrator activation is permitted."
            )
        }

        // Invariant 4: Protect critical deactivation / tamper destinations
        return when (destination) {
            SettingsDestination.TAKEBACK_ACCESSIBILITY_DETAILS -> ProtectionDecision(
                isRestricted = true,
                destination = destination,
                targetLabel = "Accessibility Protection",
                warningMessage = "Disabling TakeBack's Accessibility Service halts real-time application limits and website blocking. Strict Mode is protecting this service from premature disabling."
            )
            SettingsDestination.TAKEBACK_DEVICE_ADMIN_DETAILS -> ProtectionDecision(
                isRestricted = true,
                destination = destination,
                targetLabel = "Device Administrator Protection",
                warningMessage = "Deactivating TakeBack's Device Administrator removes anti-tamper and uninstallation protection. Strict Mode requires verification to modify this setting."
            )
            SettingsDestination.TAKEBACK_APP_INFO -> ProtectionDecision(
                isRestricted = true,
                destination = destination,
                targetLabel = "App Settings Protection",
                warningMessage = "Modifying TakeBack's application settings, clearing storage, or force-stopping the application is blocked while Strict Mode is active."
            )
            SettingsDestination.TAKEBACK_PERMISSION_SETTINGS -> ProtectionDecision(
                isRestricted = true,
                destination = destination,
                targetLabel = "Permission Protection",
                warningMessage = "Modifying TakeBack's special access permissions is restricted while Strict Mode is active."
            )
            SettingsDestination.PACKAGE_INSTALLER_UNINSTALL -> ProtectionDecision(
                isRestricted = true,
                destination = destination,
                targetLabel = "Uninstallation Protection",
                warningMessage = "Uninstalling TakeBack is blocked while Strict Mode is active."
            )
            // Non-target destinations, general settings, lists, unknown screens are NEVER blocked (avoids nuisance blocking)
            SettingsDestination.SETTINGS_HOME,
            SettingsDestination.ACCESSIBILITY_SETTINGS,
            SettingsDestination.INSTALLED_ACCESSIBILITY_SERVICES,
            SettingsDestination.DEVICE_ADMIN_SETTINGS,
            SettingsDestination.DEVICE_ADMIN_ACTIVATION,
            SettingsDestination.UNRELATED_DEVICE_ADMIN_ACTIVATION,
            SettingsDestination.SECURITY_AND_PRIVACY_SETTINGS,
            SettingsDestination.UNKNOWN_OR_INACCESSIBLE,
            SettingsDestination.OTHER_SETTINGS -> ProtectionDecision(
                isRestricted = false,
                destination = destination,
                targetLabel = destination.name,
                warningMessage = ""
            )
        }
    }

    private fun isDeviceAdminActivationConfirmation(
        className: String,
        rootNode: AccessibilityNodeSnapshot
    ): Boolean {
        val isAddClass = className.contains("deviceadminadd")
        val hasActivate = containsText(rootNode, "Activate this device admin app", MAX_SEARCH_DEPTH) ||
                containsText(rootNode, "Activate", MAX_SEARCH_DEPTH)
        val hasDeactivate = containsText(rootNode, "Deactivate", MAX_SEARCH_DEPTH) ||
                containsText(rootNode, "Turn off", MAX_SEARCH_DEPTH)
        return (isAddClass || hasActivate) && !hasDeactivate
    }

    private fun containsText(
        node: AccessibilityNodeSnapshot,
        query: String,
        depthRemaining: Int
    ): Boolean {
        if (depthRemaining <= 0) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (text.contains(query, ignoreCase = true) || desc.contains(query, ignoreCase = true)) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsText(child, query, depthRemaining - 1)) {
                return true
            }
        }
        return false
    }
}
