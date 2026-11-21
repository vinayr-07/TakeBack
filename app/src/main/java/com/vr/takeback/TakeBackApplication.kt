package com.vr.takeback

import android.app.Application
import com.vr.takeback.data.repository.*
import com.vr.takeback.platform.alarm.ScheduleAlarmScheduler

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
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
