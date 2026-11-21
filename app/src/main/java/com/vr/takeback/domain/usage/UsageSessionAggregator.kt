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

data class InteractiveInterval(
    val startEpochMillis: Long,
    val endEpochMillis: Long
) {
    val durationMillis: Long get() = maxOf(0L, endEpochMillis - startEpochMillis)
}

data class ReconstructionResult(
    val appSessions: List<SessionInterval>,
    val interactiveIntervals: List<InteractiveInterval>,
    val deviceInteractiveTimeMillis: Long,
    val totalTrackedAppUsageMillis: Long,
    val hasIncompleteStartBoundary: Boolean,
    val hasIncompleteEndBoundary: Boolean
)

/**
 * High-precision, deterministic session and interactive-time reconstruction engine.
 *
 * Reconstructs exact calendar-aligned application foreground sessions and
 * device-interactive intervals from raw UsageEvents.
 *
 * ARCHITECTURAL INVARIANTS:
 * 1. Zero Android framework imports - fully testable on JVM with deterministic injected clocks.
 * 2. Multi-resume support: Overlapping foreground intervals from multiple applications (split-screen / multi-window)
 *    are preserved independently. Starting app B does NOT artificially truncate app A.
 * 3. NO arbitrary session caps: Continuous long sessions (e.g. 5+ hours) are never truncated.
 * 4. Distinct metric semantics: Device-Interactive Time (display active/unlocked) is computed
 *    from SCREEN/KEYGUARD events independently of application filters (launcher, settings, and home screen count).
 * 5. Strict calendar interval clipping: All intervals are strictly bounded to [startEpochMillis, endEpochMillis).
 * 6. Duplicate event protection: Repeated ACTIVITY_RESUMED events do not overwrite the initial start time.
 * 7. Screen-off / keyguard enforcement: SCREEN_NON_INTERACTIVE and KEYGUARD_SHOWN cleanly close active sessions.
 * 8. Boundary completeness tracking: Exposes explicit boundary quality flags when history begins mid-session.
 */
object UsageSessionAggregator {

    private data class ActivityKey(
        val packageName: String,
        val className: String?
    )

    /**
     * Reconstructs application foreground sessions for launchable applications.
     */
    fun reconstructSessions(
        events: List<RawUsageEvent>,
        startEpochMillis: Long,
        endEpochMillis: Long,
        isToday: Boolean,
        isUserLaunchableApp: (String) -> Boolean,
        currentEpochMillis: Long = System.currentTimeMillis()
    ): List<SessionInterval> {
        val activeActivities = mutableMapOf<ActivityKey, Long>()
        val rawIntervals = mutableListOf<SessionInterval>()
        var screenInteractive = true
        var deviceShuttingDown = false

        fun closeActivity(key: ActivityKey, closeTime: Long) {
            val started = activeActivities.remove(key) ?: return
            val boundedEnd = minOf(endEpochMillis, closeTime)
            val boundedStart = maxOf(startEpochMillis, started)

            if (boundedEnd > boundedStart && isUserLaunchableApp(key.packageName)) {
                rawIntervals.add(
                    SessionInterval(
                        packageName = key.packageName,
                        startEpochMillis = boundedStart,
                        endEpochMillis = boundedEnd
                    )
                )
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
                        // Multi-resume support: Do NOT close other packages.
                        // Do NOT overwrite existing start time if activity is already marked active.
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
                    activeActivities.clear()
                }

                NormalizedEventType.OTHER -> { /* no-op */ }
            }
        }

        // Bounded closure at end of interval
        val finalCloseTime = if (isToday) {
            minOf(endEpochMillis, currentEpochMillis)
        } else {
            endEpochMillis
        }

        if (screenInteractive && !deviceShuttingDown) {
            closeAllActivities(finalCloseTime)
        } else {
            activeActivities.clear()
        }

