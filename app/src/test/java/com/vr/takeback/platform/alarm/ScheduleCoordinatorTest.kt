package com.vr.takeback.platform.alarm

import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.model.Schedule
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ScheduleCoordinatorTest {

    private val engine = CentralRestrictionEngine()
    private val zone = ZoneId.of("UTC")

    @Test
    fun testDaytimeSchedule_nextStartAndEnd() {
        val schedule = Schedule(
            id = 1,
            profileId = "work_hours",
            daysOfWeekMask = 31, // Mon-Fri (1, 2, 4, 8, 16)
            startTimeSeconds = 9 * 3600,  // 09:00
            endTimeSeconds = 17 * 3600,   // 17:00
            isEnabled = true
        )

        // Given: Tuesday 08:00
        val tuesdayMorning = LocalDateTime.of(2026, 11, 24, 8, 0) // Nov 24, 2026 is Tuesday
        assertEquals(DayOfWeek.TUESDAY, tuesdayMorning.dayOfWeek)

        val nextStart = ScheduleCoordinator.computeNextStartOccurrenceMillis(schedule, tuesdayMorning, zone)
        assertNotNull(nextStart)
        val expectedStart = LocalDateTime.of(2026, 11, 24, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedStart, nextStart)

        val nextEnd = ScheduleCoordinator.computeNextEndOccurrenceMillis(schedule, tuesdayMorning, zone)
        assertNotNull(nextEnd)
        val expectedEnd = LocalDateTime.of(2026, 11, 24, 17, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedEnd, nextEnd)
    }

    @Test
    fun testOvernightSchedule_fridayToSaturday_endCalculatesCorrectNextDay() {
        // Overnight: Friday 22:00 to Saturday 06:00
        // Friday is active (bit 16). Saturday is NOT in mask!
        val fridayMask = 16 // Friday only (1 shl 4 = 16)
        val schedule = Schedule(
            id = 2,
            profileId = "sleep_schedule",
            daysOfWeekMask = fridayMask,
            startTimeSeconds = 22 * 3600, // 22:00
            endTimeSeconds = 6 * 3600,    // 06:00
            isEnabled = true
        )

        // Given: Friday 15:00
        val fridayAfternoon = LocalDateTime.of(2026, 11, 27, 15, 0) // Nov 27, 2026 is Friday
        assertEquals(DayOfWeek.FRIDAY, fridayAfternoon.dayOfWeek)

        // 1. Next start must be Friday 22:00
        val nextStart = ScheduleCoordinator.computeNextStartOccurrenceMillis(schedule, fridayAfternoon, zone)
        val expectedStart = LocalDateTime.of(2026, 11, 27, 22, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Start must be Friday at 22:00", expectedStart, nextStart)

        // 2. Next end must be Saturday 06:00 (1 day after Friday start), even though Saturday is not in mask!
        val nextEnd = ScheduleCoordinator.computeNextEndOccurrenceMillis(schedule, fridayAfternoon, zone)
        val expectedEnd = LocalDateTime.of(2026, 11, 28, 6, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Overnight end must be Saturday at 06:00", expectedEnd, nextEnd)

        // 3. CentralRestrictionEngine evaluation during overnight window:
        // Friday 23:30 -> Active (started Friday evening)
        val activeFridayNight = engine.findActiveSchedule(
            listOf(schedule),
            LocalTime.of(23, 30),
            DayOfWeek.FRIDAY
        )
        assertNotNull("Friday 23:30 must be actively enforcing", activeFridayNight)

        // Saturday 04:30 -> Active (started Friday evening, ends Saturday 06:00)
        val activeSaturdayMorning = engine.findActiveSchedule(
            listOf(schedule),
            LocalTime.of(4, 30),
            DayOfWeek.SATURDAY
        )
        assertNotNull("Saturday 04:30 must be actively enforcing from Friday overnight schedule", activeSaturdayMorning)

        // Saturday 07:00 -> NOT Active (expired at 06:00)
        val expiredSaturday = engine.findActiveSchedule(
            listOf(schedule),
            LocalTime.of(7, 0),
            DayOfWeek.SATURDAY
        )
        assertNull("Saturday 07:00 must NOT be active after 06:00 expiration", expiredSaturday)
    }

    @Test
    fun testEqualStartAndEndTime_treatedAs24HourActive() {
        val schedule = Schedule(
            id = 3,
            profileId = "full_day_sunday",
            daysOfWeekMask = 64, // Sunday only (1 shl 6 = 64)
            startTimeSeconds = 10 * 3600,
            endTimeSeconds = 10 * 3600, // Equal!
            isEnabled = true
        )

        // Engine must evaluate 24-hour enforcement on Sunday
        val activeSundayMorning = engine.findActiveSchedule(
            listOf(schedule),
            LocalTime.of(2, 0),
            DayOfWeek.SUNDAY
        )
        assertNotNull("Equal start/end times must be active all day on selected day", activeSundayMorning)

        val activeSundayEvening = engine.findActiveSchedule(
            listOf(schedule),
            LocalTime.of(23, 0),
            DayOfWeek.SUNDAY
        )
        assertNotNull(activeSundayEvening)

        val notActiveMonday = engine.findActiveSchedule(
            listOf(schedule),
            LocalTime.of(10, 0),
            DayOfWeek.MONDAY
        )
        assertNull("Must not be active on unselected day", notActiveMonday)
    }
}
