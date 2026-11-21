package com.vr.takeback.platform.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vr.takeback.MainActivity
import com.vr.takeback.domain.model.Profile
import com.vr.takeback.domain.model.Schedule
import com.vr.takeback.platform.receiver.ScheduleAlarmReceiver
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Central Schedule Coordinator.
 * Manages punctual Android AlarmManager alarms for all restriction schedules.
 *
 * SPECIFICATION & ARCHITECTURAL INVARIANTS:
 * 1. Overnight Schedules (e.g. Friday 22:00 to Saturday 06:00):
 *    - Start day is Friday (selected in daysOfWeekMask).
 *    - End day is Saturday (exactly +1 day from start).
 *    - Saturday is NOT required to be selected as a separate start day.
 * 2. Equal Start & End Times (e.g. 09:00 to 09:00):
 *    - Explicitly defined as a 24-hour full-day restriction for selected active days.
 * 3. Automatic Updates:
 *    - Updates AlarmManager on profile enable/disable, schedule create/edit/delete,
 *      device boot, time changes, timezone changes, and daylight-saving transitions.
 * 4. Punctuality:
 *    - Uses AlarmManager.setAlarmClock() to prevent Doze/OEM throttling.
 */
object ScheduleCoordinator {

    private const val TAG = "ScheduleCoordinator"

    /**
     * Re-synchronizes alarms for all enabled profiles currently in repository.
     */
    fun rescheduleAll(context: Context, profiles: List<Profile>? = null) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val profileList = profiles ?: run {
            try {
                com.vr.takeback.TakeBackApplication.profileRepository.profiles.value
            } catch (e: Exception) {
                emptyList()
            }
        }

        for (profile in profileList) {
            syncAlarmsForProfile(context, alarmManager, profile)
        }
    }

    /**
     * Called whenever a profile is saved, toggled, or modified.
     */
    fun onProfilesChanged(context: Context, profiles: List<Profile>) {
        rescheduleAll(context, profiles)
    }

    private fun syncAlarmsForProfile(context: Context, alarmManager: AlarmManager, profile: Profile) {
        if (!profile.isEnabled || profile.schedules.isEmpty()) {
            // Cancel any scheduled alarms for this profile
            cancelAlarmsForProfile(context, alarmManager, profile)
            return
        }

        for (schedule in profile.schedules) {
            if (!schedule.isEnabled) {
                cancelScheduleAlarms(context, alarmManager, profile.id, schedule.id)
                continue
            }

            val nextStartEpoch = computeNextStartOccurrenceMillis(schedule)
            val nextEndEpoch = computeNextEndOccurrenceMillis(schedule)

            if (nextStartEpoch != null) {
                val startReqCode = getRequestCode(profile.id, schedule.id, isStart = true)
                scheduleExactAlarm(context, alarmManager, profile.id, nextStartEpoch, startReqCode)
            }

            if (nextEndEpoch != null) {
                val endReqCode = getRequestCode(profile.id, schedule.id, isStart = false)
                scheduleExactAlarm(context, alarmManager, profile.id, nextEndEpoch, endReqCode)
            }
        }
    }

    private fun cancelAlarmsForProfile(context: Context, alarmManager: AlarmManager, profile: Profile) {
        for (schedule in profile.schedules) {
            cancelScheduleAlarms(context, alarmManager, profile.id, schedule.id)
        }
    }

    private fun cancelScheduleAlarms(context: Context, alarmManager: AlarmManager, profileId: String, scheduleId: Long) {
        val startReq = getRequestCode(profileId, scheduleId, isStart = true)
        val endReq = getRequestCode(profileId, scheduleId, isStart = false)
        cancelSingleAlarm(context, alarmManager, startReq)
        cancelSingleAlarm(context, alarmManager, endReq)
    }

    private fun cancelSingleAlarm(context: Context, alarmManager: AlarmManager, requestCode: Int) {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun scheduleExactAlarm(
        context: Context,
        alarmManager: AlarmManager,
        profileId: String,
        triggerEpochMillis: Long,
        requestCode: Int
    ) {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            putExtra("EXTRA_PROFILE_ID", profileId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
            Log.w(TAG, "Missing exact alarm permission, falling back to setAndAllowWhileIdle", e)
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerEpochMillis, pendingIntent)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to schedule alarm", ex)
            }
        }
    }

    private fun getRequestCode(profileId: String, scheduleId: Long, isStart: Boolean): Int {
        val base = (profileId.hashCode() * 31) + scheduleId.toInt() * 2
        return if (isStart) base else base + 1
    }

    /**
     * Computes the next upcoming start occurrence in epoch milliseconds.
     */
    fun computeNextStartOccurrenceMillis(
        schedule: Schedule,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val startTime = LocalTime.ofSecondOfDay(schedule.startTimeSeconds.toLong())

        for (dayOffset in 0..7) {
            val candidateDate = now.toLocalDate().plusDays(dayOffset.toLong())
            val dayOfWeekIndex = candidateDate.dayOfWeek.value // 1..7 (Mon..Sun)

            if (schedule.isDayActive(dayOfWeekIndex)) {
                val candidateDateTime = LocalDateTime.of(candidateDate, startTime)
                if (candidateDateTime.isAfter(now)) {
                    return candidateDateTime.atZone(zone).toInstant().toEpochMilli()
                }
            }
        }
        return null
    }

    /**
     * Computes the next upcoming end occurrence in epoch milliseconds.
     * Correctly handles overnight schedules (e.g. Friday 22:00 to Saturday 06:00).
     */
    fun computeNextEndOccurrenceMillis(
        schedule: Schedule,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val startSec = schedule.startTimeSeconds
        val endSec = schedule.endTimeSeconds
        val isOvernight = startSec > endSec
        val isEqual = startSec == endSec

        if (isEqual) {
            // 24-hour active schedule: ends at 23:59:59 of the active day
            for (dayOffset in 0..7) {
                val candidateDate = now.toLocalDate().plusDays(dayOffset.toLong())
                val dayOfWeekIndex = candidateDate.dayOfWeek.value
                if (schedule.isDayActive(dayOfWeekIndex)) {
                    val candidateEnd = LocalDateTime.of(candidateDate, LocalTime.MAX)
                    if (candidateEnd.isAfter(now)) {
                        return candidateEnd.atZone(zone).toInstant().toEpochMilli()
                    }
                }
            }
            return null
        }

        val endTime = LocalTime.ofSecondOfDay(endSec.toLong())

        for (dayOffset in 0..7) {
            val candidateStartDate = now.toLocalDate().plusDays(dayOffset.toLong())
            val dayOfWeekIndex = candidateStartDate.dayOfWeek.value // 1..7

            if (schedule.isDayActive(dayOfWeekIndex)) {
                // If overnight, the end occurs on the NEXT day (e.g. Friday 22:00 -> Saturday 06:00)
                val candidateEndDate = if (isOvernight) candidateStartDate.plusDays(1) else candidateStartDate
                val candidateEndDateTime = LocalDateTime.of(candidateEndDate, endTime)

                if (candidateEndDateTime.isAfter(now)) {
                    return candidateEndDateTime.atZone(zone).toInstant().toEpochMilli()
                }
            }
        }
        return null
    }
}
