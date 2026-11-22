package com.vr.takeback.platform.util

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.vr.takeback.domain.security.PackageInfoProvider

/**
 * Standard Android implementation of PackageInfoProvider.
 * Queries Android PackageManager, TelecomManager, Telephony, and InputMethodManager
 * using official public platform APIs.
 */
class AndroidPackageInfoProvider(private val context: Context) : PackageInfoProvider {

    private val pm: PackageManager = context.packageManager

    override fun isSystemApp(packageName: String): Boolean {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            false
        }
    }

    override fun isUpdatedSystemApp(packageName: String): Boolean {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (e: Exception) {
            false
        }
    }

    override fun getDefaultDialerPackage(): String? {
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage
        } catch (e: Exception) {
            null
        }
    }

    override fun getDefaultSmsPackage(): String? {
        return try {
            Telephony.Sms.getDefaultSmsPackage(context)
        } catch (e: Exception) {
            null
        }
    }

    override fun getCameraPackages(): Set<String> {
        return try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    override fun getHomeLauncherPackages(): Set<String> {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    override fun getInputMethodPackages(): Set<String> {
        return try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    override fun getInstalledPackages(): Set<String> {
        return try {
            pm.getInstalledPackages(0).map { it.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    override fun getApplicationCategory(packageName: String): Int? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                appInfo.category
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
