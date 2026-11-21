package com.vr.takeback.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.model.TargetApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppDiscoveryRepository(private val context: Context) {

    suspend fun getInstalledApplications(): List<TargetApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = mutableMapOf<String, TargetApp>()

        // 1. Query all launchable apps
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
        for (resolveInfo in resolveInfos) {
            val pkg = resolveInfo.activityInfo.packageName
            if (CentralRestrictionEngine.EMERGENCY_ALLOWLIST_PACKAGES.contains(pkg)) continue
            val label = resolveInfo.loadLabel(pm).toString()
            apps[pkg] = TargetApp(packageName = pkg, appName = label)
        }

        // 2. Query all installed packages to catch third-party apps that may have custom launch configurations
        try {
            val installedPackages = pm.getInstalledPackages(0)
            for (pkgInfo in installedPackages) {
                val pkg = pkgInfo.packageName
                if (CentralRestrictionEngine.EMERGENCY_ALLOWLIST_PACKAGES.contains(pkg)) continue
                if (apps.containsKey(pkg)) continue

                val appInfo = pkgInfo.applicationInfo ?: continue
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystemApp = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                // Include user-installed apps or apps that have a launch intent
                val hasLaunchIntent = pm.getLaunchIntentForPackage(pkg) != null
                if (!isSystemApp || isUpdatedSystemApp || hasLaunchIntent) {
                    val label = appInfo.loadLabel(pm).toString()
                    apps[pkg] = TargetApp(packageName = pkg, appName = label)
                }
            }
        } catch (e: Exception) {
            // Fallback to launcher resolve infos
        }

        // Return sorted by app name
        apps.values.sortedBy { it.appName.lowercase() }
    }
}
