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

enum class InitialState {
    KNOWN_INTERACTIVE,
    KNOWN_NON_INTERACTIVE,
    UNKNOWN
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
    val deviceInteractiveTimeMillis: Long?,
    val isDeviceInteractiveAvailable: Boolean,
    val totalTrackedAppUsageMillis: Long,
    val hasIncompleteStartBoundary: Boolean,
    val hasIncompleteEndBoundary: Boolean
)

/**
 * High-precision, deterministic session and interactive-time reconstruction engine.
 *
 * CRITICAL ARCHITECTURAL RULES:
 * 1. Zero Android framework imports - 100% deterministic JVM execution with injected clocks.
 * 2. NO fallback to app sessions: Device-interactive time is ONLY calculated from genuine screen
 *    transitions. If screen-state events are unavailable, it is marked unavailable.
 * 3. Correct KEYGUARD handling: SCREEN_INTERACTIVE/SCREEN_NON_INTERACTIVE govern screen interactivity.
 *    KEYGUARD_SHOWN/KEYGUARD_HIDDEN track lockscreen state separately and do NOT independently stop
 *    or start device-interactive intervals. KEYGUARD_SHOWN bounds application sessions.
 * 4. Multi-resume support: Independent per-package foreground tracking allows split-screen / multi-window.
 * 5. Explicit initial state: Preceding events in lookback determine KNOWN_INTERACTIVE, KNOWN_NON_INTERACTIVE,
 *    or UNKNOWN. UNKNOWN states produce explicit boundary warnings rather than silent assumptions.
 * 6. NO arbitrary session caps: Continuous sessions (even 12+ hours) are never artificially chopped.
 */
object UsageSessionAggregator {

    private data class ActivityKey(
        val packageName: String,
        val className: String?
    )

    /**
     * Determines initial screen state at startEpochMillis by inspecting preceding events in lookback.
     */
    fun determineInitialScreenState(
        events: List<RawUsageEvent>,
        startEpochMillis: Long
    ): InitialState {
        val precedingScreenEvents = events
            .filter { it.timestampMillis < startEpochMillis }
            .filter {
                it.type == NormalizedEventType.SCREEN_INTERACTIVE ||
                        it.type == NormalizedEventType.SCREEN_NON_INTERACTIVE ||
                        it.type == NormalizedEventType.DEVICE_SHUTDOWN ||
                        it.type == NormalizedEventType.DEVICE_STARTUP
            }
            .sortedBy { it.timestampMillis }

        val lastEvent = precedingScreenEvents.lastOrNull() ?: return InitialState.UNKNOWN

        return when (lastEvent.type) {
            NormalizedEventType.SCREEN_INTERACTIVE -> InitialState.KNOWN_INTERACTIVE
            NormalizedEventType.SCREEN_NON_INTERACTIVE,
            NormalizedEventType.DEVICE_SHUTDOWN -> InitialState.KNOWN_NON_INTERACTIVE
            NormalizedEventType.DEVICE_STARTUP -> InitialState.KNOWN_NON_INTERACTIVE
            else -> InitialState.UNKNOWN
        }
    }

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
        var keyguardShowing = false
        var deviceShuttingDown = false

        // 1. Inspect preceding events in lookback to find activities already open at startEpochMillis
        val precedingEvents = events
            .filter { it.timestampMillis < startEpochMillis }
            .sortedWith(compareBy<RawUsageEvent> { it.timestampMillis }.thenBy { it.type.ordinal })

        for (event in precedingEvents) {
            val key = ActivityKey(event.packageName, event.className)
            when (event.type) {
                NormalizedEventType.ACTIVITY_RESUMED -> {
                    if (screenInteractive && !keyguardShowing && !deviceShuttingDown && event.packageName != "android") {
                        activeActivities[key] = event.timestampMillis
                    }
                }
                NormalizedEventType.ACTIVITY_PAUSED,
                NormalizedEventType.ACTIVITY_STOPPED -> {
                    activeActivities.remove(key)
                }
                NormalizedEventType.SCREEN_NON_INTERACTIVE -> {
                    screenInteractive = false
                    activeActivities.clear()
                }
                NormalizedEventType.SCREEN_INTERACTIVE -> {
                    screenInteractive = true
                    deviceShuttingDown = false
                }
                NormalizedEventType.KEYGUARD_SHOWN -> {
                    keyguardShowing = true
                    activeActivities.clear()
                }
                NormalizedEventType.KEYGUARD_HIDDEN -> {
                    keyguardShowing = false
                }
                NormalizedEventType.DEVICE_SHUTDOWN -> {
                    deviceShuttingDown = true
                    activeActivities.clear()
                }
                NormalizedEventType.DEVICE_STARTUP -> {
                    deviceShuttingDown = false
                    activeActivities.clear()
                }
                else -> { /* no-op */ }
            }
        }

        // For any activities that were already active at midnight, their effective start for today is startEpochMillis
        val activeKeysAtMidnight = activeActivities.keys.toList()
        for (key in activeKeysAtMidnight) {
            activeActivities[key] = startEpochMillis
        }

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

