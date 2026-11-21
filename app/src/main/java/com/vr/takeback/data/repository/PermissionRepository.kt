package com.vr.takeback.data.repository

import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.vr.takeback.domain.model.PermissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PermissionRepository(private val context: Context) {

    private val _permissionStatus = MutableStateFlow(checkPermissions())
    val permissionStatus: StateFlow<PermissionStatus> = _permissionStatus.asStateFlow()

    fun refreshPermissions(): PermissionStatus {
        val updated = checkPermissions()
        _permissionStatus.value = updated
        return updated
    }

    private fun checkPermissions(): PermissionStatus {
        val hasUsage = checkUsageStatsPermission()
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true

        val hasAccessibility = checkAccessibilityPermission()
        val hasNotificationListener = checkNotificationListenerPermission()
        val hasPostNotif = NotificationManagerCompat.from(context).areNotificationsEnabled()

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isBatteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else true

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val hasExactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager != null) {
            alarmManager.canScheduleExactAlarms()
        } else true

        return PermissionStatus(
            hasUsageStats = hasUsage,
            hasOverlay = hasOverlay,
            hasAccessibility = hasAccessibility,
            hasNotificationListener = hasNotificationListener,
            hasPostNotifications = hasPostNotif,
            isBatteryOptimizationIgnored = isBatteryIgnored,
            hasExactAlarm = hasExactAlarm,
            hasLocation = false
        )
    }

    private fun checkUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        if (mode == AppOpsManager.MODE_ALLOWED) return true

        return try {
            val now = System.currentTimeMillis()
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            val list = usageStatsManager?.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 60_000, now)
            !list.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun checkAccessibilityPermission(): Boolean {
        val expectedService = "${context.packageName}/com.vr.takeback.platform.service.TakeBackAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expectedService, ignoreCase = true)
    }

    private fun checkNotificationListenerPermission(): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabledListeners.contains(context.packageName)
    }

    // Intents for direct deep-linking into Android Settings
    fun getUsageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun getOverlayIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    } else Intent(Settings.ACTION_SETTINGS)

    fun getAccessibilityIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun getNotificationListenerIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    } else Intent(Settings.ACTION_SETTINGS)

    fun getBatteryOptimizationIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    } else Intent(Settings.ACTION_SETTINGS)

    fun getExactAlarmIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
    } else Intent(Settings.ACTION_SETTINGS)
}
