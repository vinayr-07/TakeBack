package com.vr.takeback.data.repository

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import com.vr.takeback.domain.model.AppUsageInfo
import com.vr.takeback.domain.usage.NormalizedEventType
import com.vr.takeback.domain.usage.RawUsageEvent
import com.vr.takeback.domain.usage.UsageSessionAggregator
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
    val topApps: List<AppUsageInfo>,
    val sessions: List<AppUsageSession>,
    val weeklyOverview: List<DayUsageOverview>,
    val isRealDataAvailable: Boolean
)

class UsageStatsRepository(private val context: Context) {

    companion object {
        private const val TAG = "UsageStatsRepository"
        private const val KEY_DAILY_ALLOWANCE = "daily_allowance_minutes"
        private const val DEFAULT_ALLOWANCE_MINUTES = 120 // 2 hours

        private val SYSTEM_EXCLUDED_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.google.android.gms",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.android.phone",
            "com.android.server.telecom",
            "com.vr.takeback"
        )
    }

    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val packageManager = context.packageManager
    private val allowancePrefs = context.getSharedPreferences("takeback_allowance_prefs", Context.MODE_PRIVATE)

    @Volatile
    private var cachedLaunchablePackages: Set<String>? = null

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
            emptySet()
        }
    }

    fun invalidatePackageCache() {
        cachedLaunchablePackages = null
    }

    private fun isUserLaunchableApp(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (SYSTEM_EXCLUDED_PACKAGES.contains(packageName)) return false
        return getLaunchablePackages().contains(packageName)
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

    suspend fun refreshUsageSnapshot() = withContext(Dispatchers.IO) {
        if (!hasUsagePermission() || usageStatsManager == null) {
            cachedUsageSnapshot = null
            return@withContext
        }
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val stats = try {
            usageStatsManager.queryAndAggregateUsageStats(start, now).orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to refresh usage snapshot", e)
            return@withContext
        }
        val usage = stats.filter { (pkg, stat) ->
            isUserLaunchableApp(pkg) && stat.totalTimeInForeground >= 0L
        }.mapValues { (_, stat) -> stat.totalTimeInForeground }

        cachedUsageSnapshot = UsageSnapshot(
            date = LocalDate.now(zone),
            capturedAtMillis = now,
            foregroundUsageByPackage = usage
        )
    }

    suspend fun getTodayUsageSummary(): DailyUsageSummary = withContext(Dispatchers.IO) {
        getUsageSummaryForDate(LocalDate.now())
    }

    suspend fun getUsageSummaryForDate(targetDate: LocalDate): DailyUsageSummary = withContext(Dispatchers.IO) {
        if (!hasUsagePermission() || usageStatsManager == null) {
            return@withContext DailyUsageSummary(
                selectedDate = targetDate,
                totalTimeMillis = 0L,
                topApps = emptyList(),
                sessions = emptyList(),
                weeklyOverview = emptyList(),
                isRealDataAvailable = false
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
                topApps = emptyList(),
                sessions = emptyList(),
                weeklyOverview = emptyList(),
                isRealDataAvailable = true
            )
        }

        val statsMap = try {
            usageStatsManager.queryAndAggregateUsageStats(startOfDay, endExclusive).orEmpty()
        } catch (e: SecurityException) {
            Log.e(TAG, "Usage access revoked", e)
            return@withContext DailyUsageSummary(
                selectedDate = targetDate,
                totalTimeMillis = 0L,
                topApps = emptyList(),
                sessions = emptyList(),
                weeklyOverview = emptyList(),
                isRealDataAvailable = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Usage query failed", e)
            return@withContext DailyUsageSummary(
                selectedDate = targetDate,
                totalTimeMillis = 0L,
                topApps = emptyList(),
                sessions = emptyList(),
                weeklyOverview = emptyList(),
                isRealDataAvailable = false
            )
        }

        // Chronological session history via activity state machine
        val sessions = extractSessionsFromEvents(startOfDay, endExclusive, targetDate == today)

        // Android aggregate is the sole authoritative baseline for displayed daily usage durations
        val allApps = statsMap.entries
            .asSequence()
            .filter { (pkg, stat) -> isUserLaunchableApp(pkg) && stat.totalTimeInForeground > 0L }
            .map { (pkg, stat) ->
                val appSessions = sessions.filter { it.packageName == pkg }
                val lastTime = appSessions.maxOfOrNull { it.endTimeEpochMillis }
                    ?: stat.lastTimeUsed
                AppUsageInfo(
                    packageName = pkg,
                    appName = resolveAppLabel(pkg),
                    usageTimeMillis = stat.totalTimeInForeground,
                    launchCount = appSessions.size.coerceAtLeast(1),
                    lastTimeUsedMillis = lastTime,
                    isBlocked = false
                )
            }
            .sortedByDescending { it.usageTimeMillis }
            .toList()

        // Total screen time calculated from ALL qualifying applications
        val totalTime = allApps.sumOf { it.usageTimeMillis }
        val topApps = allApps.take(25)

        val monday = targetDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeklyOverview = (0..6).map { offset ->
            val day = monday.plusDays(offset.toLong())
            val dayTotal = when {
                day.isAfter(today) -> 0L
                day == targetDate -> totalTime
                else -> {
                    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
                    val dayEnd = if (day == today) {
                        System.currentTimeMillis()
                    } else {
                        day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    }
                    fastQueryDayTotal(dayStart, dayEnd)
                }
            }

            DayUsageOverview(
                date = day,
                dayName = day.dayOfWeek.name.take(3),
                totalTimeMillis = dayTotal,
                isToday = day == today,
                isSelected = day == targetDate
            )
        }

        DailyUsageSummary(
            selectedDate = targetDate,
            totalTimeMillis = totalTime,
            topApps = topApps,
            sessions = sessions,
            weeklyOverview = weeklyOverview,
            isRealDataAvailable = true
        )
    }

    private fun extractSessionsFromEvents(
        startEpochMillis: Long,
        endEpochMillis: Long,
        isToday: Boolean
    ): List<AppUsageSession> {
        val manager = usageStatsManager ?: return emptyList()
        val queryStart = maxOf(0L, startEpochMillis - 24L * 60L * 60L * 1000L)
        val events = try {
            manager.queryEvents(queryStart, endEpochMillis) ?: return emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read usage events", e)
            return emptyList()
        }

        val rawEvents = mutableListOf<RawUsageEvent>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val time = event.timeStamp
            val className = event.className

            val normalizedType = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, 1 /* MOVE_TO_FOREGROUND */ -> NormalizedEventType.ACTIVITY_RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED, 2 /* MOVE_TO_BACKGROUND */ -> NormalizedEventType.ACTIVITY_PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> NormalizedEventType.ACTIVITY_STOPPED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, 16 -> NormalizedEventType.SCREEN_NON_INTERACTIVE
                UsageEvents.Event.SCREEN_INTERACTIVE, 15 -> NormalizedEventType.SCREEN_INTERACTIVE
                UsageEvents.Event.KEYGUARD_SHOWN, 17 -> NormalizedEventType.KEYGUARD_SHOWN
                UsageEvents.Event.KEYGUARD_HIDDEN, 18 -> NormalizedEventType.KEYGUARD_HIDDEN
                UsageEvents.Event.DEVICE_SHUTDOWN -> NormalizedEventType.DEVICE_SHUTDOWN
                UsageEvents.Event.DEVICE_STARTUP -> NormalizedEventType.DEVICE_STARTUP
                else -> NormalizedEventType.OTHER
            }

            rawEvents.add(
                RawUsageEvent(
                    packageName = pkg,
                    className = className,
                    timestampMillis = time,
                    type = normalizedType
                )
            )
        }

        val intervals = UsageSessionAggregator.reconstructSessions(
            events = rawEvents,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            isToday = isToday,
            isUserLaunchableApp = ::isUserLaunchableApp
        )

        return intervals.map { interval ->
            AppUsageSession(
                packageName = interval.packageName,
                appName = resolveAppLabel(interval.packageName),
                startTimeEpochMillis = interval.startEpochMillis,
                endTimeEpochMillis = interval.endEpochMillis,
                durationMillis = interval.durationMillis
            )
        }
    }

    private fun fastQueryDayTotal(startEpochMillis: Long, endExclusiveEpochMillis: Long): Long {
        val manager = usageStatsManager ?: return 0L
        if (startEpochMillis >= endExclusiveEpochMillis) return 0L
        return try {
            manager.queryAndAggregateUsageStats(startEpochMillis, endExclusiveEpochMillis)
                .orEmpty()
                .entries
                .sumOf { (pkg, stat) ->
                    if (isUserLaunchableApp(pkg)) stat.totalTimeInForeground.coerceAtLeast(0L) else 0L
                }
        } catch (e: Exception) {
            Log.e(TAG, "Weekly usage query failed", e)
            0L
        }
    }

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }
}
