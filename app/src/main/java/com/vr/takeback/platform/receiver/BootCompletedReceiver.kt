package com.vr.takeback.platform.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vr.takeback.data.repository.ProfileRepository
import com.vr.takeback.platform.alarm.ScheduleAlarmScheduler
import com.vr.takeback.platform.service.TakeBackEnforcementForegroundService

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            // 1. Restart the background enforcement service
            TakeBackEnforcementForegroundService.start(context)

            // 2. Reschedule all exact alarms for active profiles
            val profileRepository = ProfileRepository()
            val profiles = profileRepository.profiles.value
            val scheduler = ScheduleAlarmScheduler(context)

            for (profile in profiles) {
                if (profile.isEnabled && profile.schedules.isNotEmpty()) {
                    scheduler.scheduleNextAlarmsForProfile(profile)
                }
            }
        }
    }
}
