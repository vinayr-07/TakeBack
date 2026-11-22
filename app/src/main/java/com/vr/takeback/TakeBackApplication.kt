package com.vr.takeback

import android.app.Application
import com.vr.takeback.data.repository.*
import com.vr.takeback.domain.security.EssentialModeExemptionResolver
import com.vr.takeback.platform.alarm.ScheduleAlarmScheduler
import com.vr.takeback.platform.util.AndroidPackageInfoProvider

class TakeBackApplication : Application() {

    companion object {
        lateinit var instance: TakeBackApplication
            private set

        val profileRepository by lazy { ProfileRepository(instance.applicationContext) }
        val quickBlockRepository by lazy { QuickBlockRepository() }
        val focusSessionRepository by lazy { FocusSessionRepository() }
        val permissionRepository by lazy { PermissionRepository(instance.applicationContext) }
        val strictModeRepository by lazy { StrictModeRepository(instance.applicationContext) }
        val alarmRepository by lazy { AlarmRepository(instance.applicationContext) }
        val usageStatsRepository by lazy { UsageStatsRepository(instance.applicationContext) }
        val timelineRepository by lazy { TimelineRepository(instance.applicationContext) }
        val billingRepository by lazy { BillingRepository(instance.applicationContext) }
        val alarmScheduler by lazy { ScheduleAlarmScheduler(instance.applicationContext) }
        val packageInfoProvider by lazy { AndroidPackageInfoProvider(instance.applicationContext) }
        val essentialModeExemptionResolver by lazy { EssentialModeExemptionResolver(packageInfoProvider) }
        val essentialModeRepository by lazy { EssentialModeRepository(instance.applicationContext) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Reconcile saved custom Good Lock modules with exemption resolver
        try {
            val customModules = essentialModeRepository.state.value.customGoodLockModules
            essentialModeExemptionResolver.setCustomGoodLockModules(customModules)
        } catch (e: Exception) {
            // Ignore
        }

        // Safe migration: Clear any obsolete Web Shield DNS preferences from earlier versions
        try {
            val dnsPrefs = getSharedPreferences("takeback_dns_prefs", MODE_PRIVATE)
            dnsPrefs.edit().clear().apply()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
