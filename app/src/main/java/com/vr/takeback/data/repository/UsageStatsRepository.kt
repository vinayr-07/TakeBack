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
import com.vr.takeback.data.database.PersistedAppRecord
import com.vr.takeback.data.database.PersistedDeviceRecord
import com.vr.takeback.data.database.UsageLedgerDatabase
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
    val totalTimeMillis: Long?,
    val isAvailable: Boolean = (totalTimeMillis != null),
    val isToday: Boolean,
    val isSelected: Boolean,
    val dataQuality: UsageDataQuality = UsageDataQuality.RECONSTRUCTED_COMPLETE
)

data class DailyUsageSummary(
    val selectedDate: LocalDate = LocalDate.now(),
    val totalTimeMillis: Long, // Sum of tracked app foreground usage
    val deviceScreenTimeMillis: Long?, // Device Interactive Time (null if unavailable, NO misleading fallback)
    val isDeviceInteractiveAvailable: Boolean = (deviceScreenTimeMillis != null),
    val topApps: List<AppUsageInfo>,
    val sessions: List<AppUsageSession>,
    val weeklyOverview: List<DayUsageOverview>,
    val isRealDataAvailable: Boolean,
    val dataQuality: UsageDataQuality = UsageDataQuality.RECONSTRUCTED_COMPLETE,
    val dataSourceDescription: String = "UsageEvents Reconstructed",
    val completeThroughTimestampMillis: Long = System.currentTimeMillis(),
    val diagnostics: UsageDiagnostics? = null
)

sealed class UsageEventsQueryResult {
    data class Success(val events: List<RawUsageEvent>) : UsageEventsQueryResult()
    object EmptySuccess : UsageEventsQueryResult()
    object NullResponse : UsageEventsQueryResult()
    data class SecurityError(val exception: SecurityException) : UsageEventsQueryResult()
    data class QueryError(val exception: Exception) : UsageEventsQueryResult()
}

data class ValidatedUsageSnapshot(
    val date: LocalDate,
    val capturedAtMillis: Long,
    val foregroundUsageByPackage: Map<String, Long>,
    val quality: UsageDataQuality,
    val isAvailable: Boolean,
    val dataSource: String,
    /** Defect 2: Rolling 60-minute foreground usage per package. Empty when unavailable. */
    val hourlyUsageByPackage: Map<String, Long> = emptyMap()
)

class UsageStatsRepository(private val context: Context) {

    companion object {
        const val TAG = "UsageStatsRepository"
        const val DIAG_TAG = "TakeBackUsageDiag"
        private const val KEY_DAILY_ALLOWANCE = "daily_allowance_minutes"
        private const val DEFAULT_ALLOWANCE_MINUTES = 120 // 2 hours
        private const val MAX_CACHE_FRESHNESS_MILLIS = 15L * 60L * 1000L // 15 minutes

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
    private val ledgerDb = UsageLedgerDatabase(context)

    @Volatile
    private var cachedLaunchablePackages: Set<String>? = null

    // Build 7 Issue 6: Cache resolved package labels to avoid repeated PackageManager IPC
    private val appLabelCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    private var latestDiagnostics: UsageDiagnostics? = null

    @Volatile
    private var lastValidatedSnapshot: ValidatedUsageSnapshot? = null

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
        appLabelCache.clear()
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

    /**
     * Returns the active typed usage snapshot for CentralRestrictionEngine.
     * Enforces strict date and permission freshness requirements:
     * 1. If permission is revoked, returns permission-denied unavailable snapshot.
     * 2. Rejects previous-day snapshots (never uses yesterday's usage as today's data).
     * 3. Rejects snapshots older than MAX_CACHE_FRESHNESS_MILLIS.
     */
    fun getActiveUsageSnapshot(): ValidatedUsageSnapshot {
        val today = LocalDate.now()
        val now = System.currentTimeMillis()

        if (!hasUsagePermission() || usageStatsManager == null) {
            lastValidatedSnapshot = null
            return ValidatedUsageSnapshot(
                date = today,
                capturedAtMillis = now,
                foregroundUsageByPackage = emptyMap(),
                quality = UsageDataQuality.PERMISSION_DENIED,
                isAvailable = false,
                dataSource = "Permission Denied"
            )
        }

        val snapshot = lastValidatedSnapshot ?: return ValidatedUsageSnapshot(
            date = today,
            capturedAtMillis = now,
            foregroundUsageByPackage = emptyMap(),
            quality = UsageDataQuality.UNKNOWN,
            isAvailable = false,
            dataSource = "No Snapshot"
        )

        // Requirement 4: Never use yesterday's snapshot for today's quota
        if (snapshot.date != today) {
            lastValidatedSnapshot = null
            return ValidatedUsageSnapshot(
                date = today,
                capturedAtMillis = now,
                foregroundUsageByPackage = emptyMap(),
                quality = UsageDataQuality.STALE,
                isAvailable = false,
                dataSource = "Cross-day Stale"
            )
        }

        // Freshness check: If older than 15 minutes, reject as stale
        val ageMillis = now - snapshot.capturedAtMillis
        if (ageMillis > MAX_CACHE_FRESHNESS_MILLIS) {
            Log.w(TAG, "Usage snapshot is stale (${ageMillis / 1000}s old), rejecting cache")
            return snapshot.copy(quality = UsageDataQuality.STALE, isAvailable = false)
        }

        return snapshot
    }

    /**
     * Legacy map accessor: returns package usage map if fresh snapshot is available.
     */
    fun getCachedUsageSnapshot(): Map<String, Long> {
        val snapshot = getActiveUsageSnapshot()
        return if (snapshot.isAvailable) snapshot.foregroundUsageByPackage else emptyMap()
    }

    fun getCachedUsageQuality(): UsageDataQuality {
        return getActiveUsageSnapshot().quality
    }

    /**
     * Refreshes the active today usage snapshot for CentralRestrictionEngine.
     * Uses 24-hour lookback before midnight to ensure preceding app states (e.g. apps
     * active across midnight) are accurately established and bounded to today's start.
     *
     * Defect 2: Also computes a rolling 60-minute foreground usage map for PER_HOUR limits.
     */
    suspend fun refreshUsageSnapshot(): ValidatedUsageSnapshot = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)

