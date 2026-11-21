package com.vr.takeback.data.repository

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import com.vr.takeback.domain.model.AppUsageInfo
import com.vr.takeback.domain.usage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class AppUsageSession(
    val packageName: String,
    val appName: String,
    val startTimeEpochMillis: Long,
    val endTimeEpochMillis: Long,
    val durationMillis: Long
) {
    val durationFormatted: String
        get() {
            val minutes = durationMillis / (1000 * 60)
            val seconds = (durationMillis % (1000 * 60)) / 1000
            return when {
                minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
                minutes > 0 -> "${minutes}m"
                else -> "${seconds}s"
            }
        }

    val railwayTimeRange: String
        get() {
            val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
            val startStr = formatter.format(Instant.ofEpochMilli(startTimeEpochMillis))
            val endStr = formatter.format(Instant.ofEpochMilli(endTimeEpochMillis))
            return "$startStr - $endStr"
        }
}

data class DayUsageOverview(
    val date: LocalDate,
    val dayName: String,
    val totalTimeMillis: Long,
    val isToday: Boolean,
    val isSelected: Boolean
)

data class DailyUsageSummary(
    val selectedDate: LocalDate = LocalDate.now(),
    val totalTimeMillis: Long,
    val deviceScreenTimeMillis: Long = totalTimeMillis,
    val topApps: List<AppUsageInfo>,
    val sessions: List<AppUsageSession>,
    val weeklyOverview: List<DayUsageOverview>,
    val isRealDataAvailable: Boolean,
    val diagnostics: UsageDiagnostics? = null
)

class UsageStatsRepository(private val context: Context) {

