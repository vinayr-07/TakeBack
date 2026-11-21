package com.vr.takeback.platform.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vr.takeback.MainActivity
import com.vr.takeback.domain.model.Profile
import com.vr.takeback.platform.receiver.ScheduleAlarmReceiver
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ScheduleAlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun scheduleNextAlarmsForProfile(profile: Profile) {
        if (alarmManager == null || profile.schedules.isEmpty()) return

        for (schedule in profile.schedules) {
            if (!schedule.isEnabled) continue

            val nextStartEpoch = computeNextOccurrenceMillis(schedule.startTimeSeconds, schedule.daysOfWeekMask)
            val nextEndEpoch = computeNextOccurrenceMillis(schedule.endTimeSeconds, schedule.daysOfWeekMask)

            scheduleAlarm(profile.id, nextStartEpoch, requestCode = (profile.id.hashCode() * 31 + schedule.id.toInt() * 2))
            scheduleAlarm(profile.id, nextEndEpoch, requestCode = (profile.id.hashCode() * 31 + schedule.id.toInt() * 2 + 1))
        }
    }

    private fun scheduleAlarm(profileId: String, triggerEpochMillis: Long, requestCode: Int) {
        if (alarmManager == null) return

        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            putExtra("EXTRA_PROFILE_ID", profileId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val showIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // setAlarmClock guarantees punctuality without Doze throttling
        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerEpochMillis, showIntent)
        alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
    }

    private fun computeNextOccurrenceMillis(timeSeconds: Int, daysOfWeekMask: Int): Long {
        val now = LocalDateTime.now()
        val targetTime = LocalTime.ofSecondOfDay(timeSeconds.toLong())

        for (offset in 0..7) {
            val candidateDate = LocalDate.now().plusDays(offset.toLong())
            val candidateDayIndex = candidateDate.dayOfWeek.value // 1..7
            val isDayActive = (daysOfWeekMask and (1 shl (candidateDayIndex - 1))) != 0

            if (isDayActive) {
                val candidateDateTime = LocalDateTime.of(candidateDate, targetTime)
                if (candidateDateTime.isAfter(now)) {
                    return candidateDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            }
        }
        return now.plusDays(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
