package com.vr.takeback.platform.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vr.takeback.data.repository.ProfileRepository
import com.vr.takeback.platform.alarm.ScheduleAlarmScheduler

class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val profileId = intent.getStringExtra("EXTRA_PROFILE_ID") ?: return
        val profileRepository = ProfileRepository()
        val profile = profileRepository.getProfileById(profileId)

        if (profile != null && profile.isEnabled) {
            // Re-schedule next boundary for this profile
            val scheduler = ScheduleAlarmScheduler(context)
            scheduler.scheduleNextAlarmsForProfile(profile)
        }
    }
}