    companion object {
        const val TAG = "UsageStatsRepository"
        const val DIAG_TAG = "TakeBackUsageDiag"
        private const val KEY_DAILY_ALLOWANCE = "daily_allowance_minutes"
        private const val DEFAULT_ALLOWANCE_MINUTES = 120 // 2 hours

        val SYSTEM_EXCLUDED_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.google.android.gms",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.vr.takeback"
        )
    }

    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val packageManager = context.packageManager
    private val allowancePrefs = context.getSharedPreferences("takeback_allowance_prefs", Context.MODE_PRIVATE)

    @Volatile
    private var cachedLaunchablePackages: Set<String>? = null

    @Volatile
    private var latestDiagnostics: UsageDiagnostics? = null

    fun getLatestDiagnostics(): UsageDiagnostics? = latestDiagnostics

    fun getDailyAllowanceMinutes(): Int {
        return allowancePrefs.getInt(KEY_DAILY_ALLOWANCE, DEFAULT_ALLOWANCE_MINUTES)
    }

    fun setDailyAllowanceMinutes(minutes: Int) {
        allowancePrefs.edit().putInt(KEY_DAILY_ALLOWANCE, minutes).apply()
    }

    @Suppress("DEPRECATION")
    fun hasUsagePermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun getLaunchablePackages(): Set<String> {
        val cached = cachedLaunchablePackages
        if (cached != null) return cached
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launchable = packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
            cachedLaunchablePackages = launchable
            launchable
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query launchable packages", e)
            emptySet()
        }
    }

    fun invalidatePackageCache() {
        cachedLaunchablePackages = null
    }

    fun isUserLaunchableApp(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (SYSTEM_EXCLUDED_PACKAGES.contains(packageName)) return false

        // Check launcher activities first
        val launchable = getLaunchablePackages()
        if (launchable.contains(packageName)) return true

        // Fallback: check if package is a user-installed app or has launch intent
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val isUserInstalled = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val hasLaunchIntent = packageManager.getLaunchIntentForPackage(packageName) != null
            isUserInstalled || hasLaunchIntent
        } catch (e: Exception) {
            false
        }
    }

    data class UsageSnapshot(
        val date: LocalDate,
        val capturedAtMillis: Long,
        val foregroundUsageByPackage: Map<String, Long>
    )

    @Volatile
    private var cachedUsageSnapshot: UsageSnapshot? = null

    fun getCachedUsageSnapshot(): Map<String, Long> {
        val today = LocalDate.now()
        val snapshot = cachedUsageSnapshot
        return if (snapshot != null && snapshot.date == today) {
            snapshot.foregroundUsageByPackage
        } else {
            emptyMap()
        }
    }

    /**
     * Refreshes the active today usage snapshot for CentralRestrictionEngine.
     * Reconstructs true foreground usage strictly from midnight 00:00:00 to now,
     * preventing pre-midnight usage in unrotated OS daily buckets from triggering limits prematurely.
     */
    suspend fun refreshUsageSnapshot() = withContext(Dispatchers.IO) {
        if (!hasUsagePermission() || usageStatsManager == null) {
            cachedUsageSnapshot = null
            return@withContext
        }
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

        val intervals = extractRawIntervals(startOfDay, now, isToday = true)
        val usageMap = intervals
            .groupBy { it.packageName }
            .mapValues { (_, list) -> list.sumOf { it.durationMillis } }

        cachedUsageSnapshot = UsageSnapshot(
            date = LocalDate.now(zone),
            capturedAtMillis = now,
            foregroundUsageByPackage = usageMap
        )
    }

    suspend fun getTodayUsageSummary(): DailyUsageSummary = withContext(Dispatchers.IO) {
        getUsageSummaryForDate(LocalDate.now())
    }

    suspend fun getUsageSummaryForDate(targetDate: LocalDate): DailyUsageSummary = withContext(Dispatchers.IO) {
        val hasPermission = hasUsagePermission()
        if (!hasPermission || usageStatsManager == null) {
            return@withContext DailyUsageSummary(
                selectedDate = targetDate,
                totalTimeMillis = 0L,
                deviceScreenTimeMillis = 0L,
                topApps = emptyList(),
                sessions = emptyList(),
                weeklyOverview = emptyList(),
                isRealDataAvailable = false,
                diagnostics = buildErrorDiagnostics(targetDate, "Usage access permission not granted or service unavailable")
            )
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startOfDay = targetDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusive = if (targetDate == today) {
            System.currentTimeMillis()
        } else {
            targetDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }

        if (endExclusive <= startOfDay) {
            return@withContext DailyUsageSummary(
                selectedDate = targetDate,
                totalTimeMillis = 0L,
                deviceScreenTimeMillis = 0L,
                topApps = emptyList(),
                sessions = emptyList(),
                weeklyOverview = emptyList(),
                isRealDataAvailable = true,
                diagnostics = null
            )
        }

        val queryStart = maxOf(0L, startOfDay - 24L * 60L * 60L * 1000L)
        val rawEvents = fetchRawUsageEvents(queryStart, endExclusive)

        // Reconstruct sessions with deterministic time injection
        val sessionIntervals = UsageSessionAggregator.reconstructSessions(
            events = rawEvents,
            startEpochMillis = startOfDay,
            endEpochMillis = endExclusive,
            isToday = (targetDate == today),
            isUserLaunchableApp = ::isUserLaunchableApp,
            currentEpochMillis = System.currentTimeMillis()
        )

        // True interactive screen-on time (union of all app intervals)
        val screenOnTime = UsageSessionAggregator.computeDeviceScreenOnTimeMillis(sessionIntervals)

        // Map to presentation sessions with resolved application labels
        val sessions = sessionIntervals.map { interval ->
            AppUsageSession(
                packageName = interval.packageName,
                appName = resolveAppLabel(interval.packageName),
                startTimeEpochMillis = interval.startEpochMillis,
                endTimeEpochMillis = interval.endEpochMillis,
                durationMillis = interval.durationMillis
            )
        }

        // Calculate per-application daily totals strictly from reconstructed sessions
        val sessionsByPackage = sessions.groupBy { it.packageName }
        val allApps = sessionsByPackage.map { (pkg, appSessions) ->
            val totalAppTime = appSessions.sumOf { it.durationMillis }
            val lastTimeUsed = appSessions.maxOfOrNull { it.endTimeEpochMillis } ?: 0L
            AppUsageInfo(
                packageName = pkg,
                appName = resolveAppLabel(pkg),
                usageTimeMillis = totalAppTime,
                launchCount = appSessions.size,
                lastTimeUsedMillis = lastTimeUsed,
                isBlocked = false
            )
        }.sortedByDescending { it.usageTimeMillis }

        // Sum of all tracked apps foreground usage
        val totalAppTime = allApps.sumOf { it.usageTimeMillis }
        val topApps = allApps.take(25)

        // Build weekly overview
        val monday = targetDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeklyOverview = (0..6).map { offset ->
            val day = monday.plusDays(offset.toLong())
            val dayTotal = when {
                day.isAfter(today) -> 0L
                day == targetDate -> screenOnTime
                else -> {
                    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
                    val dayEnd = if (day == today) {
                        System.currentTimeMillis()
                    } else {
                        day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    }
                    computeScreenOnTimeForWindow(dayStart, dayEnd, day == today)
                }
            }

            DayUsageOverview(
                date = day,
                dayName = day.dayOfWeek.name.take(3),
                totalTimeMillis = dayTotal,
                isToday = (day == today),
                isSelected = (day == targetDate)
            )
        }

        // Collect comprehensive diagnostics
        val diagnostics = collectDiagnostics(
            targetDate = targetDate,
            startOfDay = startOfDay,
            endExclusive = endExclusive,
            rawEvents = rawEvents,
            sessions = sessionIntervals,
            screenOnTime = screenOnTime,
            totalAppTime = totalAppTime,
            perAppUsage = allApps.associate { it.packageName to it.usageTimeMillis },
            perAppLaunches = allApps.associate { it.packageName to it.launchCount }
        )
        latestDiagnostics = diagnostics

        Log.i(DIAG_TAG, diagnostics.toFormattedReport())

        DailyUsageSummary(
            selectedDate = targetDate,
            totalTimeMillis = totalAppTime,
            deviceScreenTimeMillis = screenOnTime,
            topApps = topApps,
            sessions = sessions,
            weeklyOverview = weeklyOverview,
            isRealDataAvailable = true,
            diagnostics = diagnostics
        )
    }

    private fun extractRawIntervals(
        startEpochMillis: Long,
        endEpochMillis: Long,
        isToday: Boolean
    ): List<SessionInterval> {
        val queryStart = maxOf(0L, startEpochMillis - 24L * 60L * 60L * 1000L)
        val events = fetchRawUsageEvents(queryStart, endEpochMillis)
        return UsageSessionAggregator.reconstructSessions(
            events = events,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            isToday = isToday,
            isUserLaunchableApp = ::isUserLaunchableApp,
            currentEpochMillis = System.currentTimeMillis()
        )
    }

    private fun fetchRawUsageEvents(queryStart: Long, queryEnd: Long): List<RawUsageEvent> {
        val manager = usageStatsManager ?: return emptyList()
        val events = try {
            manager.queryEvents(queryStart, queryEnd) ?: return emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read usage events from $queryStart to $queryEnd", e)
            return emptyList()
        }

        val rawList = mutableListOf<RawUsageEvent>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val time = event.timeStamp
            val className = event.className

            val normalizedType = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, 1 -> NormalizedEventType.ACTIVITY_RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED, 2 -> NormalizedEventType.ACTIVITY_PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> NormalizedEventType.ACTIVITY_STOPPED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, 16 -> NormalizedEventType.SCREEN_NON_INTERACTIVE
                UsageEvents.Event.SCREEN_INTERACTIVE, 15 -> NormalizedEventType.SCREEN_INTERACTIVE
                UsageEvents.Event.KEYGUARD_SHOWN, 17 -> NormalizedEventType.KEYGUARD_SHOWN
                UsageEvents.Event.KEYGUARD_HIDDEN, 18 -> NormalizedEventType.KEYGUARD_HIDDEN
                UsageEvents.Event.DEVICE_SHUTDOWN -> NormalizedEventType.DEVICE_SHUTDOWN
                UsageEvents.Event.DEVICE_STARTUP -> NormalizedEventType.DEVICE_STARTUP
                else -> NormalizedEventType.OTHER
            }

            rawList.add(
                RawUsageEvent(
                    packageName = pkg,
                    className = className,
                    timestampMillis = time,
                    type = normalizedType
                )
            )
        }
        return rawList
    }

    private fun computeScreenOnTimeForWindow(start: Long, end: Long, isToday: Boolean): Long {
        val intervals = extractRawIntervals(start, end, isToday)
        return UsageSessionAggregator.computeDeviceScreenOnTimeMillis(intervals)
    }

    private fun collectDiagnostics(
        targetDate: LocalDate,
        startOfDay: Long,
        endExclusive: Long,
        rawEvents: List<RawUsageEvent>,
        sessions: List<SessionInterval>,
        screenOnTime: Long,
        totalAppTime: Long,
        perAppUsage: Map<String, Long>,
        perAppLaunches: Map<String, Int>
    ): UsageDiagnostics {
        val zone = ZoneId.systemDefault()
        val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)
        val now = System.currentTimeMillis()

        // 1. Query queryAndAggregateUsageStats (wrap into HashMap because Android ArrayMap throws UnsupportedOperationException on toArray)
        val aggMap: Map<String, UsageStats> = try {
            val raw = usageStatsManager?.queryAndAggregateUsageStats(startOfDay, endExclusive)
            if (raw != null) HashMap(raw) else emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
        val aggTotal = aggMap.values.sumOf { it.totalTimeInForeground }
        val aggTop = aggMap.entries
            .sortedByDescending { it.value.totalTimeInForeground }
            .take(15)
            .map { (pkg, stat) ->
                val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) stat.totalTimeVisible else 0L
                PackageRawStat(
                    packageName = pkg,
                    totalTimeInForegroundMillis = stat.totalTimeInForeground,
                    totalTimeVisibleMillis = visible,
                    lastTimeUsedMillis = stat.lastTimeUsed
                )
            }

        // 2. Query queryUsageStats(INTERVAL_DAILY)
        val dailyStats = try {
            usageStatsManager?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, endExclusive).orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        val dailyIntervalTotal = dailyStats.sumOf { it.totalTimeInForeground }
        val dailyBuckets = dailyStats.groupBy { "${it.firstTimeStamp}_${it.lastTimeStamp}" }.map { (_, list) ->
            val first = list.first()
            DailyBucketInfo(
                beginTimestamp = first.firstTimeStamp,
                endTimestamp = first.lastTimeStamp,
                beginFormatted = dtf.format(Instant.ofEpochMilli(first.firstTimeStamp)),
                endFormatted = dtf.format(Instant.ofEpochMilli(first.lastTimeStamp)),
                totalForegroundMillis = list.sumOf { it.totalTimeInForeground },
                packageCount = list.size
            )
        }

        // 3. Filter decisions for all observed packages
        val allSeenPackages = mutableSetOf<String>()
        allSeenPackages.addAll(aggMap.keys)
        allSeenPackages.addAll(dailyStats.map { it.packageName })
        allSeenPackages.addAll(rawEvents.map { it.packageName })

        val filterDecisions = mutableMapOf<String, String>()
        var eligibleCount = 0
        var excludedCount = 0

        for (pkg in allSeenPackages) {
            val decision = when {
                SYSTEM_EXCLUDED_PACKAGES.contains(pkg) -> "EXCLUDED: Explicit system framework component"
                pkg.isBlank() -> "EXCLUDED: Empty package name"
                !isUserLaunchableApp(pkg) -> "EXCLUDED: Background system service/daemon without launcher activity"
                else -> {
                    eligibleCount++
                    "INCLUDED: User launchable application"
                }
            }
            if (decision.startsWith("EXCLUDED")) excludedCount++
            filterDecisions[pkg] = decision
        }

        val firstEventTime = rawEvents.firstOrNull()?.timestampMillis?.let { dtf.format(Instant.ofEpochMilli(it)) }
        val lastEventTime = rawEvents.lastOrNull()?.timestampMillis?.let { dtf.format(Instant.ofEpochMilli(it)) }

        return UsageDiagnostics(
            queryInfo = QueryMetadata(
                targetDate = targetDate,
                timeZoneId = zone.id,
                startEpochMillis = startOfDay,
                startFormatted = dtf.format(Instant.ofEpochMilli(startOfDay)),
                endEpochMillis = endExclusive,
                endFormatted = dtf.format(Instant.ofEpochMilli(endExclusive)),
                queryExecutedEpochMillis = now,
                queryExecutedFormatted = dtf.format(Instant.ofEpochMilli(now)),
                apiLevel = Build.VERSION.SDK_INT,
                hasUsagePermission = true,
                isSuccess = true
            ),
            rawOsResults = RawOsResults(
                queryAndAggregateTotalMillis = aggTotal,
                queryAndAggregatePackageCount = aggMap.size,
                queryAndAggregateTopPackages = aggTop,
                dailyBucketCount = dailyBuckets.size,
                dailyIntervalTotalMillis = dailyIntervalTotal,
                dailyBuckets = dailyBuckets,
                eventCount = rawEvents.size,
                firstEventTimestampFormatted = firstEventTime,
                lastEventTimestampFormatted = lastEventTime
            ),
            filterDiagnostics = FilterDiagnostics(
                totalPackagesEvaluated = allSeenPackages.size,
                eligiblePackagesCount = eligibleCount,
                excludedPackagesCount = excludedCount,
                filterDecisions = filterDecisions
            ),
            calculationDiagnostics = CalculationDiagnostics(
                totalReconstructedSessions = sessions.size,
                totalAppUsageMillis = totalAppTime,
                deviceScreenOnTimeMillis = screenOnTime,
                perAppUsage = perAppUsage,
                perAppLaunchCounts = perAppLaunches,
                snapshotTimestampMillis = now
            )
        )
    }

    private fun buildErrorDiagnostics(targetDate: LocalDate, message: String): UsageDiagnostics {
        val zone = ZoneId.systemDefault()
        val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)
        val now = System.currentTimeMillis()
        val startOfDay = targetDate.atStartOfDay(zone).toInstant().toEpochMilli()

        return UsageDiagnostics(
            queryInfo = QueryMetadata(
                targetDate = targetDate,
                timeZoneId = zone.id,
                startEpochMillis = startOfDay,
                startFormatted = dtf.format(Instant.ofEpochMilli(startOfDay)),
                endEpochMillis = now,
                endFormatted = dtf.format(Instant.ofEpochMilli(now)),
                queryExecutedEpochMillis = now,
                queryExecutedFormatted = dtf.format(Instant.ofEpochMilli(now)),
                apiLevel = Build.VERSION.SDK_INT,
                hasUsagePermission = false,
                isSuccess = false,
                errorMessage = message
            ),
            rawOsResults = RawOsResults(0L, 0, emptyList(), 0, 0L, emptyList(), 0, null, null),
            filterDiagnostics = FilterDiagnostics(0, 0, 0, emptyMap()),
            calculationDiagnostics = CalculationDiagnostics(0, 0L, 0L, emptyMap(), emptyMap(), now)
        )
    }

    fun resolveAppLabel(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }
}
