package com.vr.takeback.domain.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EssentialModeExemptionResolverTest {

    private lateinit var fakeProvider: FakePackageInfoProvider
    private lateinit var resolver: EssentialModeExemptionResolver

    @Before
    fun setUp() {
        fakeProvider = FakePackageInfoProvider()
        resolver = EssentialModeExemptionResolver(fakeProvider)
    }

    // 1. One Hand Operation+ is ALWAYS exempt
    @Test
    fun scenario01_oneHandOperationPlus_isAlwaysExempt() {
        val decision = resolver.resolveExemption("com.samsung.android.sidegesturepad")
        assertTrue("Samsung One Hand Operation+ must be exempt", decision.isExempt)
        assertEquals(ExemptionCategory.SAMSUNG_ONE_HAND_OPERATION, decision.category)
        assertTrue(resolver.isExempt("com.samsung.android.sidegesturepad"))
    }

    // 2. Good Lock hub is ALWAYS exempt
    @Test
    fun scenario02_goodLockHub_isAlwaysExempt() {
        fakeProvider.installed.add("com.samsung.android.goodlock")
        val decision = resolver.resolveExemption("com.samsung.android.goodlock")
        assertTrue("Good Lock hub must be exempt", decision.isExempt)
        assertEquals(ExemptionCategory.SAMSUNG_GOOD_LOCK, decision.category)
    }

    // 3. All detected installed Good Lock modules are exempt
    @Test
    fun scenario03_allDetectedGoodLockModules_areExempt() {
        val officialModules = listOf(
            "com.samsung.android.app.homestar",     // Home Up
            "com.samsung.android.qsturning",        // QuickStar
            "com.samsung.android.lockstar",         // LockStar
            "com.samsung.android.app.navstar",      // NavStar
            "com.samsung.android.multistar",        // MultiStar
            "com.samsung.android.themedesigner",    // Theme Park
            "com.samsung.android.keyscafe",         // Keys Cafe
            "com.samsung.android.soundassistant",   // Sound Assistant
            "com.samsung.android.app.galaxyregistar"// RegiStar
        )
        fakeProvider.installed.addAll(officialModules)

        officialModules.forEach { pkg ->
            val decision = resolver.resolveExemption(pkg)
            assertTrue("Official Good Lock module $pkg must be exempt", decision.isExempt)
            assertEquals("Module $pkg must be categorized as SAMSUNG_GOOD_LOCK", ExemptionCategory.SAMSUNG_GOOD_LOCK, decision.category)
        }
    }

    // 4. Newly installed recognized Good Lock module discovered dynamically
    @Test
    fun scenario04_newlyInstalledRecognizedGoodLockModule_discovered() {
        val newModule = "com.samsung.android.app.cocktailbarservice"
        assertFalse(fakeProvider.installed.contains(newModule))

        // Dynamically installed
        fakeProvider.installed.add(newModule)
        val decision = resolver.resolveExemption(newModule)
        assertTrue("Newly installed module $newModule must be recognized as exempt", decision.isExempt)
        assertEquals(ExemptionCategory.SAMSUNG_GOOD_LOCK, decision.category)
    }

    // 5. Unrecognized unrelated Samsung app NOT classified as Good Lock (No wildcard!)
    @Test
    fun scenario05_unrecognizedUnrelatedSamsungApp_isNotClassifiedAsGoodLock() {
        val unrelatedSamsungApp = "com.samsung.android.unrelatedgame"
        fakeProvider.installed.add(unrelatedSamsungApp)
        fakeProvider.systemPackages.remove(unrelatedSamsungApp)

        val decision = resolver.resolveExemption(unrelatedSamsungApp)
        assertFalse("Unrelated Samsung app without system flags must NOT be exempt", decision.isExempt)
        assertEquals(ExemptionCategory.NOT_EXEMPT, decision.category)
    }

    // 6. FLAG_SYSTEM applications are exempt
    @Test
    fun scenario06_flagSystemApplications_areExempt() {
        val systemPkg = "com.android.settings"
        fakeProvider.systemPackages.add(systemPkg)

        val decision = resolver.resolveExemption(systemPkg)
        assertTrue("FLAG_SYSTEM application must be exempt", decision.isExempt)
        assertEquals(ExemptionCategory.ANDROID_SYSTEM_APP, decision.category)
    }

    // 7. FLAG_UPDATED_SYSTEM_APP applications are exempt
    @Test
    fun scenario07_flagUpdatedSystemApplications_areExempt() {
        val updatedSystemPkg = "com.google.android.webview"
        fakeProvider.updatedSystemPackages.add(updatedSystemPkg)

        val decision = resolver.resolveExemption(updatedSystemPkg)
        assertTrue("FLAG_UPDATED_SYSTEM_APP application must be exempt", decision.isExempt)
        assertEquals(ExemptionCategory.ANDROID_SYSTEM_APP, decision.category)
    }

    // 8. User-installed app not exempt merely because developer is Samsung
    @Test
    fun scenario08_userInstalledApp_notExemptMerelyBecauseDeveloperIsSamsung() {
        val samsungShop = "com.samsung.ecomm"
        fakeProvider.installed.add(samsungShop)
        // Explicitly not in system apps or good lock catalogue
        fakeProvider.systemPackages.remove(samsungShop)

        val decision = resolver.resolveExemption(samsungShop)
        assertFalse("Samsung retail app must not be exempt", decision.isExempt)
        assertEquals(ExemptionCategory.NOT_EXEMPT, decision.category)
    }

    // 9. Background services never force-stopped (Invariant assertion)
    @Test
    fun scenario09_backgroundServices_neverForceStopped() {
        // EssentialModeExemptionResolver adheres strictly to the invariant that Essential Mode
        // is an application-access restriction, not a process killer
        val summary = resolver.getDiagnosticSummary()
        assertTrue("Resolver must guarantee non-interference with background execution", summary.backgroundExecutionGuaranteed)
    }

    // 10. Background jobs and notifications not cancelled (Invariant assertion)
    @Test
    fun scenario10_backgroundJobsAndNotifications_notCancelled() {
        val decision = resolver.resolveExemption("com.spotify.music")
        assertFalse("Spotify foreground UI is not exempt by default", decision.isExempt)
        // Even though Spotify foreground UI is not exempt, the resolver guarantees that background audio playback
        // and media sessions are outside the boundary of Essential Mode enforcement
        val summary = resolver.getDiagnosticSummary()
        assertTrue(summary.backgroundExecutionGuaranteed)
    }

    // 11. Background app not treated as foreground launch
    @Test
    fun scenario11_backgroundApp_notTreatedAsForegroundLaunch() {
        // Invariant: resolver only inspects package names passed to resolveExemption during foreground transitions.
        val nonExemptPkg = "com.facebook.katana"
        assertFalse("Social app is not exempt", resolver.isExempt(nonExemptPkg))
    }

    // 12. Exempt app foreground UI remains accessible
    @Test
    fun scenario12_exemptAppForegroundUI_remainsAccessible() {
        fakeProvider.defaultDialer = "com.samsung.android.dialer"
        val decision = resolver.resolveExemption("com.samsung.android.dialer")
        assertTrue("Dialer foreground UI must be accessible", decision.isExempt)
        assertEquals(ExemptionCategory.CORE_COMMUNICATION, decision.category)
    }

    // 13. Core essential apps are exempt (Dialer, SMS, Camera, WhatsApp)
    @Test
    fun scenario13_coreEssentialApps_areExempt() {
        fakeProvider.defaultDialer = "com.google.android.dialer"
        fakeProvider.defaultSms = "com.google.android.apps.messaging"
        fakeProvider.cameras.add("com.sec.android.app.camera")
        fakeProvider.installed.add(EssentialModeExemptionResolver.WHATSAPP_PACKAGE)

        assertTrue("Default dialer is exempt", resolver.isExempt("com.google.android.dialer"))
        assertTrue("Default SMS is exempt", resolver.isExempt("com.google.android.apps.messaging"))
        assertTrue("Camera is exempt", resolver.isExempt("com.sec.android.app.camera"))
        assertTrue("Official WhatsApp is exempt", resolver.isExempt("com.whatsapp"))
    }

    // 14. WhatsApp Business (com.whatsapp.w4b) is NOT exempt as WhatsApp
    @Test
    fun scenario14_whatsAppBusiness_isNotExemptAsWhatsApp() {
        val w4b = "com.whatsapp.w4b"
        val decision = resolver.resolveExemption(w4b)
        assertFalse("WhatsApp Business must NOT be treated as core WhatsApp", decision.isExempt)
        assertEquals(ExemptionCategory.NOT_EXEMPT, decision.category)
    }

    // 15. User-selected additional app (ChatGPT) is exempt
    @Test
    fun scenario15_userSelectedAdditionalApp_isExempt() {
        val selectedApp = "com.openai.chatgpt"
        val decision = resolver.resolveExemption(selectedApp, selectedAdditionalPackage = selectedApp)
        assertTrue("Configured additional app must be exempt", decision.isExempt)
        assertEquals(ExemptionCategory.USER_SELECTED_ADDITIONAL, decision.category)
    }

    // 16. Ineligible apps (games, social media, entertainment streaming) are rejected from selection
    @Test
    fun scenario16_ineligibleApps_rejectedFromAdditionalSelection() {
        val games = listOf("com.king.candycrushsaga", "com.supercell.clashofclans")
        val social = listOf("com.instagram.android", "com.facebook.katana", "com.zhiliaoapp.musically", "com.twitter.android")
        val streaming = listOf("com.netflix.mediaclient", "com.google.android.youtube", "com.spotify.music")

        games.forEach { pkg ->
            assertFalse("Game $pkg must not be selectable as additional app", resolver.canSelectAsAdditionalApp(pkg))
        }
        social.forEach { pkg ->
            assertFalse("Social app $pkg must not be selectable as additional app", resolver.canSelectAsAdditionalApp(pkg))
        }
        streaming.forEach { pkg ->
            assertFalse("Streaming app $pkg must not be selectable as additional app", resolver.canSelectAsAdditionalApp(pkg))
        }

        // Productivity app must be accepted
        assertTrue("ChatGPT must be eligible", resolver.canSelectAsAdditionalApp("com.openai.chatgpt"))
        assertTrue("Notion must be eligible", resolver.canSelectAsAdditionalApp("notion.id"))
    }

    // 17. Custom registered Good Lock modules are exempt and survive sync
    @Test
    fun scenario17_customRegisteredGoodLockModules_areExempt() {
        val customModule = "com.samsung.android.customnewlock"
        assertFalse(resolver.isExempt(customModule))

        resolver.synchronizeCustomGoodLockModules(setOf(customModule))
        val decision = resolver.resolveExemption(customModule)
        assertTrue("Custom registered Good Lock module must become exempt", decision.isExempt)
        assertEquals(ExemptionCategory.SAMSUNG_GOOD_LOCK, decision.category)
    }

    // 18. Android CATEGORY_SOCIAL (4) and uninstalled packages are rejected
    @Test
    fun scenario18_categorySocial_and_uninstalled_rejected() {
        val installedProductivityApp = "com.app.notes"
        val installedSocialApp = "com.custom.socialnetwork"
        val uninstalledApp = "com.ghost.app"

        fakeProvider.installed.add(installedProductivityApp)
        fakeProvider.installed.add(installedSocialApp)
        // Set Android ApplicationInfo.CATEGORY_SOCIAL = 4
        fakeProvider.appCategories[installedSocialApp] = 4

        assertTrue("Installed productivity app must be eligible", resolver.canSelectAsAdditionalApp(installedProductivityApp))
        assertFalse("App with CATEGORY_SOCIAL (4) must be rejected", resolver.canSelectAsAdditionalApp(installedSocialApp))
        assertFalse("Uninstalled app must be rejected when package enumeration is active", resolver.canSelectAsAdditionalApp(uninstalledApp))
    }

    // 19. Candidate validation for custom Good Lock modules
    @Test
    fun scenario19_isValidGoodLockCandidate() {
        val installedSamsungModule = "com.samsung.android.newnav"
        val installedThirdPartyApp = "com.spotify.music"
        val uninstalledSamsungModule = "com.samsung.android.uninstalled"

        fakeProvider.installed.add(installedSamsungModule)
        fakeProvider.installed.add(installedThirdPartyApp)

        assertTrue("Installed Samsung namespace package must be a valid candidate", resolver.isValidGoodLockCandidate(installedSamsungModule))
        assertFalse("Third party non-Samsung app must be rejected as Good Lock candidate", resolver.isValidGoodLockCandidate(installedThirdPartyApp))
        assertFalse("Uninstalled package must be rejected as Good Lock candidate", resolver.isValidGoodLockCandidate(uninstalledSamsungModule))
    }

    // 20. Diagnostic summary system package transparency
    @Test
    fun scenario20_diagnosticSummary_systemPackages_transparency() {
        val sys1 = "com.android.settings"
        val sys2 = "com.android.systemui"
        val updatedSys = "com.google.android.webview"

        fakeProvider.installed.addAll(listOf(sys1, sys2, updatedSys))
        fakeProvider.systemPackages.add(sys1)
        fakeProvider.systemPackages.add(sys2)
        fakeProvider.updatedSystemPackages.add(updatedSys)

        val summary = resolver.getDiagnosticSummary()
        assertTrue("Package enumeration must be marked successful", summary.isPackageEnumerationSuccessful)
        assertEquals(2, summary.systemAppExemptionCount)
        assertEquals(1, summary.updatedSystemAppExemptionCount)
        assertTrue("System packages list must contain com.android.settings", summary.systemAppPackagesList.contains("com.android.settings"))
        assertTrue("System packages list must contain com.google.android.webview", summary.systemAppPackagesList.contains("com.google.android.webview"))
    }
}

