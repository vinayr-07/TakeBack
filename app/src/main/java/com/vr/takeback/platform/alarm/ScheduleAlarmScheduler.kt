package com.vr.takeback.platform.alarm

import android.content.Context
import com.vr.takeback.domain.model.Profile

class ScheduleAlarmScheduler(private val context: Context) {
    fun scheduleNextAlarmsForProfile(profile: Profile) {
        ScheduleCoordinator.rescheduleAll(context.applicationContext, listOf(profile))
    }
}
