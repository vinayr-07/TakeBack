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

    private const val MAX_SEARCH_DEPTH = 20
    private const val MAX_VISITED_NODES = 350

    private val SETTINGS_AND_TAMPER_PACKAGES = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.samsung.accessibility",
        "com.oplus.settings",
        "com.coloros.settings",
        "com.realme.settings",
        "com.oplus.appdetail",
        "com.coloros.appdetail",
        "com.oplus.safecenter",
        "com.coloros.safecenter",
        "com.oplus.securitypermission",
        "com.coloros.securitypermission",
        "com.oplus.apprecover"
    )

    private val PACKAGE_INSTALLER_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.oplus.packageinstaller",
        "com.coloros.packageinstaller"
    )

    fun isProtectedSettingsPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase().trim()
        return SETTINGS_AND_TAMPER_PACKAGES.contains(pkg) ||
                PACKAGE_INSTALLER_PACKAGES.contains(pkg) ||
                pkg.contains(".settings") ||
                pkg.contains("packageinstaller")
    }

    /**
     * Classifies a foreground screen based on package, class name, node hierarchy, and optional event text.
     */
    fun classify(
        packageName: String,
        className: String?,
        rootNode: AccessibilityNodeSnapshot?,
        eventText: String? = null
    ): SettingsDestination {
        val pkg = packageName.lowercase().trim()
        val cls = (className ?: "").lowercase().trim()
        val evText = (eventText ?: "").lowercase().trim()

        if (rootNode == null) {
            // Preserve null-node safety invariant: never guess from null node trees during transitions.
            return SettingsDestination.UNKNOWN_OR_INACCESSIBLE
        }

        val hasTakeBack = containsText(rootNode, "TakeBack") ||
                containsText(rootNode, "com.vr.takeback") ||
                evText.contains("takeback") ||
                evText.contains("com.vr.takeback")

        // 1. Package Installer / OEM App Recover uninstallation screen
        if (PACKAGE_INSTALLER_PACKAGES.contains(pkg) || pkg.contains("packageinstaller") || pkg == "com.oplus.apprecover") {
            if (hasTakeBack) {
                return SettingsDestination.PACKAGE_INSTALLER_UNINSTALL
            }
            return SettingsDestination.OTHER_SETTINGS
        }

        // 2. Validate Settings / OEM Security / AppDetail package
        val isSettingsPkg = SETTINGS_AND_TAMPER_PACKAGES.contains(pkg) || pkg.contains(".settings")
        if (!isSettingsPkg) {
            return SettingsDestination.OTHER_SETTINGS
        }

        // 3. Initial Device Admin activation screen (MUST NOT be blocked so activation/recovery never deadlocks)
        val isDeviceAdminActivation = isDeviceAdminActivationConfirmation(cls, rootNode, evText)
        if (isDeviceAdminActivation) {
            return if (hasTakeBack) {
                SettingsDestination.DEVICE_ADMIN_ACTIVATION
            } else {
                SettingsDestination.UNRELATED_DEVICE_ADMIN_ACTIVATION
            }
        }

        // 4. Device Admin Details & Whole Device Admin Access Page (AOSP, Samsung One UI, Realme / OplusDeviceAdminAdd)
        val isDeviceAdminContext = cls.contains("deviceadmin") ||
                evText.contains("device admin") ||
                evText.contains("admin apps") ||
                containsText(rootNode, "Device admin") ||
                containsText(rootNode, "Device administrators") ||
                containsText(rootNode, "Device admin apps") ||
                containsText(rootNode, "Phone administrators") ||
                containsText(rootNode, "Admin apps")

        if (isDeviceAdminContext) {
            if (hasTakeBack && (containsText(rootNode, "Deactivate") ||
                        containsText(rootNode, "Turn off") ||
                        containsText(rootNode, "Remove") ||
                        cls.contains("deviceadminadd"))
            ) {
                return SettingsDestination.TAKEBACK_DEVICE_ADMIN_DETAILS
            }
            return SettingsDestination.DEVICE_ADMIN_SETTINGS
        }

        // 5. Whole Accessibility Settings Page (Main Accessibility menu, Installed/Downloaded apps, and Service Details)
        // Covers AOSP, Samsung One UI (com.samsung.accessibility), and Realme / ColorOS (OplusAccessibilityDownloadedSettingsActivity, AccessibilitySettingsActivity, com.oplus.safecenter/.accessibility)
        val isAccessibilityClass = pkg == "com.samsung.accessibility" ||
                (pkg == "com.oplus.safecenter" && (cls.contains("accessibility") || evText.contains("accessibility"))) ||
                (pkg == "com.coloros.safecenter" && (cls.contains("accessibility") || evText.contains("accessibility"))) ||
                cls.contains("accessibility") ||
                cls.contains("installedservice") ||
                cls.contains("toggleaccessibilityservice") ||
                cls.contains("accessibilitydownloaded")

        val isAccessibilityDetails = containsText(rootNode, "Use TakeBack") ||
                evText.contains("use takeback") ||
                (hasTakeBack && containsText(rootNode, "TakeBack shortcut")) ||
                (hasTakeBack && containsText(rootNode, "Turn off")) ||
                (hasTakeBack && cls.contains("toggleaccessibilityservice")) ||
                (hasTakeBack && cls.contains("accessibilitydetails")) ||
                (hasTakeBack && cls.contains("accessibilityservicewarning"))

        if (isAccessibilityDetails) {
            return SettingsDestination.TAKEBACK_ACCESSIBILITY_DETAILS
        }

        val isGeneralAccessibilityMenu = containsText(rootNode, "Vision enhancements") ||
                containsText(rootNode, "Visibility enhancements") ||
                containsText(rootNode, "Hearing enhancements") ||
                containsText(rootNode, "Interaction and dexterity") ||
                containsText(rootNode, "Accessibility features") ||
                containsText(rootNode, "Accessibility button") ||
                containsText(rootNode, "Accessibility shortcut") ||
                (containsText(rootNode, "Accessibility") && containsText(rootNode, "Vision") && containsText(rootNode, "Hearing")) ||
                evText == "accessibility" ||
                (isAccessibilityClass && !cls.contains("installedservice") && !cls.contains("downloaded"))

        if (isGeneralAccessibilityMenu) {
            return SettingsDestination.ACCESSIBILITY_SETTINGS
        }

        val isInstalledOrDownloadedServicesList = cls.contains("installedservice") ||
                cls.contains("downloaded") ||
                containsText(rootNode, "Installed services") ||
                containsText(rootNode, "Installed apps") ||
                containsText(rootNode, "Downloaded apps") ||
                containsText(rootNode, "Downloaded services") ||
                evText.contains("installed services") ||
                evText.contains("installed apps") ||
                evText.contains("downloaded apps")

        if (isInstalledOrDownloadedServicesList) {
            return SettingsDestination.INSTALLED_ACCESSIBILITY_SERVICES
        }

        if (isAccessibilityClass || containsText(rootNode, "Accessibility")) {
            // Avoid misclassifying Settings Home if "Accessibility" just appears as a category item on Settings Home
            val looksLikeSettingsHome = (containsText(rootNode, "Search settings") ||
                    containsText(rootNode, "Network & internet") ||
                    containsText(rootNode, "Connections")) &&
                    !cls.contains("accessibility")
            if (!looksLikeSettingsHome) {
                return SettingsDestination.ACCESSIBILITY_SETTINGS
            }
        }

        // 6. TakeBack App Info / App Management / Security Permission screens (Samsung & Realme)
        val isAppInfoClass = cls.contains("installedappdetails") ||
                cls.contains("appinfo") ||
                cls.contains("appdetails") ||
                cls.contains("installedappopenbydefault") ||
                pkg == "com.oplus.appdetail" ||
                pkg == "com.coloros.appdetail"

        if (hasTakeBack) {
            val isAppInfoText = containsText(rootNode, "App info") ||
                    containsText(rootNode, "App details") ||
                    containsText(rootNode, "Force stop") ||
                    containsText(rootNode, "Uninstall") ||
                    containsText(rootNode, "Storage") ||
                    containsText(rootNode, "Storage usage") ||
                    containsText(rootNode, "Clear data") ||
                    containsText(rootNode, "Battery usage") ||
                    containsText(rootNode, "Disable") ||
                    evText.contains("app info") ||
                    evText.contains("app details")
            if (isAppInfoClass || isAppInfoText) {
                return SettingsDestination.TAKEBACK_APP_INFO
            }

            val isPermissionSetting = containsText(rootNode, "Usage access") ||
                    containsText(rootNode, "Appear on top") ||
                    containsText(rootNode, "Display over other apps") ||
                    containsText(rootNode, "Special app access") ||
                    containsText(rootNode, "Manage permissions") ||
                    pkg == "com.oplus.securitypermission" ||
                    pkg == "com.coloros.securitypermission"
            if (isPermissionSetting) {
                return SettingsDestination.TAKEBACK_PERMISSION_SETTINGS
            }
        }

        // 7. Security and Privacy settings
        val isSecurityPrivacy = containsText(rootNode, "Security and privacy") ||
                containsText(rootNode, "Security & privacy") ||
                containsText(rootNode, "Biometrics and security") ||
                containsText(rootNode, "Privacy") ||
                cls.contains("securitysettings")
        if (isSecurityPrivacy && !hasTakeBack) {
            return SettingsDestination.SECURITY_AND_PRIVACY_SETTINGS
        }

        // 8. General Settings Home
        val isSettingsHome = containsText(rootNode, "Connections") ||
                containsText(rootNode, "Sound and vibration") ||
                containsText(rootNode, "Notifications") ||
                containsText(rootNode, "Display") ||
                containsText(rootNode, "Search settings")
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
        isMaintenanceUnlocked: Boolean,
        eventText: String? = null
    ): ProtectionDecision {
        val destination = classify(packageName, className, rootNode, eventText)

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

        // Invariant 4: Protect the ENTIRE Accessibility, Device Admin, App Info, and Uninstall flow under Strict Mode
        return when (destination) {
            SettingsDestination.ACCESSIBILITY_SETTINGS,
            SettingsDestination.INSTALLED_ACCESSIBILITY_SERVICES,
            SettingsDestination.TAKEBACK_ACCESSIBILITY_DETAILS -> ProtectionDecision(
                isRestricted = true,
                destination = destination,
                targetLabel = "Accessibility Protection",
                warningMessage = "Accessibility settings are locked while Strict Mode is active to prevent disabling TakeBack's protection service."
            )
            SettingsDestination.DEVICE_ADMIN_SETTINGS,
            SettingsDestination.TAKEBACK_DEVICE_ADMIN_DETAILS -> ProtectionDecision(
                isRestricted = true,
                destination = destination,
                targetLabel = "Device Administrator Protection",
                warningMessage = "Device Administrator settings are locked while Strict Mode is active to prevent deactivating anti-tamper protection."
            )
            SettingsDestination.TAKEBACK_APP_INFO -> ProtectionDecision(
                isRestricted = true,
                destination = destination,
                targetLabel = "App Info Protection",
                warningMessage = "TakeBack's App Info, Force Stop, Storage, and Uninstall settings are locked while Strict Mode is active."
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
            SettingsDestination.SETTINGS_HOME,
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
        rootNode: AccessibilityNodeSnapshot,
        eventText: String = ""
    ): Boolean {
        // Realme UI (ColorOS) shows an "Activate Device admin apps?" verification dialog when opening
        // DeviceAdminSettingsActivity; that settings gate must be blocked, never mistaken for app activation.
        if (className.contains("deviceadminsettings") ||
            containsText(rootNode, "Activate Device admin apps") ||
            containsText(rootNode, "Device admin apps") ||
            containsText(rootNode, "Verification code")
        ) {
            return false
        }
        val isAddClass = className.contains("deviceadminadd")
        val hasActivatePrompt = containsText(rootNode, "Activate this device admin app") ||
                containsText(rootNode, "Activate device admin app") ||
                containsText(rootNode, "Activate phone administrator") ||
                eventText.contains("activate this device admin")
        val hasDeactivate = containsText(rootNode, "Deactivate") ||
                containsText(rootNode, "Turn off") ||
                containsText(rootNode, "Remove") ||
                eventText.contains("deactivate")
        return (isAddClass || hasActivatePrompt) && !hasDeactivate
    }

    private fun containsText(
        node: AccessibilityNodeSnapshot,
        query: String,
        depthRemaining: Int = MAX_SEARCH_DEPTH,
        visitedCounter: IntArray = intArrayOf(0)
    ): Boolean {
        if (depthRemaining <= 0 || visitedCounter[0] >= MAX_VISITED_NODES) return false
        visitedCounter[0]++

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (text.contains(query, ignoreCase = true) || desc.contains(query, ignoreCase = true)) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsText(child, query, depthRemaining - 1, visitedCounter)) {
                return true
            }
        }
        return false
    }
}