        if (!hasUsagePermission() || usageStatsManager == null) {
            lastValidatedSnapshot = null
            return@withContext ValidatedUsageSnapshot(
                date = today,
                capturedAtMillis = now,
                foregroundUsageByPackage = emptyMap(),
                quality = UsageDataQuality.PERMISSION_DENIED,
                isAvailable = false,
                dataSource = "Permission Denied"
            )
        }

        val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val queryStart = maxOf(0L, startOfDay - 24L * 60L * 60L * 1000L)
        val queryResult = fetchRawUsageEvents(queryStart, now)

        // Defect 2: Compute rolling 60-minute hourly usage from the same event buffer.
        val hourlyWindowStart = maxOf(queryStart, now - 60L * 60L * 1000L)
        val hourlyUsageMap: Map<String, Long> = when (queryResult) {
            is UsageEventsQueryResult.Success -> {
                val hourlySessions = UsageSessionAggregator.reconstructSessions(
                    events = queryResult.events,
                    startEpochMillis = hourlyWindowStart,
                    endEpochMillis = now,
                    isToday = true,
                    isUserLaunchableApp = ::isUserLaunchableApp,
                    currentEpochMillis = now
                )
                hourlySessions
                    .groupBy { it.packageName }
                    .mapValues { (_, list) -> list.sumOf { it.durationMillis } }
            }
            else -> emptyMap()
        }

        // Build 7 Issue 6: Calculate ReconstructionResult once and reuse for snapshot and checkpoint persistence.
        var dailyReconstruction: ReconstructionResult? = null

        val snapshot = when (queryResult) {
            is UsageEventsQueryResult.Success -> {
                val reconstruction = UsageSessionAggregator.reconstructFull(
                    events = queryResult.events,
                    startEpochMillis = startOfDay,
                    endEpochMillis = now,
                    isToday = true,
                    isUserLaunchableApp = ::isUserLaunchableApp,
                    currentEpochMillis = now
                )
                dailyReconstruction = reconstruction

                val usageMap = reconstruction.appSessions
                    .groupBy { it.packageName }
                    .mapValues { (_, list) -> list.sumOf { it.durationMillis } }

                val quality = if (reconstruction.hasIncompleteStartBoundary || reconstruction.hasIncompleteEndBoundary || reconstruction.hasIncompleteAppSessionStart) {
                    UsageDataQuality.RECONSTRUCTED_PARTIAL
                } else {
                    UsageDataQuality.PROVISIONAL
                }

                ValidatedUsageSnapshot(
                    date = today,
                    capturedAtMillis = now,
                    foregroundUsageByPackage = usageMap,
                    quality = quality,
                    isAvailable = true,
                    dataSource = "UsageEvents",
                    hourlyUsageByPackage = hourlyUsageMap
                )
            }
            is UsageEventsQueryResult.EmptySuccess -> {
                // Defect 5: Empty events for today is PROVISIONAL (device may simply be idle so far).
                // It is NOT a verified zero; we cannot confirm the event buffer is not simply empty early.
                ValidatedUsageSnapshot(
                    date = today,
                    capturedAtMillis = now,
                    foregroundUsageByPackage = emptyMap(),
                    quality = UsageDataQuality.PROVISIONAL,
                    isAvailable = true, // plausible: genuinely no use yet today
                    dataSource = "UsageEvents (0 events)"
                )
            }
            is UsageEventsQueryResult.SecurityError -> {
                ValidatedUsageSnapshot(
                    date = today,
                    capturedAtMillis = now,
                    foregroundUsageByPackage = lastValidatedSnapshot?.takeIf { it.date == today }?.foregroundUsageByPackage ?: emptyMap(),
                    quality = UsageDataQuality.PERMISSION_DENIED,
                    isAvailable = false,
                    dataSource = "SecurityException"
                )
            }
            is UsageEventsQueryResult.QueryError,
            is UsageEventsQueryResult.NullResponse -> {
                ValidatedUsageSnapshot(
                    date = today,
                    capturedAtMillis = now,
                    foregroundUsageByPackage = lastValidatedSnapshot?.takeIf { it.date == today }?.foregroundUsageByPackage ?: emptyMap(),
                    quality = UsageDataQuality.QUERY_FAILED,
                    isAvailable = false,
                    dataSource = "Query Failed"
                )
            }
        }

