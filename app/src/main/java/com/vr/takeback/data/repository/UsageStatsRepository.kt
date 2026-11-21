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

    private fun isUserLaunchableApp(packageName: String): Boolean {
        if (SYSTEM_EXCLUDED_PACKAGES.contains(packageName)) return false
        val launchable = getLaunchablePackages()
        return if (launchable.isNotEmpty()) launchable.contains(packageName) else true
    }

    suspend fun getTodayUsageSummary(): DailyUsageSummary = withContext(Dispatchers.IO) {
        getUsageSummaryForDate(LocalDate.now())
    }

    suspend fun getUsageSummaryForDate(targetDate: LocalDate): DailyUsageSummary = withContext(Dispatchers.IO) {
        val isPermissionGranted = hasUsagePermission()
        if (!isPermissionGranted || usageStatsManager == null) {
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
        val startOfDay = targetDate.atStartOfDay(zone).toInstant().toEpochMilli() // 00:00:00.000 railway start
        val isToday = targetDate == LocalDate.now()
        val endOfDay = if (isToday) {
            System.currentTimeMillis()
        } else {
            targetDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 // 23:59:59.999
        }

        // Fast Session extraction from Events using cached launchable app set
        val sessions = extractSessionsFromEvents(startOfDay, endOfDay, isToday)

        // Ground-truth total foreground time per app from Android OS queryAndAggregateUsageStats (Digital Wellbeing baseline)
        val appUsageMap = mutableMapOf<String, Long>()
        val appLastUsedMap = mutableMapOf<String, Long>()

        try {
            val statsMap = usageStatsManager.queryAndAggregateUsageStats(startOfDay, endOfDay)
            for ((pkg, stat) in statsMap) {
                if (isUserLaunchableApp(pkg) && stat.totalTimeInForeground > 1000L) {
                    appUsageMap[pkg] = stat.totalTimeInForeground
                    appLastUsedMap[pkg] = stat.lastTimeUsed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryAndAggregateUsageStats failed: ${e.message}")
        }

        // Reconcile with extracted event sessions so we never under-report if Android OS hasn't flushed daily bucket
        for (session in sessions) {
            val current = appUsageMap[session.packageName] ?: 0L
            val sessionSum = sessions.filter { it.packageName == session.packageName }.sumOf { it.durationMillis }
            if (sessionSum > current) {
                appUsageMap[session.packageName] = sessionSum
            }
        }

        val topApps = appUsageMap.entries
            .map { (pkg, duration) ->
                val appSessions = sessions.filter { it.packageName == pkg }
                val lastTime = appSessions.maxOfOrNull { it.endTimeEpochMillis }
                    ?: appLastUsedMap[pkg]
                    ?: 0L
                AppUsageInfo(
                    packageName = pkg,
                    appName = resolveAppLabel(pkg),
                    usageTimeMillis = duration,
                    launchCount = appSessions.size.coerceAtLeast(1),
                    lastTimeUsedMillis = lastTime,
                    isBlocked = false
                )
            }
            .sortedByDescending { it.usageTimeMillis }
            .take(25)

        // Total screen time for the day is the sum of real interactive foreground time of all user apps
        val totalTime = topApps.sumOf { it.usageTimeMillis }

        // Fast 7-day current week overview starting Monday 00:00
        val monday = targetDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weeklyOverview = (0..6).map { dayOffset ->
            val dayDate = monday.plusDays(dayOffset.toLong())
            val dayTotal = if (dayDate == targetDate) {
                totalTime
            } else if (dayDate.isAfter(LocalDate.now())) {
                0L
            } else {
                val dayStart = dayDate.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = if (dayDate == LocalDate.now()) {
                    System.currentTimeMillis()
                } else {
                    dayDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                }
                fastQueryDayTotal(dayStart, dayEnd)
            }

            DayUsageOverview(
                date = dayDate,
                dayName = dayDate.dayOfWeek.name.take(3), // MON, TUE, etc.
                totalTimeMillis = dayTotal,
                isToday = dayDate == LocalDate.now(),
                isSelected = dayDate == targetDate
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
        // Query starting 1 hour before midnight to determine whatever app was in foreground right at 00:00
        val queryStart = maxOf(0L, startEpochMillis - 3600 * 1000L)
        val events = try {
            manager.queryEvents(queryStart, endEpochMillis)
        } catch (e: Exception) {
            Log.w(TAG, "queryEvents failed: ${e.message}")
            return emptyList()
        }

        val event = UsageEvents.Event()
        var currentForegroundPkg: String? = null
        var currentSessionStartTime: Long = 0L
        val completedSessions = mutableListOf<AppUsageSession>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val time = event.timeStamp

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                1 /* MOVE_TO_FOREGROUND */ -> {
                    if (pkg == currentForegroundPkg) {
                        // Same application internal navigation (e.g. Chat -> Profile) - session continues uninterrupted!
                        continue
                    }

                    // Previous app lost foreground
                    if (currentForegroundPkg != null) {
                        val sessionStart = maxOf(startEpochMillis, currentSessionStartTime)
                        val sessionEnd = minOf(endEpochMillis, time)
                        if (sessionEnd > sessionStart) {
                            val duration = sessionEnd - sessionStart
                            if (duration in 1_000L..(12 * 3600 * 1000L)) {
                                completedSessions.add(
                                    AppUsageSession(
                                        packageName = currentForegroundPkg!!,
                                        appName = resolveAppLabel(currentForegroundPkg!!),
                                        startTimeEpochMillis = sessionStart,
                                        endTimeEpochMillis = sessionEnd,
                                        durationMillis = duration
                                    )
                                )
                            }
                        }
                    }

                    // Start new session if user launchable app
                    if (isUserLaunchableApp(pkg)) {
                        currentForegroundPkg = pkg
                        currentSessionStartTime = time
                    } else {
                        currentForegroundPkg = null
                    }
                }

                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                16, 17 -> {
                    // Screen turned off or phone locked
                    if (currentForegroundPkg != null) {
                        val sessionStart = maxOf(startEpochMillis, currentSessionStartTime)
                        val sessionEnd = minOf(endEpochMillis, time)
                        if (sessionEnd > sessionStart) {
                            val duration = sessionEnd - sessionStart
                            if (duration in 1_000L..(12 * 3600 * 1000L)) {
                                completedSessions.add(
                                    AppUsageSession(
                                        packageName = currentForegroundPkg!!,
                                        appName = resolveAppLabel(currentForegroundPkg!!),
                                        startTimeEpochMillis = sessionStart,
                                        endTimeEpochMillis = sessionEnd,
                                        durationMillis = duration
                                    )
                                )
                            }
                        }
                        currentForegroundPkg = null
                    }
                }
            }
        }

        // Active ongoing session up to current moment
        if (isToday && currentForegroundPkg != null) {
            val now = System.currentTimeMillis()
            val sessionStart = maxOf(startEpochMillis, currentSessionStartTime)
            val sessionEnd = minOf(endEpochMillis, now)
            if (sessionEnd > sessionStart) {
                val duration = sessionEnd - sessionStart
                if (duration >= 1_000L) {
                    completedSessions.add(
                        AppUsageSession(
                            packageName = currentForegroundPkg!!,
                            appName = resolveAppLabel(currentForegroundPkg!!),
                            startTimeEpochMillis = sessionStart,
                            endTimeEpochMillis = sessionEnd,
                            durationMillis = duration
                        )
                    )
                }
            }
        }

        return completedSessions.sortedBy { it.startTimeEpochMillis }
    }

    private fun fastQueryDayTotal(startEpochMillis: Long, endEpochMillis: Long): Long {
        val manager = usageStatsManager ?: return 0L
        if (startEpochMillis >= endEpochMillis) return 0L
        return try {
            val statsMap = manager.queryAndAggregateUsageStats(startEpochMillis, endEpochMillis)
            var total = 0L
            for ((pkg, stat) in statsMap) {
                if (isUserLaunchableApp(pkg)) {
                    total += stat.totalTimeInForeground
                }
            }
            total
        } catch (e: Exception) {
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
