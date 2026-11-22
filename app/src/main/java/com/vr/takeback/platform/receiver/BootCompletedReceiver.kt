package com.vr.takeback.platform.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vr.takeback.TakeBackApplication
import com.vr.takeback.platform.alarm.ScheduleCoordinator
import com.vr.takeback.platform.service.TakeBackAccessibilityService
import com.vr.takeback.platform.service.TakeBackEnforcementForegroundService

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val appContext = context.applicationContext
        val action = intent.action
        Log.i(TAG, "System event received: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                // 1. Restart background enforcement service
                TakeBackEnforcementForegroundService.start(appContext)

                // 2. Re-verify Device Administrator status
                try {
                    TakeBackApplication.strictModeRepository.refreshAdminStatus()
                } catch (e: Exception) {
                    Log.w(TAG, "Error refreshing admin status on boot", e)
                }

                // 3. Reschedule all alarms using ScheduleCoordinator and AlarmRepository
                ScheduleCoordinator.rescheduleAll(appContext)
                try {
                    TakeBackApplication.alarmRepository.rescheduleAllAlarms()
                } catch (e: Exception) {
                    Log.w(TAG, "Error rescheduling strict alarms on boot", e)
                }

                // 4. Notify running accessibility service
                TakeBackAccessibilityService.notifyScheduleBoundary(appContext)
            }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                Log.i(TAG, "Time or timezone altered: synchronizing all schedule alarms")
                ScheduleCoordinator.rescheduleAll(appContext)
                TakeBackAccessibilityService.notifyScheduleBoundary(appContext)
            }
        }
    }
}
