package com.vr.takeback.platform.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vr.takeback.platform.alarm.ScheduleCoordinator
import com.vr.takeback.platform.service.TakeBackAccessibilityService

class ScheduleAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduleAlarmReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val appContext = context.applicationContext
        val profileId = intent.getStringExtra("EXTRA_PROFILE_ID")
        Log.i(TAG, "Schedule boundary alarm triggered for profile=$profileId. Re-evaluating active restrictions.")

        // 1. Reschedule next alarms across all active schedules
        ScheduleCoordinator.rescheduleAll(appContext)

        // 2. Immediately notify TakeBackAccessibilityService to re-evaluate the foreground application
        TakeBackAccessibilityService.notifyScheduleBoundary(appContext)
    }
}
