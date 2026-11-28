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
    CORE_PAYMENT,
    CORE_AI_ASSISTANT,
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

data class AppEligibility(
    val isEligible: Boolean,
    val reason: String
)

/**
 * Diagnostic summary for the Essential Mode diagnostic screen.
 */
data class ExemptionDiagnosticSummary(
    val coreApplications: Map<String, Boolean>,
    val aiAssistants: Map<String, Boolean> = emptyMap(),
    val selectedAdditionalPackages: List<String> = emptyList(),
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
    val enforcementModelDescription: String = "Foreground Access Gate (Zero process killing or background service disruption)",
    val unverifiedPackages: List<String> = emptyList()
)

data class GoodLockModuleDiagnostic(
    val moduleName: String,
    val packageName: String,
    val isInstalled: Boolean,
    val isExempt: Boolean,
    val isUserConfirmedExemption: Boolean = false
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
 * 5. Up to two (2) user-selected eligible additional applications are exempt.
 * 6. Core communication, utility, payment, and AI chatbot applications (Dialer, SMS, Camera, WhatsApp,
 *    Samsung Wallet, PhonePe, ChatGPT, Gemini, Claude, etc.) are exempt by default.
 *    WhatsApp Business (com.whatsapp.w4b) is strictly NOT treated as core WhatsApp.
 * 7. Unrelated Samsung applications are NOT exempt merely because their developer is Samsung.
 */
class EssentialModeExemptionResolver(
    private val packageInfoProvider: PackageInfoProvider
) {

    companion object {
        const val MAX_ADDITIONAL_PACKAGES = 2
        const val ONE_HAND_OPERATION_PACKAGE = "com.samsung.android.sidegesturepad"
        const val GOOD_LOCK_HUB_PACKAGE = "com.samsung.android.goodlock"
        const val WHATSAPP_PACKAGE = "com.whatsapp"

        // AI Chatbot & Assistant packages exempt by default during Essential Mode
        val ESSENTIAL_AI_CHATBOT_PACKAGES = mapOf(
            "com.openai.chatgpt" to "ChatGPT",
            "com.google.android.apps.bard" to "Google Gemini",
            "com.anthropic.claude" to "Claude",
            "ai.perplexity.app.android" to "Perplexity",
            "com.microsoft.copilot" to "Microsoft Copilot",
            "com.deepseek.chat" to "DeepSeek",
            "ai.x.grok" to "Grok",
            "com.poe.android" to "Poe",
            "ai.mistral.chat" to "Le Chat (Mistral)"
        )

        fun isDefaultAiChatbot(packageName: String): Boolean {
            return ESSENTIAL_AI_CHATBOT_PACKAGES.containsKey(packageName.trim().lowercase())
        }

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

        // Essential payment and financial utility packages that must never be blocked in Essential Mode
        val ESSENTIAL_PAYMENT_PACKAGES = setOf(
            "com.samsung.android.spay",              // Samsung Wallet / Samsung Pay
            "com.samsung.android.spayfw",            // Samsung Pay Framework / Wallet Core
            "com.samsung.android.rapsp",             // Samsung Pay Mini
            "com.phonepe.app",                       // PhonePe
            "com.google.android.apps.nbu.paisa.user", // Google Pay (GPay India / Tez)
            "com.google.android.apps.walletnfcrel",   // Google Wallet
            "net.one97.paytm",                       // Paytm
            "in.org.npci.upiapp"                     // BHIM UPI
        )

        // Disqualified package substrings for automatic system application exemption.
        // Prevents OEM preinstalled bloatware (Gallery, Videos, File Managers, Browsers)
        // with FLAG_SYSTEM from evading Essential Mode restrictions.
        val DISQUALIFIED_SYSTEM_PACKAGE_SUBSTRINGS = setOf(
            "gallery", "photos", "video", "videoplayer", "mediaplayer",
            "myfiles", "filemanager", "documentsui", "sbrowser", "browser", "chrome"
        )

        // Ineligible package substrings for additional application selection
        private val INELIGIBLE_PACKAGE_SUBSTRINGS = setOf(
            "instagram", "tiktok", "musically", "zhiliaoapp", "facebook", "katana", "snapchat", "twitter",
            "threads", "reddit", "netflix", "hulu", "disney", "primevideo", "youtube", "spotify",
            "twitch", "discord", "game", "gaming", "pubg", "roblox", "candycrush",
            "clashofclans", "brawlstars", "genshin", "steam", "playstation", "xbox", "pinterest"
        )

        val KNOWN_VERIFIED_PRODUCTIVITY_PACKAGES = setOf(
            "com.openai.chatgpt",
            "notion.id",
            "md.obsidian",
            "com.todoist",
            "com.google.android.keep",
            "com.microsoft.office.word",
            "com.microsoft.office.excel",
            "com.microsoft.office.powerpoint",
            "com.google.android.apps.docs",
            "com.google.android.apps.sheets",
            "com.google.android.apps.slides",
            "com.evernote",
            "com.ticktick.task",
            "com.anydo",
            "com.slack",
            "com.app.notes"
        )
    }

    private val customGoodLockModules = ConcurrentHashMap.newKeySet<String>()

    /**
     * Resolves whether an application is exempt from Essential Mode restrictions.
     */
    fun resolveExemption(
        packageName: String,
        selectedAdditionalPackages: List<String> = emptyList()
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

        // 3b. Essential payment & financial utility applications
        if (isPaymentPackage(pkg)) {
            return ExemptionDecision(
                packageName = pkg,
                isExempt = true,
                category = ExemptionCategory.CORE_PAYMENT,
                reason = "Essential payment/financial utility application is permanently exempt."
            )
        }

        // 3c. Essential AI chatbot & assistant applications (ChatGPT, Gemini, Claude, etc.)
        if (isAiChatbotPackage(pkg)) {
            val botName = ESSENTIAL_AI_CHATBOT_PACKAGES[pkg.lowercase()] ?: "AI Assistant"
            return ExemptionDecision(
                packageName = pkg,
                isExempt = true,
                category = ExemptionCategory.CORE_AI_ASSISTANT,
                reason = "$botName AI assistant is exempt by default."
            )
        }

        // 4. User-selected additional applications (up to MAX_ADDITIONAL_PACKAGES = 2)
        val effectiveSelected = selectedAdditionalPackages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_ADDITIONAL_PACKAGES)
            .toSet()
        if (effectiveSelected.contains(pkg)) {
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

    fun resolveExemption(
        packageName: String,
        selectedAdditionalPackage: String?
    ): ExemptionDecision {
        return resolveExemption(
            packageName = packageName,
            selectedAdditionalPackages = listOfNotNull(selectedAdditionalPackage)
        )
    }

    fun isExempt(packageName: String, selectedAdditionalPackages: List<String> = emptyList()): Boolean {
        return resolveExemption(packageName, selectedAdditionalPackages).isExempt
    }

    fun isExempt(packageName: String, selectedAdditionalPackage: String?): Boolean {
        return resolveExemption(packageName, listOfNotNull(selectedAdditionalPackage)).isExempt
    }

    /**
     * Returns true if package is one of the default exempt AI chatbot / assistant applications.
     */
    fun isAiChatbotPackage(packageName: String): Boolean {
        return isDefaultAiChatbot(packageName)
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
     * Returns true if package is an essential payment/financial utility application
     * (Samsung Wallet / Pay, PhonePe, Google Pay, UPI, Paytm).
     */
    fun isPaymentPackage(packageName: String): Boolean {
        val trimmed = packageName.trim()
        if (ESSENTIAL_PAYMENT_PACKAGES.contains(trimmed)) return true
        val lower = trimmed.lowercase()
        return lower == "com.phonepe.app" || lower == "com.samsung.android.spay" || lower == "com.samsung.android.spayfw" || lower == "com.samsung.android.rapsp"
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
     * Crucially: Disqualifies OEM bloatware, galleries, video players, file managers,
     * and browsers that may have FLAG_SYSTEM set on OEM ROMs (e.g. Samsung OneUI).
     */
    fun isSystemApplication(packageName: String): Boolean {
        return isSystemApplicationInternal(
            packageName = packageName,
            launchers = packageInfoProvider.getHomeLauncherPackages(),
            imes = packageInfoProvider.getInputMethodPackages()
        )
    }

    private fun isSystemApplicationInternal(
        packageName: String,
        launchers: Set<String>,
        imes: Set<String>
    ): Boolean {
        val trimmed = packageName.trim()
        if (ESSENTIAL_ANDROID_SYSTEM_PACKAGES.contains(trimmed)) return true
        if (launchers.contains(trimmed)) return true
        if (imes.contains(trimmed)) return true

        val isSys = packageInfoProvider.isSystemApp(trimmed) || packageInfoProvider.isUpdatedSystemApp(trimmed)
        if (!isSys) return false

        val lowerPkg = trimmed.lowercase()
        for (pattern in DISQUALIFIED_SYSTEM_PACKAGE_SUBSTRINGS) {
            if (lowerPkg.contains(pattern)) {
                return false
            }
        }
        if (lowerPkg.endsWith(".files") || lowerPkg.endsWith(".gallery")) {
            return false
        }

        val category = packageInfoProvider.getApplicationCategory(trimmed)
        if (category != null && category in 0..4) {
            return false
        }

        return true
    }

    /**
     * Evaluates whether an installed application is eligible to be selected as
     * the single additional application, returning detailed eligibility and reasons.
     * Prevents selecting games, video streaming, entertainment, social media, or unverified categories.
     * Also verifies that the package is actually installed.
     */
    fun evaluateAdditionalAppEligibility(packageName: String): AppEligibility {
        val pkg = packageName.lowercase().trim()
        if (pkg.isEmpty()) {
            return AppEligibility(false, "Package name cannot be blank")
        }

        val installed = packageInfoProvider.getInstalledPackages()
        if (installed.isNotEmpty() && !installed.contains(packageName.trim())) {
            return AppEligibility(false, "Application is not installed on this device")
        }

        // Core, AI assistant, Good Lock, and system apps don't need to be selected as additional app
        if (isCoreEssentialPackage(pkg)) {
            return AppEligibility(false, "Already exempt as a core essential application")
        }
        if (isPaymentPackage(pkg)) {
            return AppEligibility(false, "Already exempt as a core payment application")
        }
        if (isAiChatbotPackage(pkg)) {
            return AppEligibility(false, "Already exempt by default as an AI assistant application")
        }
        if (isSystemApplication(pkg)) {
            return AppEligibility(false, "Already exempt as an Android system application")
        }
        if (isGoodLockModule(pkg)) {
            return AppEligibility(false, "Already exempt as a Samsung Good Lock module")
        }

        // Check category metadata if available
        val category = packageInfoProvider.getApplicationCategory(pkg)
        when (category) {
            0 -> return AppEligibility(false, "Prohibited category: Games")
            1 -> return AppEligibility(false, "Prohibited category: Audio entertainment / music")
            2 -> return AppEligibility(false, "Prohibited category: Video entertainment / streaming")
            3 -> return AppEligibility(false, "Prohibited category: Image / photo entertainment")
            4 -> return AppEligibility(false, "Prohibited category: Social networking")
        }

        // Filter out obvious social media, games, streaming, and entertainment
        for (pattern in INELIGIBLE_PACKAGE_SUBSTRINGS) {
            if (pkg.contains(pattern)) {
                return AppEligibility(false, "Prohibited entertainment, gaming, or social package ($pattern)")
            }
        }

        // Build 11 Conservative category policy:
        // Must either have Android CATEGORY_PRODUCTIVITY (7) or belong to verified productivity registry.
        if (category == 7 || KNOWN_VERIFIED_PRODUCTIVITY_PACKAGES.contains(pkg)) {
            return AppEligibility(true, "Verified productivity application")
        }

        if (category == null || category == -1) {
            return AppEligibility(false, "Unknown application category (unclassified): Application category is undefined. Only verified productivity applications are eligible.")
        }

        return AppEligibility(false, "Non-productivity category: Category ($category) is not approved for Essential Mode.")
    }

    fun isEligibleAdditionalApp(packageName: String): Boolean {
        return evaluateAdditionalAppEligibility(packageName).isEligible
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

        // 2. Reject core essential apps, AI chatbots, or general system apps
        if (isCoreEssentialPackage(pkg) || isAiChatbotPackage(pkg) || isSystemApplication(pkg)) {
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

        // Reject retail, marketing, or general e-commerce Samsung apps
        if (pkg.contains("ecomm") || pkg.contains("shop") || pkg.contains("store") || pkg.contains("pay") || pkg.contains("billing") ||
            pkg.contains("samsungapps") || pkg.contains("galaxyappstore") || pkg.contains("themestore") || pkg.contains("samsungshop")) {
            return false
        }

        // 4. Must be a Samsung official package namespace or recognized Good Lock module
        val isSamsungNamespace = pkg.startsWith("com.samsung.") || pkg.startsWith("com.sec.")
        val isKnownModule = KNOWN_GOOD_LOCK_MODULES.containsKey(packageName.trim())

        return isSamsungNamespace || isKnownModule
    }

    /**
     * Returns the full set of currently effective exempt packages for snapshot evaluation.
     */
    fun getEffectiveExemptPackages(selectedAdditionalPackages: List<String> = emptyList()): Set<String> {
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

        // 3b. Essential Payment packages
        exempt.addAll(ESSENTIAL_PAYMENT_PACKAGES)

        // 3c. Essential AI chatbot & assistant packages
        exempt.addAll(ESSENTIAL_AI_CHATBOT_PACKAGES.keys)

        // 4. User-selected additional (up to MAX_ADDITIONAL_PACKAGES)
        selectedAdditionalPackages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_ADDITIONAL_PACKAGES)
            .forEach { exempt.add(it) }

        // 5. System apps and components (hoist launcher & IME sets once to avoid O(N) IPC repeats)
        val launchers = packageInfoProvider.getHomeLauncherPackages()
        val imes = packageInfoProvider.getInputMethodPackages()
        exempt.addAll(ESSENTIAL_ANDROID_SYSTEM_PACKAGES)
        exempt.addAll(launchers)
        exempt.addAll(imes)

        for (pkg in installed) {
            if (isSystemApplicationInternal(pkg, launchers, imes)) {
                exempt.add(pkg)
            }
        }

        return exempt
    }

    fun getEffectiveExemptPackages(selectedAdditionalPackage: String?): Set<String> {
        return getEffectiveExemptPackages(listOfNotNull(selectedAdditionalPackage))
    }

    /**
     * Generates a transparent diagnostic summary for the diagnostic UI.
     */
    fun getDiagnosticSummary(selectedAdditionalPackages: List<String> = emptyList()): ExemptionDiagnosticSummary {
        val installed = packageInfoProvider.getInstalledPackages()

        val coreStatus = mapOf(
            "Phone / Dialer (${packageInfoProvider.getDefaultDialerPackage() ?: "Default"})" to true,
            "Messages / SMS (${packageInfoProvider.getDefaultSmsPackage() ?: "Default"})" to true,
            "Camera (${packageInfoProvider.getCameraPackages().firstOrNull() ?: "Default"})" to true,
            "WhatsApp ($WHATSAPP_PACKAGE)" to installed.contains(WHATSAPP_PACKAGE)
        )

        val aiStatus = ESSENTIAL_AI_CHATBOT_PACKAGES.entries.associate { (pkg, label) ->
            "$label ($pkg)" to installed.contains(pkg)
        }

        val normalizedSelected = selectedAdditionalPackages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_ADDITIONAL_PACKAGES)
            .toList()

        val isAdditionalExempt = normalizedSelected.isNotEmpty() &&
                normalizedSelected.all { resolveExemption(it, normalizedSelected).isExempt }

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
                    moduleName = "User-Confirmed Samsung Customization ($customPkg)",
                    packageName = customPkg,
                    isInstalled = installed.contains(customPkg),
                    isExempt = true,
                    isUserConfirmedExemption = true
                )
            )
        }

        val systemPackagesList = mutableListOf<String>()
        var systemAppCount = 0
        var updatedSystemAppCount = 0
        for (pkg in installed) {
            if (isSystemApplication(pkg)) {
                if (packageInfoProvider.isUpdatedSystemApp(pkg)) {
                    updatedSystemAppCount++
                    systemPackagesList.add(pkg)
                } else if (packageInfoProvider.isSystemApp(pkg)) {
                    systemAppCount++
                    systemPackagesList.add(pkg)
                }
            }
        }
        systemPackagesList.sort()

        return ExemptionDiagnosticSummary(
            coreApplications = coreStatus,
            aiAssistants = aiStatus,
            selectedAdditionalPackages = normalizedSelected,
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

    fun getDiagnosticSummary(selectedAdditionalPackage: String?): ExemptionDiagnosticSummary {
        return getDiagnosticSummary(listOfNotNull(selectedAdditionalPackage))
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
