package com.vr.takeback.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.vr.takeback.domain.model.ActivityEvent
import com.vr.takeback.domain.model.EventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

class TimelineRepository(private val context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("takeback_timeline_prefs", Context.MODE_PRIVATE)
    private val idCounter = AtomicLong(System.currentTimeMillis())

    private val _events = MutableStateFlow<List<ActivityEvent>>(loadPersistedEvents())
    val events: StateFlow<List<ActivityEvent>> = _events.asStateFlow()

    private fun loadPersistedEvents(): List<ActivityEvent> {
        val jsonStr = prefs?.getString("saved_events_json", null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<ActivityEvent>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        ActivityEvent(
                            id = obj.optLong("id", System.currentTimeMillis()),
                            eventType = try { EventType.valueOf(obj.getString("eventType")) } catch (e: Exception) { EventType.APP_BLOCKED },
                            targetIdentifier = obj.getString("targetIdentifier"),
                            profileId = if (obj.has("profileId") && !obj.isNull("profileId")) obj.getString("profileId") else null,
                            timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis()),
                            extraMetadata = if (obj.has("extraMetadata") && !obj.isNull("extraMetadata")) obj.getString("extraMetadata") else null
                        )
                    )
                }
                return list
            } catch (e: Exception) {
                // Fallback
            }
        }
        return emptyList()
    }

    private fun persistEvents(list: List<ActivityEvent>) {
        if (prefs == null) return
        try {
            val array = JSONArray()
            for (e in list.take(100)) {
                array.put(JSONObject().apply {
                    put("id", e.id)
                    put("eventType", e.eventType.name)
                    put("targetIdentifier", e.targetIdentifier)
                    put("profileId", e.profileId)
                    put("timestampMillis", e.timestampMillis)
                    put("extraMetadata", e.extraMetadata)
                })
            }
            prefs.edit().putString("saved_events_json", array.toString()).apply()
        } catch (e: Exception) {
            // Log fallback
        }
    }

    fun logEvent(
        eventType: EventType,
        targetIdentifier: String,
        profileId: String? = null,
        extraMetadata: String? = null
    ) {
        val newEvent = ActivityEvent(
            id = idCounter.getAndIncrement(),
            eventType = eventType,
            targetIdentifier = targetIdentifier,
            profileId = profileId,
            timestampMillis = System.currentTimeMillis(),
            extraMetadata = extraMetadata
        )

        val updated = mutableListOf(newEvent)
        updated.addAll(_events.value)

        // Prune older events beyond 100 in memory or older than 30 days
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 3600 * 1000L)
        val pruned = updated.filter { it.timestampMillis > thirtyDaysAgo }.take(100)
        _events.value = pruned
        persistEvents(pruned)
    }

    fun clearTimeline() {
        _events.value = emptyList()
        prefs?.edit()?.remove("saved_events_json")?.apply()
    }
}
