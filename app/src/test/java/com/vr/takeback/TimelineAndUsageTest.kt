package com.vr.takeback

import com.vr.takeback.data.repository.AppUsageSession
import com.vr.takeback.data.repository.TimelineRepository
import com.vr.takeback.domain.model.EventType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TimelineAndUsageTest {

    private lateinit var timelineRepository: TimelineRepository

    @Before
    fun setUp() {
        timelineRepository = TimelineRepository()
    }

    @Test
    fun timelineRepository_logsEvent_andMaintainsOrder() {
        val initialCount = timelineRepository.events.value.size

        timelineRepository.logEvent(
            eventType = EventType.APP_BLOCKED,
            targetIdentifier = "TikTok",
            profileId = "study_preset",
            extraMetadata = "Study schedule in effect"
        )

        val updated = timelineRepository.events.value
        assertEquals("Timeline event count should increase by 1", initialCount + 1, updated.size)
        assertEquals("Most recent event should be first", "TikTok", updated.first().targetIdentifier)
        assertEquals(EventType.APP_BLOCKED, updated.first().eventType)
    }

    @Test
    fun timelineRepository_clearTimeline_emptiesAllEvents() {
        timelineRepository.clearTimeline()
        assertTrue("Timeline should be empty after clear", timelineRepository.events.value.isEmpty())
    }

    @Test
    fun eventTypes_coverAllDocumentedWellbeingActions() {
        val allTypes = EventType.values().map { it.name }
        assertTrue(allTypes.contains("APP_BLOCKED"))
        assertTrue(allTypes.contains("FOCUS_STARTED"))
        assertTrue(allTypes.contains("BREAK_STARTED"))
        assertTrue(allTypes.contains("PROFILE_ACTIVATED"))
        assertTrue(allTypes.contains("LIMIT_EXHAUSTED"))
    }

    @Test
    fun appUsageSession_formatsRailwayTimingsAndDurationCorrectly() {
        // Set fixed epoch timestamps: e.g. 14:15:00 to 14:35:00
        val zone = ZoneId.systemDefault()
        val startMillis = 1732179300000L // arbitrary fixed time
        val durationMillis = 20 * 60 * 1000L // 20 minutes
        val endMillis = startMillis + durationMillis

        val session = AppUsageSession(
            packageName = "com.android.chrome",
            appName = "Chrome",
            startTimeEpochMillis = startMillis,
            endTimeEpochMillis = endMillis,
            durationMillis = durationMillis
        )

        assertEquals("20m", session.durationFormatted)

        // Verify railway 24h format matches pattern HH:mm - HH:mm
        val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        val expected = "${formatter.format(Instant.ofEpochMilli(startMillis))} - ${formatter.format(Instant.ofEpochMilli(endMillis))}"
        assertEquals(expected, session.railwayTimeRange)
        assertTrue(session.railwayTimeRange.matches(Regex("\\d{2}:\\d{2} - \\d{2}:\\d{2}")))
    }

    @Test
    fun appUsageHistory_filtersAndOrdersSessionsForSpecificApp() {
        val baseTime = 1732176000000L // 08:00:00

        val sessionWhatsApp1 = AppUsageSession("com.whatsapp", "WhatsApp", baseTime, baseTime + 10 * 60 * 1000L, 10 * 60 * 1000L)
        val sessionChrome = AppUsageSession("com.android.chrome", "Chrome", baseTime + 15 * 60 * 1000L, baseTime + 25 * 60 * 1000L, 10 * 60 * 1000L)
        val sessionWhatsApp2 = AppUsageSession("com.whatsapp", "WhatsApp", baseTime + 30 * 60 * 1000L, baseTime + 45 * 60 * 1000L, 15 * 60 * 1000L)

        val allSessions = listOf(sessionWhatsApp1, sessionChrome, sessionWhatsApp2)

        val whatsAppHistory = allSessions.filter { it.packageName == "com.whatsapp" }
        assertEquals("WhatsApp should have exactly 2 launch sessions", 2, whatsAppHistory.size)
        assertEquals("10m", whatsAppHistory[0].durationFormatted)
        assertEquals("15m", whatsAppHistory[1].durationFormatted)

        val totalWhatsAppTime = whatsAppHistory.sumOf { it.durationMillis }
        assertEquals(25 * 60 * 1000L, totalWhatsAppTime)
    }
}