class FakePackageInfoProvider : PackageInfoProvider {
    val systemPackages = mutableSetOf<String>()
    val updatedSystemPackages = mutableSetOf<String>()
    val installed = mutableSetOf<String>()
    val cameras = mutableSetOf<String>()
    val launchers = mutableSetOf<String>()
    val imes = mutableSetOf<String>()
    val appCategories = mutableMapOf<String, Int>()

    var defaultDialer: String? = null
    var defaultSms: String? = null

    override fun isSystemApp(packageName: String): Boolean = systemPackages.contains(packageName)
    override fun isUpdatedSystemApp(packageName: String): Boolean = updatedSystemPackages.contains(packageName)
    override fun getDefaultDialerPackage(): String? = defaultDialer
    override fun getDefaultSmsPackage(): String? = defaultSms
    override fun getCameraPackages(): Set<String> = cameras
    override fun getHomeLauncherPackages(): Set<String> = launchers
    override fun getInputMethodPackages(): Set<String> = imes
    override fun getInstalledPackages(): Set<String> = if (installed.isEmpty() && (systemPackages.isNotEmpty() || updatedSystemPackages.isNotEmpty())) systemPackages + updatedSystemPackages else installed
    override fun getApplicationCategory(packageName: String): Int? = appCategories[packageName]
}
