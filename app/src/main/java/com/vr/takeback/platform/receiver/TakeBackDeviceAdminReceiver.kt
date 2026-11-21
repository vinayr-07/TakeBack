package com.vr.takeback.platform.receiver

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vr.takeback.R
import com.vr.takeback.TakeBackApplication

/**
 * Standard Android Device Administrator for TakeBack.
 *
 * ARCHITECTURAL CONSTRAINTS & GUARANTEES:
 * 1. Standard Device Administrator ONLY. This application is NOT a Device Owner or Managed Profile.
 *    No wipeData(), factory reset, or destructive administrative policies are ever requested or executed.
 * 2. Prerequisite for Strict Mode: TakeBack enforces that Strict Mode CANNOT be armed unless
 *    DevicePolicyManager.isAdminActive() returns true.
 * 3. Deactivation handling:
 *    - onDisableRequested() supplies an official system warning.
 *    - onDisabled() detects revocation, preserves existing profiles and user data, and marks
 *      the Strict Mode state as DEGRADED / COMPROMISED.
 */
class TakeBackDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        const val TAG = "TakeBackDeviceAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, TakeBackDeviceAdminReceiver::class.java)
        }

        /**
         * Checks whether TakeBack Device Administrator is actively granted in Android DevicePolicyManager.
         * Never trusts a local SharedPreferences flag.
         */
        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return dpm.isAdminActive(getComponentName(context))
        }

        /**
         * Creates an Intent to launch Android's official Device Admin activation screen.
         */
        fun createAddDeviceAdminIntent(context: Context): Intent {
            return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getComponentName(context))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    context.getString(R.string.device_admin_description)
                )
            }
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "TakeBack Standard Device Administrator activated")
        try {
            TakeBackApplication.strictModeRepository.syncAdminState(isAdminActive = true)
        } catch (e: Exception) {
            Log.w(TAG, "Error syncing admin state on enabled", e)
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "TakeBack Standard Device Administrator deactivation requested by user")
        val strictRepo = try { TakeBackApplication.strictModeRepository } catch (e: Exception) { null }
        val isStrictActive = strictRepo?.protectionState?.value?.isGlobalStrictActive == true
        return if (isStrictActive) {
            context.getString(R.string.device_admin_strict_warning)
        } else {
            context.getString(R.string.device_admin_warning)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "TakeBack Standard Device Administrator deactivated. Marking Strict Mode degraded.")
        try {
            // Preserve all profiles and configuration, but mark protection status as COMPROMISED
            TakeBackApplication.strictModeRepository.syncAdminState(isAdminActive = false)
        } catch (e: Exception) {
            Log.w(TAG, "Error syncing admin state on disabled", e)
        }
    }
}