        // 2. Process events within the query interval
        val intervalEvents = events
            .filter { it.timestampMillis >= startEpochMillis }
            .sortedWith(
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

        for (event in intervalEvents) {
            val key = ActivityKey(event.packageName, event.className)
            val time = event.timestampMillis

            when (event.type) {
                NormalizedEventType.ACTIVITY_RESUMED -> {
                    if (screenInteractive && !keyguardShowing && !deviceShuttingDown && event.packageName != "android") {
                        if (!activeActivities.containsKey(key)) {
                            activeActivities[key] = time
                        }
                    }
                }

                NormalizedEventType.ACTIVITY_PAUSED,
                NormalizedEventType.ACTIVITY_STOPPED -> {
                    closeActivity(key, time)
                }

                NormalizedEventType.SCREEN_NON_INTERACTIVE -> {
                    screenInteractive = false
                    closeAllActivities(time)
                }

                NormalizedEventType.SCREEN_INTERACTIVE -> {
                    screenInteractive = true
                    deviceShuttingDown = false
                }

                NormalizedEventType.KEYGUARD_SHOWN -> {
                    keyguardShowing = true
                    closeAllActivities(time)
                }

                NormalizedEventType.KEYGUARD_HIDDEN -> {
                    keyguardShowing = false
                }

                NormalizedEventType.DEVICE_SHUTDOWN -> {
                    deviceShuttingDown = true
                    closeAllActivities(time)
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

        if (screenInteractive && !keyguardShowing && !deviceShuttingDown) {
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
     * Reconstructs device-interactive intervals based strictly on screen transitions.
     * KEYGUARD transitions do NOT start or stop interactive intervals.
     */
    fun reconstructDeviceInteractiveIntervals(
        events: List<RawUsageEvent>,
        startEpochMillis: Long,
        endEpochMillis: Long,
        isToday: Boolean,
        currentEpochMillis: Long = System.currentTimeMillis()
    ): Pair<List<InteractiveInterval>, Boolean> {
        val rawIntervals = mutableListOf<InteractiveInterval>()
        val initialState = determineInitialScreenState(events, startEpochMillis)
        var hasIncompleteStart = (initialState == InitialState.UNKNOWN)

        var isInteractive = (initialState == InitialState.KNOWN_INTERACTIVE)
        var interactiveStart: Long? = if (isInteractive) startEpochMillis else null
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

        val intervalEvents = events
            .filter { it.timestampMillis >= startEpochMillis }
            .sortedWith(
                compareBy<RawUsageEvent> { it.timestampMillis }.thenBy {
                    when (it.type) {
                        NormalizedEventType.SCREEN_NON_INTERACTIVE,
                        NormalizedEventType.DEVICE_SHUTDOWN -> 0
                        else -> 1
                    }
                }
            )

        for (event in intervalEvents) {
            val time = event.timestampMillis

            when (event.type) {
                NormalizedEventType.SCREEN_INTERACTIVE -> {
                    if (!isShutDown && !isInteractive) {
                        isInteractive = true
                        interactiveStart = time
                    }
                }

                NormalizedEventType.SCREEN_NON_INTERACTIVE -> {
                    if (isInteractive) {
                        closeInteractiveWindow(time)
                    } else if (initialState == InitialState.UNKNOWN) {
                        // Requirement 2: Do NOT generate an exact numeric interactive interval from an unknown initial state.
                        hasIncompleteStart = true
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

                NormalizedEventType.KEYGUARD_SHOWN,
                NormalizedEventType.KEYGUARD_HIDDEN -> {
                    // Requirement 2: Keyguard transitions must not independently start or stop device-interactive intervals
                }

                else -> { /* Ignore app lifecycle */ }
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
        if (sorted.isEmpty()) return Pair(emptyList(), hasIncompleteStart)

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

        return Pair(merged, hasIncompleteStart)
    }

    /**
     * Computes full reconstruction output with explicit boundary completeness detection.
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

        val (interactiveIntervals, hasIncompleteInteractiveStart) = reconstructDeviceInteractiveIntervals(
            events = events,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            isToday = isToday,
            currentEpochMillis = currentEpochMillis
        )

        // Check whether screen events actually exist in the event buffer
        val hasScreenEvents = events.any {
            it.type == NormalizedEventType.SCREEN_INTERACTIVE ||
                    it.type == NormalizedEventType.SCREEN_NON_INTERACTIVE
        }

        // Requirement 1 & 2: NEVER substitute appUnionDuration for deviceInteractiveTimeMillis.
        // If initial state was unknown and no verified screen-on intervals exist, device interactive time is unavailable.
        val deviceInteractiveTime = if (hasScreenEvents) {
            if (interactiveIntervals.isNotEmpty()) {
                interactiveIntervals.sumOf { it.durationMillis }
            } else if (!hasIncompleteInteractiveStart) {
                0L // Verified zero: screen was known non-interactive at start and never turned on
            } else {
                null // Unknown initial state and no verified interactive intervals observed
            }
        } else {
            null
        }

        val totalTrackedAppUsage = appSessions.sumOf { it.durationMillis }

        // Ending boundary completeness analysis:
        // For past days, did any session or interactive interval remain open at endEpochMillis
        // without an observed closing event in subsequent lookahead?
        var hasIncompleteEnd = false
        if (!isToday) {
            val openAppAtEnd = appSessions.any { it.endEpochMillis >= endEpochMillis }
            val subsequentEvents = events.filter { it.timestampMillis >= endEpochMillis }
            if (openAppAtEnd && subsequentEvents.none { it.type == NormalizedEventType.ACTIVITY_PAUSED || it.type == NormalizedEventType.ACTIVITY_STOPPED || it.type == NormalizedEventType.SCREEN_NON_INTERACTIVE }) {
                hasIncompleteEnd = true
            }
        }

        return ReconstructionResult(
            appSessions = appSessions,
            interactiveIntervals = interactiveIntervals,
            deviceInteractiveTimeMillis = deviceInteractiveTime,
            isDeviceInteractiveAvailable = (deviceInteractiveTime != null),
            totalTrackedAppUsageMillis = totalTrackedAppUsage,
            hasIncompleteStartBoundary = hasIncompleteInteractiveStart,
            hasIncompleteEndBoundary = hasIncompleteEnd
        )
    }
}
