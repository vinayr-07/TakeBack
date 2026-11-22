package com.vr.takeback.data.repository

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.vr.takeback.MainActivity
import com.vr.takeback.domain.model.AlarmChallengeDifficulty
import com.vr.takeback.domain.model.StrictAlarm
import com.vr.takeback.platform.alarm.StrictAlarmReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class AlarmRepository(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    private val prefs: SharedPreferences = context.getSharedPreferences("takeback_alarms_prefs", Context.MODE_PRIVATE)

    private val _alarms = MutableStateFlow(loadPersistedAlarms())
    val alarms: StateFlow<List<StrictAlarm>> = _alarms.asStateFlow()

    init {
        // Schedule active alarms upon initialization
        scheduleActiveAlarms()
    }

    private fun loadPersistedAlarms(): List<StrictAlarm> {
        val rawJson = prefs.getString("alarms_json", null)
        if (!rawJson.isNullOrBlank()) {
            try {
                val array = JSONArray(rawJson)
                val list = mutableListOf<StrictAlarm>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        StrictAlarm(
                            id = obj.getString("id"),
                            hour = obj.getInt("hour"),
                            minute = obj.getInt("minute"),
                            daysOfWeekMask = obj.optInt("daysOfWeekMask", 127),
                            isEnabled = obj.optBoolean("isEnabled", true),
                            difficulty = try {
                                AlarmChallengeDifficulty.valueOf(obj.getString("difficulty"))
                            } catch (e: Exception) {
                                AlarmChallengeDifficulty.MEDIUM
                            },
                            label = obj.optString("label", "Morning Focus")
                        )
                    )
                }
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                // Fallback to default
            }
        }
        val defaultList = listOf(
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
        persistAlarms(defaultList)
        return defaultList
    }

    private fun persistAlarms(list: List<StrictAlarm>) {
        try {
            val array = JSONArray()
            for (alarm in list) {
                val obj = JSONObject().apply {
                    put("id", alarm.id)
                    put("hour", alarm.hour)
                    put("minute", alarm.minute)
                    put("daysOfWeekMask", alarm.daysOfWeekMask)
                    put("isEnabled", alarm.isEnabled)
                    put("difficulty", alarm.difficulty.name)
                    put("label", alarm.label)
                }
                array.put(obj)
            }
            prefs.edit().putString("alarms_json", array.toString()).apply()
        } catch (e: Exception) {
            // Defensive handling
        }
    }

    fun toggleAlarm(id: String) {
        val updated = _alarms.value.map {
            if (it.id == id) it.copy(isEnabled = !it.isEnabled) else it
        }
        _alarms.value = updated
        persistAlarms(updated)
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
        persistAlarms(current)
        scheduleActiveAlarms()
    }

    fun deleteAlarm(id: String) {
        val updated = _alarms.value.filter { it.id != id }
        _alarms.value = updated
        persistAlarms(updated)
        cancelAlarmIntent(id)
    }

    fun rescheduleAllAlarms() {
        scheduleActiveAlarms()
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
        try {
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } catch (e: SecurityException) {
            android.util.Log.w("AlarmRepository", "Missing exact alarm permission, falling back to setAndAllowWhileIdle", e)
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerEpochMillis, pendingIntent)
            } catch (ex: Exception) {
                android.util.Log.e("AlarmRepository", "Failed to schedule alarm", ex)
            }
        }
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