        // Merge overlapping or adjacent activity intervals per package
        val mergedSessions = mutableListOf<SessionInterval>()
        rawIntervals
            .groupBy { it.packageName }
            .forEach { (pkg, packageIntervals) ->
                val sorted = packageIntervals.sortedBy { it.startEpochMillis }
                if (sorted.isEmpty()) return@forEach

                var mergedStart = sorted.first().startEpochMillis
                var mergedEnd = sorted.first().endEpochMillis

                fun emitSession() {
                    // Filter sub-second transient flickers (< 1000ms)
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
     * Reconstructs device-interactive intervals based strictly on screen and keyguard transitions.
     * Does NOT filter out launchers, system settings, or system UI.
     */
    fun reconstructDeviceInteractiveIntervals(
        events: List<RawUsageEvent>,
        startEpochMillis: Long,
        endEpochMillis: Long,
        isToday: Boolean,
        currentEpochMillis: Long = System.currentTimeMillis()
    ): List<InteractiveInterval> {
        val rawIntervals = mutableListOf<InteractiveInterval>()
        var isInteractive = false
        var interactiveStart: Long? = null
        var isShutDown = false

        fun closeInteractiveWindow(closeTime: Long) {
            val start = interactiveStart ?: return
            val boundedEnd = minOf(endEpochMillis, closeTime)
            val boundedStart = maxOf(startEpochMillis, start)
            if (boundedEnd > boundedStart) {
                rawIntervals.add(InteractiveInterval(boundedStart, boundedEnd))
            }
            interactiveStart = null
            isInteractive = false
        }

        val sortedEvents = events.sortedWith(
            compareBy<RawUsageEvent> { it.timestampMillis }.thenBy {
                when (it.type) {
                    NormalizedEventType.SCREEN_NON_INTERACTIVE,
                    NormalizedEventType.KEYGUARD_SHOWN,
                    NormalizedEventType.DEVICE_SHUTDOWN -> 0
                    else -> 1
                }
            }
        )

        for (event in sortedEvents) {
            val time = event.timestampMillis

            when (event.type) {
                NormalizedEventType.SCREEN_INTERACTIVE,
                NormalizedEventType.KEYGUARD_HIDDEN -> {
                    if (!isShutDown && !isInteractive) {
                        isInteractive = true
                        interactiveStart = time
                    }
                }

                NormalizedEventType.SCREEN_NON_INTERACTIVE,
                NormalizedEventType.KEYGUARD_SHOWN -> {
                    if (isInteractive) {
                        closeInteractiveWindow(time)
                    }
                }

                NormalizedEventType.DEVICE_SHUTDOWN -> {
                    isShutDown = true
                    if (isInteractive) {
                        closeInteractiveWindow(time)
                    }
                }

                NormalizedEventType.DEVICE_STARTUP -> {
                    isShutDown = false
                    isInteractive = false
                    interactiveStart = null
                }

                else -> { /* Ignore app lifecycle for device-level interactive state */ }
            }
        }

        // Bounded closure at end of interval
        val finalClose = if (isToday) {
            minOf(endEpochMillis, currentEpochMillis)
        } else {
            endEpochMillis
        }

        if (isInteractive && !isShutDown) {
            closeInteractiveWindow(finalClose)
        }

        // Merge contiguous / overlapping interactive slices
        val sorted = rawIntervals.sortedBy { it.startEpochMillis }
        if (sorted.isEmpty()) return emptyList()

        val merged = mutableListOf<InteractiveInterval>()
        var curStart = sorted.first().startEpochMillis
        var curEnd = sorted.first().endEpochMillis

        for (interval in sorted.drop(1)) {
            if (interval.startEpochMillis <= curEnd) {
                curEnd = maxOf(curEnd, interval.endEpochMillis)
            } else {
                if (curEnd - curStart >= 1000L) {
                    merged.add(InteractiveInterval(curStart, curEnd))
                }
                curStart = interval.startEpochMillis
                curEnd = interval.endEpochMillis
            }
        }
        if (curEnd - curStart >= 1000L) {
            merged.add(InteractiveInterval(curStart, curEnd))
        }

        return merged
    }

    /**
     * Computes the total duration in milliseconds of an interactive interval list.
     */
    fun computeTotalInteractiveDuration(intervals: List<InteractiveInterval>): Long {
        return intervals.sumOf { it.durationMillis }
    }

    /**
     * Computes full reconstruction output with metrics and boundary quality detection.
     */
    fun reconstructFull(
        events: List<RawUsageEvent>,
        startEpochMillis: Long,
        endEpochMillis: Long,
        isToday: Boolean,
        isUserLaunchableApp: (String) -> Boolean,
        currentEpochMillis: Long = System.currentTimeMillis()
    ): ReconstructionResult {
        val appSessions = reconstructSessions(
            events = events,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            isToday = isToday,
            isUserLaunchableApp = isUserLaunchableApp,
            currentEpochMillis = currentEpochMillis
        )

        val interactiveIntervals = reconstructDeviceInteractiveIntervals(
            events = events,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            isToday = isToday,
            currentEpochMillis = currentEpochMillis
        )

        // If screen interactive intervals were observed, use them as device interactive time.
        // Fallback: If device screen events (15/16) are not dispatched by the OEM, use the
        // union of foreground app sessions as a lower-bound fallback.
        val interactiveDuration = computeTotalInteractiveDuration(interactiveIntervals)
        val appUnionDuration = computeAppSessionUnionMillis(appSessions)
        val effectiveInteractiveTime = if (interactiveDuration > 0L) {
            maxOf(interactiveDuration, appUnionDuration)
        } else {
            appUnionDuration
        }

        val totalTrackedAppUsage = appSessions.sumOf { it.durationMillis }

        // Boundary analysis
        val firstEventTime = events.minOfOrNull { it.timestampMillis }
        val hasIncompleteStart = firstEventTime != null && firstEventTime > (startEpochMillis + 60_000L) &&
                events.any { it.timestampMillis < (startEpochMillis + 180_000L) && (it.type == NormalizedEventType.ACTIVITY_PAUSED || it.type == NormalizedEventType.ACTIVITY_STOPPED) }

        return ReconstructionResult(
            appSessions = appSessions,
            interactiveIntervals = interactiveIntervals,
            deviceInteractiveTimeMillis = effectiveInteractiveTime,
            totalTrackedAppUsageMillis = totalTrackedAppUsage,
            hasIncompleteStartBoundary = hasIncompleteStart,
            hasIncompleteEndBoundary = false
        )
    }

    /**
     * Computes union of application session intervals across all packages.
     */
    fun computeAppSessionUnionMillis(sessions: List<SessionInterval>): Long {
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
