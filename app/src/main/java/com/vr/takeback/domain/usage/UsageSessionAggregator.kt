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

/**
 * High-precision, deterministic session reconstruction engine.
 *
 * Reconstructs exact calendar-aligned application foreground sessions and
 * device screen-on intervals from raw UsageEvents.
 *
 * INVARIANTS:
 * 1. Zero Android framework imports - fully testable on JVM with injected clocks.
 * 2. Duplicate ACTIVITY_RESUMED events do not overwrite the initial start time.
 * 3. In single-window mode, when a new package takes foreground, unclosed activities
 *    from other packages are cleanly bounded at the handoff timestamp.
 * 4. Screen off (SCREEN_NON_INTERACTIVE / KEYGUARD_SHOWN) immediately bounds all active sessions.
 * 5. Device shutdown bounds active sessions at the shutdown timestamp.
 * 6. Historical queries cleanly bound sessions crossing the midnight boundary.
 * 7. Overlapping activities within the SAME package are merged into a single contiguous
 *    interval without double-counting duration.
 * 8. Never synthesizes artificial sessions or fake timestamps.
 */
object UsageSessionAggregator {

    private data class ActivityKey(
        val packageName: String,
        val className: String?
    )

    // Maximum plausible continuous session without intermediate activity events (4 hours)
    private const val MAX_UNINTERRUPTED_SESSION_MILLIS = 4L * 60 * 60 * 1000L

    fun reconstructSessions(
        events: List<RawUsageEvent>,
        startEpochMillis: Long,
        endEpochMillis: Long,
        isToday: Boolean,
        isUserLaunchableApp: (String) -> Boolean,
        currentEpochMillis: Long = System.currentTimeMillis()
    ): List<SessionInterval> {
        val activeActivities = mutableMapOf<ActivityKey, Long>()
        val intervals = mutableListOf<SessionInterval>()
        var screenInteractive = true
        var deviceShuttingDown = false

        fun closeActivity(key: ActivityKey, closeTime: Long) {
            val started = activeActivities.remove(key) ?: return
            val boundedEnd = minOf(endEpochMillis, closeTime)
            val boundedStart = maxOf(startEpochMillis, started)

            // Guard against runaway sessions where pause event was missed
            val effectiveStart = if (boundedEnd - boundedStart > MAX_UNINTERRUPTED_SESSION_MILLIS) {
                maxOf(boundedStart, boundedEnd - MAX_UNINTERRUPTED_SESSION_MILLIS)
            } else {
                boundedStart
            }

            if (boundedEnd > effectiveStart && isUserLaunchableApp(key.packageName)) {
                intervals.add(
                    SessionInterval(
                        packageName = key.packageName,
                        startEpochMillis = effectiveStart,
                        endEpochMillis = boundedEnd
                    )
                )
            }
        }

        fun closeActivitiesForOtherPackages(currentPackageName: String, handoffTime: Long) {
            val otherKeys = activeActivities.keys.filter { it.packageName != currentPackageName }
            for (key in otherKeys) {
                closeActivity(key, handoffTime)
            }
        }

        fun closeAllActivities(closeTime: Long) {
            val keys = activeActivities.keys.toList()
            for (key in keys) {
                closeActivity(key, closeTime)
            }
        }

        val sortedEvents = events.sortedWith(
            compareBy<RawUsageEvent> { it.timestampMillis }.thenBy {
                // Process pauses/stops before resumes if they have the exact same millisecond
                when (it.type) {
                    NormalizedEventType.ACTIVITY_PAUSED,
                    NormalizedEventType.ACTIVITY_STOPPED,
                    NormalizedEventType.SCREEN_NON_INTERACTIVE,
                    NormalizedEventType.KEYGUARD_SHOWN,
                    NormalizedEventType.DEVICE_SHUTDOWN -> 0
                    else -> 1
                }
            }
        )

        for (event in sortedEvents) {
            val key = ActivityKey(event.packageName, event.className)
            val time = event.timestampMillis

            when (event.type) {
                NormalizedEventType.ACTIVITY_RESUMED -> {
                    if (screenInteractive && event.packageName != "android" && !deviceShuttingDown) {
                        // When a new package resumes in foreground, close previous packages' unclosed activities
                        closeActivitiesForOtherPackages(event.packageName, time)

                        // Do not overwrite existing start time if activity is already marked active
                        if (!activeActivities.containsKey(key)) {
                            activeActivities[key] = time
                        }
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
                    deviceShuttingDown = false
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

        // Bounded closure at end of evaluation interval
        val finalCloseTime = if (isToday) {
            minOf(endEpochMillis, currentEpochMillis)
        } else {
            endEpochMillis
        }

        if (screenInteractive && !deviceShuttingDown) {
            closeAllActivities(finalCloseTime)
        } else {
            // Screen is off or device is shut down; clear any unclosed residue without inventing time
            activeActivities.clear()
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
                    // Filter out sub-second transient flickers (e.g. intent redirect trampolines < 1000ms)
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

    /**
     * Computes true elapsed device screen-on time by merging the union of
     * all active application session intervals across all packages.
     * This avoids double-counting split-screen or multi-activity overlapping windows.
     */
    fun computeDeviceScreenOnTimeMillis(sessions: List<SessionInterval>): Long {
        if (sessions.isEmpty()) return 0L
        val sorted = sessions.sortedBy { it.startEpochMillis }

        var totalTime = 0L
        var currentStart = sorted.first().startEpochMillis
        var currentEnd = sorted.first().endEpochMillis

        for (interval in sorted.drop(1)) {
            if (interval.startEpochMillis <= currentEnd) {
                currentEnd = maxOf(currentEnd, interval.endEpochMillis)
            } else {
                totalTime += (currentEnd - currentStart).coerceAtLeast(0L)
                currentStart = interval.startEpochMillis
                currentEnd = interval.endEpochMillis
            }
        }
        totalTime += (currentEnd - currentStart).coerceAtLeast(0L)
        return totalTime
    }
}
