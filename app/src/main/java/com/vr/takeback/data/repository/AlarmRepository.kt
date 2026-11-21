package com.vr.takeback.data.repository

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vr.takeback.MainActivity
import com.vr.takeback.domain.model.AlarmChallengeDifficulty
import com.vr.takeback.domain.model.StrictAlarm
import com.vr.takeback.platform.alarm.StrictAlarmReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class AlarmRepository(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private val _alarms = MutableStateFlow(
        listOf(
            StrictAlarm(
                id = "default_morning_alarm",
                hour = 6,
                minute = 30,
                daysOfWeekMask = 31, // Mon-Fri
                isEnabled = true,
                difficulty = AlarmChallengeDifficulty.MEDIUM,
                label = "Morning Focus Awakening"
            )
        )
    )
    val alarms: StateFlow<List<StrictAlarm>> = _alarms.asStateFlow()

    init {
        // Schedule active alarms upon initialization
        scheduleActiveAlarms()
    }

    fun toggleAlarm(id: String) {
        val updated = _alarms.value.map {
            if (it.id == id) it.copy(isEnabled = !it.isEnabled) else it
        }
        _alarms.value = updated
        scheduleActiveAlarms()
    }

    fun saveAlarm(alarm: StrictAlarm) {
        val current = _alarms.value.toMutableList()
        val index = current.indexOfFirst { it.id == alarm.id }
        if (index >= 0) {
            current[index] = alarm
        } else {
            current.add(alarm)
        }
        _alarms.value = current
        scheduleActiveAlarms()
    }

    fun deleteAlarm(id: String) {
        _alarms.value = _alarms.value.filter { it.id != id }
        cancelAlarmIntent(id)
    }

    private fun scheduleActiveAlarms() {
        if (alarmManager == null) return

        for (alarm in _alarms.value) {
            if (alarm.isEnabled) {
                val nextEpochMillis = computeNextAlarmMillis(alarm.hour, alarm.minute, alarm.daysOfWeekMask)
                scheduleExactAlarm(alarm, nextEpochMillis)
            } else {
                cancelAlarmIntent(alarm.id)
            }
        }
    }

    private fun scheduleExactAlarm(alarm: StrictAlarm, triggerEpochMillis: Long) {
        if (alarmManager == null) return

        val intent = Intent(context, StrictAlarmReceiver::class.java).apply {
            putExtra("EXTRA_ALARM_ID", alarm.id)
            putExtra("EXTRA_ALARM_LABEL", alarm.label)
            putExtra("EXTRA_DIFFICULTY", alarm.difficulty.name)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val showIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerEpochMillis, showIntent)
        alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
    }

    private fun cancelAlarmIntent(alarmId: String) {
        if (alarmManager == null) return
        val intent = Intent(context, StrictAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun computeNextAlarmMillis(hour: Int, minute: Int, daysOfWeekMask: Int): Long {
        val now = LocalDateTime.now()
        val targetTime = LocalTime.of(hour, minute)

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
