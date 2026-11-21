package com.vr.takeback.platform.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Defensive utility to guide users and launch OEM-specific autostart
 * and background battery optimization settings (MIUI, Samsung, OnePlus, Oppo, Huawei, Vivo).
 */
object OemBatteryOptimizer {

    private const val TAG = "OemBatteryOptimizer"

    val manufacturer: String
        get() = Build.MANUFACTURER.uppercase()

    fun getDeviceAdvice(): String {
        return when {
            manufacturer.contains("XIAOMI") || manufacturer.contains("REDMI") || manufacturer.contains("POCO") ->
                "Xiaomi/MIUI/HyperOS aggressively terminates background protection. Please enable 'Autostart' and set Battery Saver to 'No restrictions'."
            manufacturer.contains("SAMSUNG") ->
                "Samsung One UI puts background apps to sleep. Please add TakeBack to 'Never sleeping apps' in Device Care > Battery."
            manufacturer.contains("ONEPLUS") || manufacturer.contains("OPPO") || manufacturer.contains("REALME") ->
                "ColorOS/OxygenOS may kill background services. Please enable 'Auto-launch' and allow background activity."
            manufacturer.contains("HUAWEI") || manufacturer.contains("HONOR") ->
                "EMUI/MagicOS requires manual app launch management. Set TakeBack to 'Manage manually' with 'Auto-launch' enabled."
            manufacturer.contains("VIVO") || manufacturer.contains("IQOO") ->
                "FuntouchOS/OriginOS requires whitelisting TakeBack in 'Speed up' and 'High background power consumption'."
            else ->
                "Ensure TakeBack is exempt from Battery Optimization so schedules and background shields stay active."
        }
    }

    fun openAutoStartSettings(context: Context): Boolean = launchOptimizationSettings(context)

    /**
     * Tries manufacturer-specific intent first, then falls back to standard Android battery settings.
     */
    fun launchOptimizationSettings(context: Context): Boolean {
        val intents = getOemIntents(context)

        for (intent in intents) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch intent: $intent", e)
            }
        }

        // Standard Android Battery Optimization fallback
        return try {
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
            true
        } catch (e: Exception) {
            // Ultimate fallback: App details
            try {
                val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(appDetailsIntent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "All battery optimization navigation attempts failed", e2)
                false
            }
        }
    }

    private fun getOemIntents(context: Context): List<Intent> {
        val list = mutableListOf<Intent>()

        // Xiaomi / MIUI / HyperOS
        list.add(
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
        )

        // Samsung Device Care / Battery
        list.add(
            Intent().setComponent(
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
            )
        )
        list.add(
            Intent().setComponent(
                ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
            )
        )

        // Oppo / Realme / OnePlus (ColorOS / OxygenOS)
        list.add(
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            )
        )
        list.add(
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            )
        )
        list.add(
            Intent().setComponent(
                ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            )
        )

        // Huawei / Honor
        list.add(
            Intent().setComponent(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            )
        )
        list.add(
            Intent().setComponent(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
            )
        )

        // Vivo
        list.add(
            Intent().setComponent(
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            )
        )

        return list
    }
}
