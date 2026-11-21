package com.vr.takeback.platform.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.vr.takeback.presentation.ui.alarm.StrictAlarmActivity

class StrictAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val label = intent.getStringExtra("EXTRA_ALARM_LABEL") ?: "Wake Up"
        val difficulty = intent.getStringExtra("EXTRA_DIFFICULTY") ?: "MEDIUM"

        // Acquire temporary wakelock to keep CPU awake during transition
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TakeBack:StrictAlarmWakeLock"
        )
        wakeLock?.acquire(30000L) // 30 second safe timeout

        // Launch full-screen StrictAlarmActivity
        val alarmIntent = Intent(context, StrictAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra("EXTRA_ALARM_LABEL", label)
            putExtra("EXTRA_DIFFICULTY", difficulty)
        }
        context.startActivity(alarmIntent)
    }
}
