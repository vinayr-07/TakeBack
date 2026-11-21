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

class PermissionRepository(private val context: Context? = null) {

    private val _permissionStatus = MutableStateFlow(checkPermissions())
    val permissionStatus: StateFlow<PermissionStatus> = _permissionStatus.asStateFlow()

    fun refreshPermissions(): PermissionStatus {
        val updated = checkPermissions()
        _permissionStatus.value = updated
        return updated
    }

    private fun checkPermissions(): PermissionStatus {
        val ctx = context ?: return PermissionStatus()
        val hasUsage = checkUsageStatsPermission(ctx)
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(ctx)
        } else true

        val hasAccessibility = checkAccessibilityPermission(ctx)
        val hasNotificationListener = checkNotificationListenerPermission(ctx)
        val hasPostNotif = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

        val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isBatteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
            powerManager.isIgnoringBatteryOptimizations(ctx.packageName)
        } else true

        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
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

    private fun checkUsageStatsPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                ctx.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun checkAccessibilityPermission(ctx: Context): Boolean {
        val expectedService = "${ctx.packageName}/com.vr.takeback.platform.service.TakeBackAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expectedService, ignoreCase = true)
    }

    private fun checkNotificationListenerPermission(ctx: Context): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(ctx)
        return enabledListeners.contains(ctx.packageName)
    }

    // Intents for direct deep-linking into Android Settings
    fun getUsageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun getOverlayIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pkg = context?.packageName ?: "com.vr.takeback"
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))
    } else Intent(Settings.ACTION_SETTINGS)

    fun getAccessibilityIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun getNotificationListenerIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    } else Intent(Settings.ACTION_SETTINGS)

    fun getBatteryOptimizationIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pkg = context?.packageName ?: "com.vr.takeback"
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
    } else Intent(Settings.ACTION_SETTINGS)

    fun getExactAlarmIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val pkg = context?.packageName ?: "com.vr.takeback"
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg"))
    } else Intent(Settings.ACTION_SETTINGS)
}