        lastValidatedSnapshot = snapshot

        // Build 7 Issue 6: Controlled idempotent checkpoint persistence reusing dailyReconstruction (no second full pass)
        if (snapshot.isAvailable && dailyReconstruction != null) {
            saveProvisionalIntradayCheckpoint(today, now, startOfDay, snapshot, dailyReconstruction)
        }

        snapshot
    }

    /**
     * Defect 6: Save provisional intraday checkpoint using real reconstruction data.
     * - deviceInteractiveMillis: use actual reconstruction result (Long.MIN_VALUE = unavailable sentinel)
     * - lastTimeUsedMillis: use the last session end time for each app (not System.currentTimeMillis())
     */
    private fun saveProvisionalIntradayCheckpoint(
        today: LocalDate,
        now: Long,
        startOfDay: Long,
        snapshot: ValidatedUsageSnapshot,
        reconstruction: com.vr.takeback.domain.usage.ReconstructionResult
    ) {
        try {
            // Build per-package last-used timestamps from reconstruction sessions
            val lastUsedByPackage: Map<String, Long> = reconstruction.appSessions
                .groupBy { it.packageName }
                .mapValues { (_, sessions) -> sessions.maxOf { it.endEpochMillis } }

            val appRecords = snapshot.foregroundUsageByPackage.map { (pkg, duration) ->
                PersistedAppRecord(
                    dateIso = today.toString(),
                    packageName = pkg,
                    appLabel = resolveAppLabel(pkg),
                    foregroundDurationMillis = duration,
                    reconstructedSessionCount = reconstruction.appSessions.count { it.packageName == pkg },
                    // Defect 6 fix: use actual last-session-end timestamp, not System.currentTimeMillis()
                    lastTimeUsedMillis = lastUsedByPackage[pkg] ?: now,
                    dataSource = snapshot.dataSource,
                    qualityStatus = snapshot.quality,
                    queryTimestamp = now,
                    lastSyncEventTimestamp = now,
                    deviceTimezone = ZoneId.systemDefault().id
                )
            }

            // Defect 6 fix: Use Long.MIN_VALUE as sentinel for "unavailable" interactive time.
            // The database schema stores it as INTEGER; callers must check for Long.MIN_VALUE.
            val deviceInteractiveSentinel = reconstruction.deviceInteractiveTimeMillis ?: Long.MIN_VALUE

            val deviceRecord = PersistedDeviceRecord(
                dateIso = today.toString(),
                deviceInteractiveMillis = deviceInteractiveSentinel,
                totalTrackedAppMillis = snapshot.foregroundUsageByPackage.values.sum(),
                dataSource = snapshot.dataSource,
                qualityStatus = snapshot.quality,
                queryTimestamp = now,
                lastSyncEventTimestamp = now,
                deviceTimezone = ZoneId.systemDefault().id,
                isFinalized = false
            )
            ledgerDb.persistDailyUsage(deviceRecord, appRecords)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save provisional intraday checkpoint", e)
        }
    }

    suspend fun getTodayUsageSummary(): DailyUsageSummary = withContext(Dispatchers.IO) {
        getUsageSummaryForDate(LocalDate.now())
    }

    suspend fun getUsageSummaryForDate(targetDate: LocalDate): DailyUsageSummary = withContext(Dispatchers.IO) {
        val hasPermission = hasUsagePermission()
        if (!hasPermission || usageStatsManager == null) {
            lastValidatedSnapshot = null
            return@withContext DailyUsageSummary(
                selectedDate = targetDate,
                totalTimeMillis = 0L,
                deviceScreenTimeMillis = null,
                isDeviceInteractiveAvailable = false,
                topApps = emptyList(),
                sessions = emptyList(),
                weeklyOverview = emptyList(),
                isRealDataAvailable = false,
                dataQuality = UsageDataQuality.PERMISSION_DENIED,
                dataSourceDescription = "Permission Denied",
                diagnostics = buildErrorDiagnostics(targetDate, "Usage access permission not granted or service unavailable")
            )
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val isTargetToday = (targetDate == today)
        val isTargetPast = targetDate.isBefore(today)

        // 1. Check persistent database ledger for past days
        if (isTargetPast) {
            val persistedDevice = ledgerDb.getDeviceRecord(targetDate)
            if (persistedDevice != null && persistedDevice.isFinalized) {
                val persistedApps = ledgerDb.getAppRecords(targetDate)
                val topApps = persistedApps.map { record ->
                    AppUsageInfo(
                        packageName = record.packageName,
                        appName = record.appLabel,
                        usageTimeMillis = record.foregroundDurationMillis,
                        reconstructedSessionCount = record.reconstructedSessionCount,
                        lastTimeUsedMillis = record.lastTimeUsedMillis,
                        isBlocked = false
                    )
                }

                // Defect 6: Treat sentinel as unavailable interactive time
                val persistedInteractive = persistedDevice.deviceInteractiveMillis
                val interactiveAvailable = persistedInteractive != UsageLedgerDatabase.DEVICE_INTERACTIVE_UNAVAILABLE_SENTINEL
                val interactiveMillis: Long? = if (interactiveAvailable) persistedInteractive else null

                val weeklyOverview = buildWeeklyOverview(targetDate, today, zone, interactiveMillis, persistedDevice.qualityStatus)

                return@withContext DailyUsageSummary(
                    selectedDate = targetDate,
                    totalTimeMillis = persistedDevice.totalTrackedAppMillis,
                    deviceScreenTimeMillis = interactiveMillis,
                    isDeviceInteractiveAvailable = interactiveAvailable,
                    topApps = topApps,
                    sessions = emptyList(),
                    weeklyOverview = weeklyOverview,
                    isRealDataAvailable = true,
                    dataQuality = persistedDevice.qualityStatus,
                    dataSourceDescription = "Persisted Usage Ledger (${persistedDevice.dataSource})",
                    completeThroughTimestampMillis = persistedDevice.queryTimestamp,
                    diagnostics = null
                )
            }
        }

        // 2. Query UsageEvents from system
        val startOfDay = targetDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusive = if (isTargetToday) {
            System.currentTimeMillis()
        } else {
            targetDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }

        if (endExclusive <= startOfDay) {
            return@withContext DailyUsageSummary(
                selectedDate = targetDate,
                totalTimeMillis = 0L,
                deviceScreenTimeMillis = 0L,
                isDeviceInteractiveAvailable = true,
                topApps = emptyList(),
                sessions = emptyList(),
                weeklyOverview = emptyList(),
                isRealDataAvailable = true,
                dataQuality = UsageDataQuality.RECONSTRUCTED_COMPLETE,
                dataSourceDescription = "UsageEvents Reconstructed",
                diagnostics = null
            )
        }

        // Lookback window (24 hours) to determine initial active state
        val queryStart = maxOf(0L, startOfDay - 24L * 60L * 60L * 1000L)
        val queryResult = fetchRawUsageEvents(queryStart, endExclusive)

        // Requirement 5: Distinguish query errors from empty events
        when (queryResult) {
            is UsageEventsQueryResult.SecurityError -> {
                lastValidatedSnapshot = null
                val weeklyOverview = buildWeeklyOverview(targetDate, today, zone, null, UsageDataQuality.PERMISSION_DENIED)
                return@withContext DailyUsageSummary(
                    selectedDate = targetDate,
                    totalTimeMillis = 0L,
                    deviceScreenTimeMillis = null,
                    isDeviceInteractiveAvailable = false,
                    topApps = emptyList(),
                    sessions = emptyList(),
                    weeklyOverview = weeklyOverview,
                    isRealDataAvailable = false,
                    dataQuality = UsageDataQuality.PERMISSION_DENIED,
                    dataSourceDescription = "SecurityException on Usage Query",
                    diagnostics = buildErrorDiagnostics(targetDate, "SecurityException: ${queryResult.exception.message}")
                )
            }
            is UsageEventsQueryResult.QueryError -> {
                val weeklyOverview = buildWeeklyOverview(targetDate, today, zone, null, UsageDataQuality.QUERY_FAILED)
                return@withContext DailyUsageSummary(
                    selectedDate = targetDate,
                    totalTimeMillis = 0L,
                    deviceScreenTimeMillis = null,
                    isDeviceInteractiveAvailable = false,
                    topApps = emptyList(),
                    sessions = emptyList(),
                    weeklyOverview = weeklyOverview,
                    isRealDataAvailable = false,
                    dataQuality = UsageDataQuality.QUERY_FAILED,
                    dataSourceDescription = "System Query Failed",
                    diagnostics = buildErrorDiagnostics(targetDate, "Exception during queryEvents: ${queryResult.exception.message}")
                )
            }
            is UsageEventsQueryResult.NullResponse -> {
                val weeklyOverview = buildWeeklyOverview(targetDate, today, zone, null, UsageDataQuality.QUERY_FAILED)
                return@withContext DailyUsageSummary(
                    selectedDate = targetDate,
                    totalTimeMillis = 0L,
                    deviceScreenTimeMillis = null,
                    isDeviceInteractiveAvailable = false,
                    topApps = emptyList(),
                    sessions = emptyList(),
                    weeklyOverview = weeklyOverview,
                    isRealDataAvailable = false,
                    dataQuality = UsageDataQuality.QUERY_FAILED,
                    dataSourceDescription = "System Service Returned Null",
                    diagnostics = buildErrorDiagnostics(targetDate, "UsageStatsManager returned null for event stream")
                )
            }
            is UsageEventsQueryResult.EmptySuccess -> {
                val daysAgo = java.time.temporal.ChronoUnit.DAYS.between(targetDate, today)
                if (isTargetPast && daysAgo > 7) {
                    val weeklyOverview = buildWeeklyOverview(targetDate, today, zone, null, UsageDataQuality.HISTORY_EXPIRED)
                    return@withContext DailyUsageSummary(
                        selectedDate = targetDate,
                        totalTimeMillis = 0L,
                        deviceScreenTimeMillis = null,
                        isDeviceInteractiveAvailable = false,
                        topApps = emptyList(),
                        sessions = emptyList(),
                        weeklyOverview = weeklyOverview,
                        isRealDataAvailable = false,
                        dataQuality = UsageDataQuality.HISTORY_EXPIRED,
                        dataSourceDescription = "History Expired (Events purged by Android OS)",
                        completeThroughTimestampMillis = endExclusive,
                        diagnostics = buildErrorDiagnostics(targetDate, "System UsageEvents for $targetDate have been purged by OS")
                    )
                } else {
                    // Defect 5: Empty events within 7 days is NOT a verified zero.
                    // For today: PROVISIONAL (device may not have been used yet today).
                    // For yesterday or older within 7 days: RECONSTRUCTED_PARTIAL / UNKNOWN —
                    // the buffer could have expired early or the day genuinely had no use.
                    // We NEVER call this RECONSTRUCTED_COMPLETE (which asserts verified zero).
                    val (quality, isAvailable) = when {
                        isTargetToday -> Pair(UsageDataQuality.PROVISIONAL, true)
                        daysAgo == 1L -> Pair(UsageDataQuality.RECONSTRUCTED_PARTIAL, false) // Cannot verify
                        else -> Pair(UsageDataQuality.UNKNOWN, false)
                    }
                    val weeklyOverview = buildWeeklyOverview(targetDate, today, zone, if (isAvailable) 0L else null, quality)
                    return@withContext DailyUsageSummary(
                        selectedDate = targetDate,
                        totalTimeMillis = 0L,
                        deviceScreenTimeMillis = if (isAvailable) 0L else null,
                        isDeviceInteractiveAvailable = isAvailable,
                        topApps = emptyList(),
                        sessions = emptyList(),
                        weeklyOverview = weeklyOverview,
                        isRealDataAvailable = isAvailable,
                        dataQuality = quality,
                        dataSourceDescription = "UsageEvents (0 events observed; cannot confirm verified zero)",
                        completeThroughTimestampMillis = endExclusive,
                        diagnostics = if (!isAvailable) buildErrorDiagnostics(targetDate, "No UsageEvents within retention window; cannot verify zero usage") else null
                    )
                }
            }
            is UsageEventsQueryResult.Success -> {
                // Continue to reconstruction
            }
        }

        val rawEvents = (queryResult as UsageEventsQueryResult.Success).events
        val now = System.currentTimeMillis()

        // Reconstruct both application sessions and independent device-interactive time
        val reconstruction = UsageSessionAggregator.reconstructFull(
            events = rawEvents,
            startEpochMillis = startOfDay,
            endEpochMillis = endExclusive,
            isToday = isTargetToday,
            isUserLaunchableApp = ::isUserLaunchableApp,
            currentEpochMillis = now
        )

        val deviceInteractiveTime = reconstruction.deviceInteractiveTimeMillis
        val totalTrackedAppTime = reconstruction.totalTrackedAppUsageMillis

        // Map reconstructed sessions with resolved application labels
        val sessions = reconstruction.appSessions.map { interval ->
            AppUsageSession(
                packageName = interval.packageName,
                appName = resolveAppLabel(interval.packageName),
                startTimeEpochMillis = interval.startEpochMillis,
                endTimeEpochMillis = interval.endEpochMillis,
                durationMillis = interval.durationMillis
            )
        }

        // Group per application
        val sessionsByPackage = sessions.groupBy { it.packageName }
        val allApps = sessionsByPackage.map { (pkg, appSessions) ->
            val totalAppTime = appSessions.sumOf { it.durationMillis }
            val lastTimeUsed = appSessions.maxOfOrNull { it.endTimeEpochMillis } ?: 0L
            AppUsageInfo(
                packageName = pkg,
                appName = resolveAppLabel(pkg),
                usageTimeMillis = totalAppTime,
                reconstructedSessionCount = appSessions.size,
                lastTimeUsedMillis = lastTimeUsed,
                isBlocked = false
            )
        }.sortedByDescending { it.usageTimeMillis }

        val topApps = allApps.take(25)

        // Determine data quality
        val lastEventTimestamp = rawEvents.maxOfOrNull { it.timestampMillis } ?: 0L
        val dataQuality = when {
            // Defect 7: treat incomplete app-session start boundary same as incomplete screen boundary
            reconstruction.hasIncompleteStartBoundary ||
            reconstruction.hasIncompleteEndBoundary ||
            reconstruction.hasIncompleteAppSessionStart -> UsageDataQuality.RECONSTRUCTED_PARTIAL
            isTargetToday && (now - lastEventTimestamp > 180_000L) -> UsageDataQuality.PROVISIONAL
            isTargetToday -> UsageDataQuality.PROVISIONAL
            else -> UsageDataQuality.RECONSTRUCTED_COMPLETE
        }

        // Requirement 6: Only finalize past dates if reconstruction is complete and has no incomplete boundaries!
        val isEligibleForFinalization = isTargetPast &&
                dataQuality == UsageDataQuality.RECONSTRUCTED_COMPLETE &&
                !reconstruction.hasIncompleteStartBoundary &&
                !reconstruction.hasIncompleteEndBoundary

        val persistedDevice = PersistedDeviceRecord(
            dateIso = targetDate.toString(),
            deviceInteractiveMillis = deviceInteractiveTime ?: 0L,
            totalTrackedAppMillis = totalTrackedAppTime,
            dataSource = "UsageEvents",
            qualityStatus = dataQuality,
            queryTimestamp = now,
            lastSyncEventTimestamp = lastEventTimestamp,
            deviceTimezone = zone.id,
            isFinalized = isEligibleForFinalization
        )
        val persistedAppRecords = allApps.map { app ->
            PersistedAppRecord(
                dateIso = targetDate.toString(),
                packageName = app.packageName,
                appLabel = app.appName,
                foregroundDurationMillis = app.usageTimeMillis,
                reconstructedSessionCount = app.reconstructedSessionCount,
                lastTimeUsedMillis = app.lastTimeUsedMillis,
                dataSource = "UsageEvents",
                qualityStatus = dataQuality,
                queryTimestamp = now,
                lastSyncEventTimestamp = lastEventTimestamp,
                deviceTimezone = zone.id
            )
        }
        val persistSuccess = ledgerDb.persistDailyUsage(persistedDevice, persistedAppRecords)
        if (!persistSuccess) {
            Log.w(TAG, "Persistence ledger write was rejected or encountered an error for $targetDate")
        }

        // Update in-memory snapshot for restriction engine if target is today
        if (isTargetToday) {
            val usageMap = allApps.associate { it.packageName to it.usageTimeMillis }
            lastValidatedSnapshot = ValidatedUsageSnapshot(
                date = today,
                capturedAtMillis = now,
                foregroundUsageByPackage = usageMap,
                quality = dataQuality,
                isAvailable = true,
                dataSource = "UsageEvents"
            )
        }

        // Weekly overview
        val weeklyOverview = buildWeeklyOverview(targetDate, today, zone, deviceInteractiveTime, dataQuality)

        // Comprehensive diagnostic report with genuine same-package comparison
        val diagnostics = collectDiagnostics(
            targetDate = targetDate,
            startOfDay = startOfDay,
            endExclusive = endExclusive,
            rawEvents = rawEvents,
            sessions = reconstruction.appSessions,
            deviceInteractiveTime = reconstruction.deviceInteractiveTimeMillis,
            isDeviceInteractiveAvailable = reconstruction.isDeviceInteractiveAvailable,
            totalAppTime = totalTrackedAppTime,
            perAppUsage = allApps.associate { it.packageName to it.usageTimeMillis },
            dataQuality = dataQuality
        )
        latestDiagnostics = diagnostics
        Log.i(DIAG_TAG, diagnostics.toFormattedReport())

        DailyUsageSummary(
            selectedDate = targetDate,
            totalTimeMillis = totalTrackedAppTime,
            deviceScreenTimeMillis = deviceInteractiveTime,
            isDeviceInteractiveAvailable = reconstruction.isDeviceInteractiveAvailable,
            topApps = topApps,
            sessions = sessions,
            weeklyOverview = weeklyOverview,
            isRealDataAvailable = true,
            dataQuality = dataQuality,
            dataSourceDescription = "UsageEvents Reconstructed (${if (isTargetToday) "Live Intraday" else "Finalized"})",
            completeThroughTimestampMillis = if (isTargetToday) now else endExclusive,
            diagnostics = diagnostics
        )
    }

    private fun buildWeeklyOverview(
        targetDate: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        selectedDayInteractiveTime: Long?,
        selectedDayQuality: UsageDataQuality
    ): List<DayUsageOverview> {
        val monday = targetDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0..6).map { offset ->
            val day = monday.plusDays(offset.toLong())
            val isDayToday = (day == today)
            val isDaySelected = (day == targetDate)

            if (day.isAfter(today)) {
                return@map DayUsageOverview(
                    date = day,
                    dayName = day.dayOfWeek.name.take(3),
                    totalTimeMillis = 0L,
                    isAvailable = true,
                    isToday = false,
                    isSelected = isDaySelected,
                    dataQuality = UsageDataQuality.RECONSTRUCTED_COMPLETE
                )
            }

            if (isDaySelected) {
                return@map DayUsageOverview(
                    date = day,
                    dayName = day.dayOfWeek.name.take(3),
                    totalTimeMillis = selectedDayInteractiveTime,
                    isAvailable = (selectedDayInteractiveTime != null),
                    isToday = isDayToday,
                    isSelected = true,
                    dataQuality = selectedDayQuality
                )
            }

            val persisted = ledgerDb.getDeviceRecord(day)
            if (persisted != null) {
                val quality = if (persisted.isFinalized) {
                    persisted.qualityStatus
                } else {
                    if (persisted.qualityStatus == UsageDataQuality.RECONSTRUCTED_COMPLETE) {
                        UsageDataQuality.RECONSTRUCTED_PARTIAL
                    } else {
                        persisted.qualityStatus
                    }
                }
                val isInteractiveAvailable = persisted.deviceInteractiveMillis != UsageLedgerDatabase.DEVICE_INTERACTIVE_UNAVAILABLE_SENTINEL
                val interactiveTime: Long? = if (isInteractiveAvailable) persisted.deviceInteractiveMillis else null
                DayUsageOverview(
                    date = day,
                    dayName = day.dayOfWeek.name.take(3),
                    totalTimeMillis = interactiveTime,
                    isAvailable = isInteractiveAvailable,
                    isToday = isDayToday,
                    isSelected = false,
                    dataQuality = quality
                )
            } else {
                val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = if (day == today) {
                    System.currentTimeMillis()
                } else {
                    day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                }
                val (computedTime, quality) = computeDeviceInteractiveForWindowWithQuality(dayStart, dayEnd, day == today)
                DayUsageOverview(
                    date = day,
                    dayName = day.dayOfWeek.name.take(3),
                    totalTimeMillis = computedTime,
                    isAvailable = (computedTime != null),
                    isToday = isDayToday,
                    isSelected = false,
                    dataQuality = quality
                )
            }
        }
    }

    private fun computeDeviceInteractiveForWindowWithQuality(start: Long, end: Long, isToday: Boolean): Pair<Long?, UsageDataQuality> {
        val queryStart = maxOf(0L, start - 24L * 60L * 60L * 1000L)
        val queryResult = fetchRawUsageEvents(queryStart, end)
        val events = when (queryResult) {
            is UsageEventsQueryResult.Success -> queryResult.events
            is UsageEventsQueryResult.EmptySuccess -> {
                // Build 7 Issue 5: Represent historical empty queries as unavailable.
                // Do not silently display zero for missing history!
                return if (isToday) {
                    Pair(0L, UsageDataQuality.PROVISIONAL)
                } else {
                    Pair(null, UsageDataQuality.HISTORY_EXPIRED)
                }
            }
            is UsageEventsQueryResult.SecurityError -> return Pair(null, UsageDataQuality.PERMISSION_DENIED)
            else -> return Pair(null, UsageDataQuality.QUERY_FAILED)
        }
        val result = UsageSessionAggregator.reconstructFull(
            events = events,
            startEpochMillis = start,
            endEpochMillis = end,
            isToday = isToday,
            isUserLaunchableApp = ::isUserLaunchableApp,
            currentEpochMillis = System.currentTimeMillis()
        )
        val quality = when {
            result.hasIncompleteStartBoundary || result.hasIncompleteEndBoundary -> UsageDataQuality.RECONSTRUCTED_PARTIAL
            isToday -> UsageDataQuality.PROVISIONAL
            else -> UsageDataQuality.RECONSTRUCTED_COMPLETE
        }
        return Pair(result.deviceInteractiveTimeMillis, quality)
    }

    private fun computeDeviceInteractiveForWindow(start: Long, end: Long, isToday: Boolean): Long {
        return computeDeviceInteractiveForWindowWithQuality(start, end, isToday).first ?: 0L
    }

    fun fetchRawUsageEvents(queryStart: Long, queryEnd: Long): UsageEventsQueryResult {
        val manager = usageStatsManager ?: return UsageEventsQueryResult.NullResponse
        val events = try {
            manager.queryEvents(queryStart, queryEnd) ?: return UsageEventsQueryResult.NullResponse
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException reading usage events from $queryStart to $queryEnd", e)
            return UsageEventsQueryResult.SecurityError(e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception reading usage events from $queryStart to $queryEnd", e)
            return UsageEventsQueryResult.QueryError(e)
        }

        if (!events.hasNextEvent()) {
            return UsageEventsQueryResult.EmptySuccess
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
        return UsageEventsQueryResult.Success(rawList)
    }



    private fun collectDiagnostics(
        targetDate: LocalDate,
        startOfDay: Long,
        endExclusive: Long,
        rawEvents: List<RawUsageEvent>,
        sessions: List<SessionInterval>,
        deviceInteractiveTime: Long?,
        isDeviceInteractiveAvailable: Boolean,
        totalAppTime: Long,
        perAppUsage: Map<String, Long>,
        dataQuality: UsageDataQuality
    ): UsageDiagnostics {
        val zone = ZoneId.systemDefault()
        val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)
        val now = System.currentTimeMillis()

        // 1. Query queryAndAggregateUsageStats
        val aggMap: Map<String, UsageStats> = try {
            val raw = usageStatsManager?.queryAndAggregateUsageStats(startOfDay, endExclusive)
            if (raw != null) HashMap(raw) else emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        // 2. Query queryUsageStats(INTERVAL_DAILY)
        val dailyStats = try {
            usageStatsManager?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, endExclusive).orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        val dailyIntervalMap = dailyStats.groupBy { it.packageName }.mapValues { (_, list) -> list.sumOf { it.totalTimeInForeground } }

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

        // 3. Build unified package set across all sources
        val allSeenPackages = mutableSetOf<String>()
        allSeenPackages.addAll(aggMap.keys)
        allSeenPackages.addAll(dailyStats.map { it.packageName })
        allSeenPackages.addAll(rawEvents.map { it.packageName })

        var eligibleCount = 0
        var excludedCount = 0
        var allAggTotal = 0L
        var allDailyTotal = 0L
        var eligibleAggTotal = 0L
        var eligibleDailyTotal = 0L
        var eligibleReconstructedTotal = 0L
        var excludedAggTotal = 0L

        val packageEntries = mutableListOf<PackageComparisonEntry>()

        for (pkg in allSeenPackages) {
            val isEligible = isUserLaunchableApp(pkg)
            val aggDuration = aggMap[pkg]?.totalTimeInForeground ?: 0L
            val dailyDuration = dailyIntervalMap[pkg] ?: 0L
            val reconstructedDuration = perAppUsage[pkg] ?: 0L

            allAggTotal += aggDuration
            allDailyTotal += dailyDuration

            if (isEligible) {
                eligibleCount++
                eligibleAggTotal += aggDuration
                eligibleDailyTotal += dailyDuration
                eligibleReconstructedTotal += reconstructedDuration
            } else {
                excludedCount++
                excludedAggTotal += aggDuration
            }

            packageEntries.add(
                PackageComparisonEntry(
                    packageName = pkg,
                    appLabel = resolveAppLabel(pkg),
                    queryAndAggregateMillis = aggDuration,
                    intervalDailyMillis = dailyDuration,
                    takeBackReconstructedMillis = reconstructedDuration,
                    isEligibleUserApp = isEligible,
                    filterDecision = if (isEligible) "INCLUDED: User App" else "EXCLUDED: System / Non-launcher"
                )
            )
        }

        // Sort matrix by highest difference first
        val sortedMatrix = packageEntries.sortedByDescending { it.differenceMillis }

        val firstEventTime = rawEvents.firstOrNull()?.timestampMillis?.let { dtf.format(Instant.ofEpochMilli(it)) }
        val lastEventTime = rawEvents.lastOrNull()?.timestampMillis?.let { dtf.format(Instant.ofEpochMilli(it)) }

        val totals = DiagnosticTotals(
            allPackagesAggregateTotalMillis = allAggTotal,
            allPackagesIntervalDailyTotalMillis = allDailyTotal,
            eligibleAggregateTotalMillis = eligibleAggTotal,
            eligibleIntervalDailyTotalMillis = eligibleDailyTotal,
            eligibleTakeBackReconstructedTotalMillis = eligibleReconstructedTotal,
            eligibleDifferenceMillis = eligibleAggTotal - eligibleReconstructedTotal,
            excludedAggregateTotalMillis = excludedAggTotal,
            deviceInteractiveTimeMillis = deviceInteractiveTime,
            isDeviceInteractiveAvailable = isDeviceInteractiveAvailable
        )

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
            totals = totals,
            filterDiagnostics = FilterDiagnostics(
                totalPackagesEvaluated = allSeenPackages.size,
                eligiblePackagesCount = eligibleCount,
                excludedPackagesCount = excludedCount
            ),
            calculationDiagnostics = CalculationDiagnostics(
                totalReconstructedSessions = sessions.size,
                totalAppUsageMillis = totalAppTime,
                deviceScreenOnTimeMillis = deviceInteractiveTime ?: 0L,
                snapshotTimestampMillis = now,
                dataQuality = dataQuality
            ),
            packageMatrix = sortedMatrix,
            dailyBuckets = dailyBuckets,
            eventCount = rawEvents.size,
            firstEventTimestampFormatted = firstEventTime,
            lastEventTimestampFormatted = lastEventTime
        )
    }

    private fun buildErrorDiagnostics(targetDate: LocalDate, message: String): UsageDiagnostics {
        val zone = ZoneId.systemDefault()
        val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)
        val now = System.currentTimeMillis()
        val startOfDay = targetDate.atStartOfDay(zone).toInstant().toEpochMilli()

        val emptyTotals = DiagnosticTotals(0L, 0L, 0L, 0L, 0L, 0L, 0L, null, false)

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
            totals = emptyTotals,
            filterDiagnostics = FilterDiagnostics(0, 0, 0),
            calculationDiagnostics = CalculationDiagnostics(0, 0L, 0L, now, UsageDataQuality.QUERY_FAILED),
            packageMatrix = emptyList(),
            dailyBuckets = emptyList(),
            eventCount = 0,
            firstEventTimestampFormatted = null,
            lastEventTimestampFormatted = null
        )
    }

    fun resolveAppLabel(packageName: String): String {
        val cached = appLabelCache[packageName]
        if (cached != null) return cached
        val label = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
        appLabelCache[packageName] = label
        return label
    }
}
