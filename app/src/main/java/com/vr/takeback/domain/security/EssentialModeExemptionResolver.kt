package com.vr.takeback.domain.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Abstraction over Android package metadata queries.
 * Allows 100% deterministic pure JVM unit testing of EssentialModeExemptionResolver
 * without Robolectric or Android framework mocks.
 */
interface PackageInfoProvider {
    fun isSystemApp(packageName: String): Boolean
    fun isUpdatedSystemApp(packageName: String): Boolean
    fun getDefaultDialerPackage(): String?
    fun getDefaultSmsPackage(): String?
    fun getCameraPackages(): Set<String>
    fun getHomeLauncherPackages(): Set<String>
    fun getInputMethodPackages(): Set<String>
    fun getInstalledPackages(): Set<String>
    fun getApplicationCategory(packageName: String): Int?
}

/**
 * Exemption categories for Essential Mode.
 */
enum class ExemptionCategory {
    CORE_COMMUNICATION,
    USER_SELECTED_ADDITIONAL,
    SAMSUNG_ONE_HAND_OPERATION,
    SAMSUNG_GOOD_LOCK,
    ANDROID_SYSTEM_APP,
    NOT_EXEMPT
}

/**
 * Detailed exemption decision returned by EssentialModeExemptionResolver.
 */
data class ExemptionDecision(
    val packageName: String,
    val isExempt: Boolean,
    val category: ExemptionCategory,
    val reason: String
)

/**
 * Diagnostic summary for the Essential Mode diagnostic screen.
 */
data class ExemptionDiagnosticSummary(
    val coreApplications: Map<String, Boolean>,
    val selectedAdditionalPackage: String?,
    val isSelectedAdditionalExempt: Boolean,
    val isOneHandOperationPlusInstalled: Boolean,
    val isOneHandOperationPlusExempt: Boolean,
    val isGoodLockHubInstalled: Boolean,
    val isGoodLockHubExempt: Boolean,
    val detectedGoodLockModules: List<GoodLockModuleDiagnostic>,
    val systemAppExemptionCount: Int,
    val updatedSystemAppExemptionCount: Int,
    val systemAppPackagesList: List<String> = emptyList(),
    val isPackageEnumerationSuccessful: Boolean = true,
    val backgroundExecutionGuaranteed: Boolean = true,
    val unverifiedPackages: List<String> = emptyList()
)

data class GoodLockModuleDiagnostic(
    val moduleName: String,
    val packageName: String,
    val isInstalled: Boolean,
    val isExempt: Boolean
)

/**
 * Centralized Essential Mode Exemption Resolver.
 * Single authoritative component governing application-level exemptions during Essential Mode.
 *
 * Invariants:
 * 1. Background-running applications are NEVER force-stopped or killed by Essential Mode.
 *    Essential Mode is strictly an application-access restriction evaluated on foreground transitions.
 * 2. Samsung One Hand Operation+ (com.samsung.android.sidegesturepad) is ALWAYS exempt.
 * 3. Samsung Good Lock hub and all detected installed Good Lock modules are exempt.
 * 4. All Android system applications (FLAG_SYSTEM and FLAG_UPDATED_SYSTEM_APP) are exempt.
 * 5. Exactly ONE user-selected eligible additional application is exempt.
 * 6. Core communication and utility applications (Dialer, SMS, Camera, WhatsApp) are exempt.
 *    WhatsApp Business (com.whatsapp.w4b) is strictly NOT treated as core WhatsApp.
 * 7. Unrelated Samsung applications are NOT exempt merely because their developer is Samsung.
 */
