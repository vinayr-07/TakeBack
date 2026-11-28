package com.vr.takeback.platform.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.vr.takeback.domain.security.PackageInfoProvider

/**
 * Standard Android implementation of PackageInfoProvider.
 * Batches and caches PackageManager / Telecom / InputMethod Binder IPC queries (60s TTL)
 * so high-frequency AccessibilityService event paths execute in microseconds without main-thread stalls or ANRs.
 */
class AndroidPackageInfoProvider(private val context: Context) : PackageInfoProvider {

    companion object {
        private const val CACHE_TTL_MILLIS = 60_000L
    }

    private data class Snapshot(
        val capturedAtElapsedMillis: Long,
        val installedPackages: Set<String>,
        val systemPackages: Set<String>,
        val updatedSystemPackages: Set<String>,
        val categoriesByPackage: Map<String, Int>,
        val defaultDialer: String?,
        val defaultSms: String?,
        val cameraPackages: Set<String>,
        val homeLauncherPackages: Set<String>,
        val inputMethodPackages: Set<String>
    )

    private val pm: PackageManager = context.packageManager

    @Volatile
    private var cachedSnapshot: Snapshot? = null
    private val snapshotLock = Any()

    fun refreshSnapshotNow() {
        cachedSnapshot = buildSnapshot()
    }

    private fun getSnapshot(): Snapshot {
        val now = SystemClock.elapsedRealtime()
        val existing = cachedSnapshot
        if (existing != null && (now - existing.capturedAtElapsedMillis) < CACHE_TTL_MILLIS) {
            return existing
        }
        synchronized(snapshotLock) {
            val current = cachedSnapshot
            val refreshedNow = SystemClock.elapsedRealtime()
            if (current != null && (refreshedNow - current.capturedAtElapsedMillis) < CACHE_TTL_MILLIS) {
                return current
            }
            return buildSnapshot().also { cachedSnapshot = it }
        }
    }

    private fun buildSnapshot(): Snapshot {
        val installed = HashSet<String>(256)
        val systemPkgs = HashSet<String>(192)
        val updatedSystemPkgs = HashSet<String>(64)
        val categories = HashMap<String, Int>(256)

        try {
            val pkgInfos = pm.getInstalledPackages(0)
            for (pkgInfo in pkgInfos) {
                val name = pkgInfo.packageName ?: continue
                installed.add(name)
                val appInfo = pkgInfo.applicationInfo
                if (appInfo != null) {
                    if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                        systemPkgs.add(name)
                    }
                    if ((appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                        updatedSystemPkgs.add(name)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        categories[name] = appInfo.category
                    }
                }
            }
        } catch (_: Exception) {
        }

        val dialer = try {
            (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        } catch (_: Exception) {
            null
        }

        val sms = try {
            Telephony.Sms.getDefaultSmsPackage(context)
        } catch (_: Exception) {
            null
        }

        val cameras = try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val launchers = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val imes = try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }

        return Snapshot(
            capturedAtElapsedMillis = SystemClock.elapsedRealtime(),
            installedPackages = installed,
            systemPackages = systemPkgs,
            updatedSystemPackages = updatedSystemPkgs,
            categoriesByPackage = categories,
            defaultDialer = dialer,
            defaultSms = sms,
            cameraPackages = cameras,
            homeLauncherPackages = launchers,
            inputMethodPackages = imes
        )
    }

    override fun isSystemApp(packageName: String): Boolean {
        return getSnapshot().systemPackages.contains(packageName)
    }

    override fun isUpdatedSystemApp(packageName: String): Boolean {
        return getSnapshot().updatedSystemPackages.contains(packageName)
    }

    override fun getDefaultDialerPackage(): String? = getSnapshot().defaultDialer

    override fun getDefaultSmsPackage(): String? = getSnapshot().defaultSms

    override fun getCameraPackages(): Set<String> = getSnapshot().cameraPackages

    override fun getHomeLauncherPackages(): Set<String> = getSnapshot().homeLauncherPackages

    override fun getInputMethodPackages(): Set<String> = getSnapshot().inputMethodPackages

    override fun getInstalledPackages(): Set<String> = getSnapshot().installedPackages

    override fun getApplicationCategory(packageName: String): Int? = getSnapshot().categoriesByPackage[packageName]
}
