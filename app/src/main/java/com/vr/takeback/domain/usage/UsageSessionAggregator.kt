package com.vr.takeback.domain.usage

enum class NormalizedEventType {
    ACTIVITY_RESUMED,
    ACTIVITY_PAUSED,
    ACTIVITY_STOPPED,
    SCREEN_NON_INTERACTIVE,
    SCREEN_INTERACTIVE,
    KEYGUARD_SHOWN,
    KEYGUARD_HIDDEN,
    DEVICE_SHUTDOWN,
    DEVICE_STARTUP,
    OTHER
}

data class RawUsageEvent(
    val packageName: String,
    val className: String? = null,
    val timestampMillis: Long,
    val type: NormalizedEventType
)

data class SessionInterval(
    val packageName: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long
) {
    val durationMillis: Long get() = maxOf(0L, endEpochMillis - startEpochMillis)
}

object UsageSessionAggregator {

    private data class ActivityKey(
        val packageName: String,
        val className: String?
    )

    fun reconstructSessions(
        events: List<RawUsageEvent>,
        startEpochMillis: Long,
        endEpochMillis: Long,
        isToday: Boolean,
        isUserLaunchableApp: (String) -> Boolean
    ): List<SessionInterval> {
        val activeActivities = mutableMapOf<ActivityKey, Long>()
        val intervals = mutableListOf<SessionInterval>()
        var screenInteractive = true
        var deviceShuttingDown = false

        fun closeActivity(key: ActivityKey, time: Long) {
            val started = activeActivities.remove(key) ?: return
            val start = maxOf(startEpochMillis, started)
            val end = minOf(endEpochMillis, time)
            if (end > start && isUserLaunchableApp(key.packageName)) {
                intervals.add(
                    SessionInterval(
                        packageName = key.packageName,
                        startEpochMillis = start,
                        endEpochMillis = end
                    )
                )
            }
        }

        fun closeAllActivities(time: Long) {
            val keys = activeActivities.keys.toList()
            for (key in keys) {
                closeActivity(key, time)
            }
        }

        val sortedEvents = events.sortedBy { it.timestampMillis }
        for (event in sortedEvents) {
            val key = ActivityKey(event.packageName, event.className)
            val time = event.timestampMillis

            when (event.type) {
                NormalizedEventType.ACTIVITY_RESUMED -> {
                    if (screenInteractive && event.packageName != "android") {
                        activeActivities[key] = time
                    }
                }
                NormalizedEventType.ACTIVITY_PAUSED,
                NormalizedEventType.ACTIVITY_STOPPED -> {
                    closeActivity(key, time)
                }
                NormalizedEventType.SCREEN_NON_INTERACTIVE,
                NormalizedEventType.KEYGUARD_SHOWN -> {
                    screenInteractive = false
                    closeAllActivities(time)
                }
                NormalizedEventType.SCREEN_INTERACTIVE,
                NormalizedEventType.KEYGUARD_HIDDEN -> {
                    screenInteractive = true
                }
                NormalizedEventType.DEVICE_SHUTDOWN -> {
                    closeAllActivities(time)
                    deviceShuttingDown = true
                }
                NormalizedEventType.DEVICE_STARTUP -> {
                    deviceShuttingDown = false
                }
                NormalizedEventType.OTHER -> { /* no-op */ }
            }
        }

        if (isToday && !deviceShuttingDown) {
            closeAllActivities(minOf(endEpochMillis, System.currentTimeMillis()))
        }

        // Merge overlapping or adjacent intervals per package
        val mergedSessions = mutableListOf<SessionInterval>()
        intervals
            .groupBy { it.packageName }
            .forEach { (pkg, packageIntervals) ->
                val sorted = packageIntervals.sortedBy { it.startEpochMillis }
                if (sorted.isEmpty()) return@forEach

                var mergedStart = sorted.first().startEpochMillis
                var mergedEnd = sorted.first().endEpochMillis

                fun emitSession() {
                    if (mergedEnd - mergedStart >= 1000L) {
                        mergedSessions.add(
                            SessionInterval(
                                packageName = pkg,
                                startEpochMillis = mergedStart,
                                endEpochMillis = mergedEnd
                            )
                        )
                    }
                }

                for (interval in sorted.drop(1)) {
                    if (interval.startEpochMillis <= mergedEnd) {
                        mergedEnd = maxOf(mergedEnd, interval.endEpochMillis)
                    } else {
                        emitSession()
                        mergedStart = interval.startEpochMillis
                        mergedEnd = interval.endEpochMillis
                    }
                }
                emitSession()
            }

        return mergedSessions.sortedBy { it.startEpochMillis }
    }
}