class EssentialModeExemptionResolver(
    private val packageInfoProvider: PackageInfoProvider
) {

    companion object {
        const val ONE_HAND_OPERATION_PACKAGE = "com.samsung.android.sidegesturepad"
        const val GOOD_LOCK_HUB_PACKAGE = "com.samsung.android.goodlock"
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        const val DEFAULT_ADDITIONAL_PACKAGE = "com.openai.chatgpt"

        // Verified registry of official Samsung Good Lock modules
        val KNOWN_GOOD_LOCK_MODULES = mapOf(
            ONE_HAND_OPERATION_PACKAGE to "One Hand Operation+",
            GOOD_LOCK_HUB_PACKAGE to "Good Lock",
            "com.samsung.android.app.homestar" to "Home Up",
            "com.samsung.android.homestar" to "Home Up",
            "com.samsung.android.qsturning" to "QuickStar",
            "com.samsung.android.quickstar" to "QuickStar",
            "com.samsung.android.lockstar" to "LockStar",
            "com.samsung.android.navstar" to "NavStar",
            "com.samsung.android.app.navstar" to "NavStar",
            "com.samsung.android.multistar" to "MultiStar",
            "com.samsung.android.themedesigner" to "Theme Park",
            "com.samsung.android.keyscafe" to "Keys Cafe",
            "com.samsung.android.app.clockpack.provider" to "ClockFace",
            "com.samsung.android.clockface" to "ClockFace",
            "com.samsung.android.wonderland.designer" to "Wonderland",
            "com.samsung.android.soundassistant" to "Sound Assistant",
            "com.samsung.android.notistar" to "NotiStar",
            "com.samsung.android.app.goodcatch" to "Nice Catch",
            "com.samsung.android.app.captureplugin" to "Nice Shot",
            "com.samsung.android.app.routinesplus" to "Routines+",
            "com.samsung.android.app.edgetouch" to "Edge Touch",
            "com.samsung.android.edgelightingplus" to "Edge Lighting+",
            "com.samsung.android.pentastic" to "Pentastic",
            "com.samsung.android.app.cameraassistant" to "Camera Assistant",
            "com.samsung.android.app.soundcraft" to "RegiStar",
            "com.samsung.android.app.starsetting" to "RegiStar",
            "com.samsung.android.app.galaxyregistar" to "RegiStar",
            "com.samsung.android.app.cocktailbarservice" to "Edge Panels",
            "com.samsung.android.app.displayassistant" to "Display Assistant",
            "com.samsung.android.galaxytoshare" to "Galaxy To Share"
        )

        // Essential Android core system packages that must never be blocked
        val ESSENTIAL_ANDROID_SYSTEM_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.android.systemui",
            "com.android.emergency",
            "com.google.android.apps.safetyhub",
            "com.android.phone",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.server.telecom",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.vr.takeback",
            "android"
        )

        // Ineligible package substrings for additional application selection
        private val INELIGIBLE_PACKAGE_SUBSTRINGS = setOf(
            "instagram", "tiktok", "musically", "zhiliaoapp", "facebook", "katana", "snapchat", "twitter",
            "threads", "reddit", "netflix", "hulu", "disney", "primevideo", "youtube", "spotify",
            "twitch", "discord", "game", "gaming", "pubg", "roblox", "candycrush",
            "clashofclans", "brawlstars", "genshin", "steam", "playstation", "xbox"
        )
    }

    private val customGoodLockModules = ConcurrentHashMap.newKeySet<String>()

    /**
     * Resolves whether an application is exempt from Essential Mode restrictions.
     */
    fun resolveExemption(
        packageName: String,
        selectedAdditionalPackage: String? = null
    ): ExemptionDecision {
        val pkg = packageName.trim()

        // 1. Samsung One Hand Operation+ (Mandatory Exemption)
        if (pkg == ONE_HAND_OPERATION_PACKAGE) {
            return ExemptionDecision(
                packageName = pkg,
                isExempt = true,
                category = ExemptionCategory.SAMSUNG_ONE_HAND_OPERATION,
                reason = "One Hand Operation+ edge gesture system is permanently exempt."
            )
        }

        // 2. Samsung Good Lock hub and verified Good Lock modules
        if (isGoodLockModule(pkg)) {
            val moduleName = KNOWN_GOOD_LOCK_MODULES[pkg] ?: "Good Lock Module"
            return ExemptionDecision(
                packageName = pkg,
                isExempt = true,
                category = ExemptionCategory.SAMSUNG_GOOD_LOCK,
                reason = "$moduleName customization module is exempt."
            )
        }

        // 3. Core communication & utility applications
        if (isCoreEssentialPackage(pkg)) {
            return ExemptionDecision(
                packageName = pkg,
                isExempt = true,
                category = ExemptionCategory.CORE_COMMUNICATION,
                reason = "Core communication/utility application."
            )
        }

        // 4. User-selected additional application (exactly one)
        if (selectedAdditionalPackage != null && pkg == selectedAdditionalPackage) {
            return ExemptionDecision(
                packageName = pkg,
                isExempt = true,
                category = ExemptionCategory.USER_SELECTED_ADDITIONAL,
                reason = "User-selected additional allowed application."
            )
        }

        // 5. Android System & Updated System Applications
        if (isSystemApplication(pkg)) {
            return ExemptionDecision(
                packageName = pkg,
                isExempt = true,
                category = ExemptionCategory.ANDROID_SYSTEM_APP,
                reason = "Android platform system or updated-system application."
            )
        }

        // 6. Not exempt: ordinary user application restricted under Essential Mode
        return ExemptionDecision(
            packageName = pkg,
            isExempt = false,
            category = ExemptionCategory.NOT_EXEMPT,
            reason = "Ordinary non-exempt application restricted by Essential Mode daily allowance."
        )
    }

    fun isExempt(packageName: String, selectedAdditionalPackage: String? = null): Boolean {
        return resolveExemption(packageName, selectedAdditionalPackage).isExempt
    }

    /**
     * Returns true if package is Samsung Good Lock hub or a verified/confirmed Good Lock module.
     * Crucially: Does NOT use a wildcard com.samsung.android.*.
     */
    fun isGoodLockModule(packageName: String): Boolean {
        if (packageName == GOOD_LOCK_HUB_PACKAGE) return true
        if (KNOWN_GOOD_LOCK_MODULES.containsKey(packageName)) return true
        return customGoodLockModules.contains(packageName)
    }

    /**
     * Returns true if package is one of the Core Essential applications:
     * - Default Phone / Dialer
     * - Default Messages / SMS
     * - Camera application
     * - WhatsApp (com.whatsapp)
     */
    fun isCoreEssentialPackage(packageName: String): Boolean {
        // WhatsApp: com.whatsapp only. com.whatsapp.w4b is strictly excluded.
        if (packageName == WHATSAPP_PACKAGE) return true

        // Default Dialer
        val defaultDialer = packageInfoProvider.getDefaultDialerPackage()
        if (defaultDialer != null && packageName == defaultDialer) return true

        // Default SMS
        val defaultSms = packageInfoProvider.getDefaultSmsPackage()
        if (defaultSms != null && packageName == defaultSms) return true

        // Camera
        if (packageInfoProvider.getCameraPackages().contains(packageName)) return true

        // Fallback for standard dialer/SMS packages
        val standardDialers = setOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer", "com.android.phone")
        if (standardDialers.contains(packageName)) return true

        val standardSms = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms")
        if (standardSms.contains(packageName)) return true

        val standardCameras = setOf("com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera")
        if (standardCameras.contains(packageName)) return true

        return false
    }

    /**
     * Returns true if package is an Android system or updated system app,
     * or essential system component (Settings, UI, Launcher, IME, etc.).
     */
    fun isSystemApplication(packageName: String): Boolean {
        if (ESSENTIAL_ANDROID_SYSTEM_PACKAGES.contains(packageName)) return true
        if (packageInfoProvider.getHomeLauncherPackages().contains(packageName)) return true
        if (packageInfoProvider.getInputMethodPackages().contains(packageName)) return true
        if (packageInfoProvider.isSystemApp(packageName)) return true
        if (packageInfoProvider.isUpdatedSystemApp(packageName)) return true
        return false
    }

    /**
     * Determines whether an installed application is eligible to be selected as
     * the single additional application.
     * Prevents selecting games, video streaming, entertainment, or social media.
     * Also verifies that the package is actually installed.
     */
    fun isEligibleAdditionalApp(packageName: String): Boolean {
        val pkg = packageName.lowercase().trim()
        if (pkg.isEmpty()) return false

        val installed = packageInfoProvider.getInstalledPackages()
        if (installed.isNotEmpty() && !installed.contains(packageName.trim())) {
            return false
        }

        // Core and system apps don't need to be selected as additional app
        if (isCoreEssentialPackage(pkg) || isSystemApplication(pkg) || isGoodLockModule(pkg)) {
            return false
        }

        // Check category metadata if available
        val category = packageInfoProvider.getApplicationCategory(pkg)
        if (category != null) {
            // Android ApplicationInfo categories:
            // CATEGORY_GAME = 0
            // CATEGORY_AUDIO = 1
            // CATEGORY_VIDEO = 2
            // CATEGORY_IMAGE = 3
            // CATEGORY_SOCIAL = 4
            if (category == 0 || category == 1 || category == 2 || category == 3 || category == 4) {
                return false
            }
        }

        // Filter out obvious social media, games, streaming, and entertainment
        for (pattern in INELIGIBLE_PACKAGE_SUBSTRINGS) {
            if (pkg.contains(pattern)) {
                return false
            }
        }

        return true
    }

    fun canSelectAsAdditionalApp(packageName: String): Boolean = isEligibleAdditionalApp(packageName)

    /**
     * Verifies whether a candidate package name is a legitimate Samsung Good Lock module
     * before allowing it to be registered in the custom allowlist.
     * Prevents arbitrary distracting apps (e.g. Instagram, TikTok, Games) from being registered as Good Lock.
     */
    fun isValidGoodLockCandidate(packageName: String): Boolean {
        val pkg = packageName.lowercase().trim()
        if (pkg.isEmpty()) return false

        // 1. Must be installed on the device (if package enumeration is available)
        val installed = packageInfoProvider.getInstalledPackages()
        if (installed.isNotEmpty() && !installed.contains(packageName.trim())) {
            return false
        }

        // 2. Reject core essential apps or general system apps
        if (isCoreEssentialPackage(pkg) || isSystemApplication(pkg)) {
            return false
        }

        // 3. Reject games, streaming, and social media
        val category = packageInfoProvider.getApplicationCategory(pkg)
        if (category != null && (category == 0 || category == 1 || category == 2 || category == 3 || category == 4)) {
            return false
        }

        for (pattern in INELIGIBLE_PACKAGE_SUBSTRINGS) {
            if (pkg.contains(pattern)) {
                return false
            }
        }

        // 4. Must be a Samsung official package namespace or recognized Good Lock module
        val isSamsungNamespace = pkg.startsWith("com.samsung.") || pkg.startsWith("com.sec.")
        val isKnownModule = KNOWN_GOOD_LOCK_MODULES.containsKey(packageName.trim())

        return isSamsungNamespace || isKnownModule
    }

    /**
     * Returns the full set of currently effective exempt packages for snapshot evaluation.
     */
    fun getEffectiveExemptPackages(selectedAdditionalPackage: String?): Set<String> {
        val exempt = HashSet<String>()

        // 1. One Hand Operation+
        exempt.add(ONE_HAND_OPERATION_PACKAGE)

        // 2. Good Lock hub and all known/installed modules
        exempt.add(GOOD_LOCK_HUB_PACKAGE)
        val installed = packageInfoProvider.getInstalledPackages()
        for (modulePkg in KNOWN_GOOD_LOCK_MODULES.keys) {
            if (installed.isEmpty() || installed.contains(modulePkg)) {
                exempt.add(modulePkg)
            }
        }
        exempt.addAll(customGoodLockModules)

        // 3. Core Essential
        exempt.add(WHATSAPP_PACKAGE)
        packageInfoProvider.getDefaultDialerPackage()?.let { exempt.add(it) }
        packageInfoProvider.getDefaultSmsPackage()?.let { exempt.add(it) }
        exempt.addAll(packageInfoProvider.getCameraPackages())
        exempt.addAll(setOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer", "com.android.phone"))
        exempt.addAll(setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"))
        exempt.addAll(setOf("com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera"))

        // 4. User-selected additional
        if (selectedAdditionalPackage != null) {
            exempt.add(selectedAdditionalPackage)
        }

        // 5. System apps and components
        exempt.addAll(ESSENTIAL_ANDROID_SYSTEM_PACKAGES)
        exempt.addAll(packageInfoProvider.getHomeLauncherPackages())
        exempt.addAll(packageInfoProvider.getInputMethodPackages())

        for (pkg in installed) {
            if (packageInfoProvider.isSystemApp(pkg) || packageInfoProvider.isUpdatedSystemApp(pkg)) {
                exempt.add(pkg)
            }
        }

        return exempt
    }

    /**
     * Generates a transparent diagnostic summary for the diagnostic UI.
     */
    fun getDiagnosticSummary(selectedAdditionalPackage: String? = null): ExemptionDiagnosticSummary {
        val installed = packageInfoProvider.getInstalledPackages()

        val coreStatus = mapOf(
            "Phone / Dialer (${packageInfoProvider.getDefaultDialerPackage() ?: "Default"})" to true,
            "Messages / SMS (${packageInfoProvider.getDefaultSmsPackage() ?: "Default"})" to true,
            "Camera (${packageInfoProvider.getCameraPackages().firstOrNull() ?: "Default"})" to true,
            "WhatsApp ($WHATSAPP_PACKAGE)" to installed.contains(WHATSAPP_PACKAGE)
        )

        val isAdditionalExempt = selectedAdditionalPackage != null &&
                resolveExemption(selectedAdditionalPackage, selectedAdditionalPackage).isExempt

        val ohoInstalled = installed.contains(ONE_HAND_OPERATION_PACKAGE)
        val goodLockInstalled = installed.contains(GOOD_LOCK_HUB_PACKAGE)

        val moduleDiagnostics = mutableListOf<GoodLockModuleDiagnostic>()
        for ((pkg, name) in KNOWN_GOOD_LOCK_MODULES) {
            if (pkg == ONE_HAND_OPERATION_PACKAGE || pkg == GOOD_LOCK_HUB_PACKAGE) continue
            val isModuleInstalled = installed.contains(pkg)
            moduleDiagnostics.add(
                GoodLockModuleDiagnostic(
                    moduleName = name,
                    packageName = pkg,
                    isInstalled = isModuleInstalled,
                    isExempt = true
                )
            )
        }

        for (customPkg in customGoodLockModules) {
            moduleDiagnostics.add(
                GoodLockModuleDiagnostic(
                    moduleName = "Custom Module ($customPkg)",
                    packageName = customPkg,
                    isInstalled = installed.contains(customPkg),
                    isExempt = true
                )
            )
        }

        val systemPackagesList = mutableListOf<String>()
        var systemAppCount = 0
        var updatedSystemAppCount = 0
        for (pkg in installed) {
            if (packageInfoProvider.isUpdatedSystemApp(pkg)) {
                updatedSystemAppCount++
                systemPackagesList.add(pkg)
            } else if (packageInfoProvider.isSystemApp(pkg)) {
                systemAppCount++
                systemPackagesList.add(pkg)
            }
        }
        systemPackagesList.sort()

        return ExemptionDiagnosticSummary(
            coreApplications = coreStatus,
            selectedAdditionalPackage = selectedAdditionalPackage,
            isSelectedAdditionalExempt = isAdditionalExempt,
            isOneHandOperationPlusInstalled = ohoInstalled,
            isOneHandOperationPlusExempt = true,
            isGoodLockHubInstalled = goodLockInstalled,
            isGoodLockHubExempt = true,
            detectedGoodLockModules = moduleDiagnostics,
            systemAppExemptionCount = systemAppCount,
            updatedSystemAppExemptionCount = updatedSystemAppCount,
            systemAppPackagesList = systemPackagesList,
            isPackageEnumerationSuccessful = installed.isNotEmpty(),
            backgroundExecutionGuaranteed = true,
            unverifiedPackages = emptyList()
        )
    }

    fun addConfirmedGoodLockModule(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isNotEmpty()) {
            customGoodLockModules.add(pkg)
        }
    }

    fun removeConfirmedGoodLockModule(packageName: String) {
        customGoodLockModules.remove(packageName.trim())
    }

    fun setCustomGoodLockModules(modules: Set<String>) {
        customGoodLockModules.clear()
        customGoodLockModules.addAll(modules)
    }

    fun synchronizeCustomGoodLockModules(modules: Set<String>) {
        setCustomGoodLockModules(modules)
    }

    fun getCustomGoodLockModules(): Set<String> {
        return customGoodLockModules.toSet()
    }
}
