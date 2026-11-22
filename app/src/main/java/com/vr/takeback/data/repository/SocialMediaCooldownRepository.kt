package com.vr.takeback.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlin.math.ceil

/**
 * Thread-safe, persistent storage for social media session limits and mandatory cooldowns.
 * Uses private SharedPreferences to persist cooldown timestamps across process death and phone reboots.
 */
class SocialMediaCooldownRepository(
    private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "takeback_social_cooldown_prefs"
        private const val KEY_COOLDOWN_UNTIL_EPOCH = "cooldown_until_epoch_millis"
        private const val KEY_COOLDOWN_TRIGGERED_MONOTONIC = "cooldown_triggered_monotonic_millis"
        const val SESSION_LIMIT_MILLIS = 5 * 60 * 1000L // 5 minutes (300,000 ms)
        const val COOLDOWN_DURATION_MILLIS = 55 * 60 * 1000L // 55 minutes (3,300,000 ms)
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isCooldownActive(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        val cooldownUntil = getCooldownUntilEpochMillis()
        return nowEpochMillis < cooldownUntil
    }

    fun getRemainingCooldownMillis(nowEpochMillis: Long = System.currentTimeMillis()): Long {
        val cooldownUntil = getCooldownUntilEpochMillis()
        return maxOf(0L, cooldownUntil - nowEpochMillis)
    }

    fun getRemainingCooldownMinutes(nowEpochMillis: Long = System.currentTimeMillis()): Int {
        val remainingMillis = getRemainingCooldownMillis(nowEpochMillis)
        return ceil(remainingMillis / 60000.0).toInt().coerceAtLeast(0)
    }

    fun getCooldownUntilEpochMillis(): Long {
        return prefs.getLong(KEY_COOLDOWN_UNTIL_EPOCH, 0L)
    }

    fun setCooldownUntilEpochMillis(epochMillis: Long) {
        prefs.edit()
            .putLong(KEY_COOLDOWN_UNTIL_EPOCH, epochMillis)
            .putLong(KEY_COOLDOWN_TRIGGERED_MONOTONIC, SystemClock.elapsedRealtime())
            .apply()
    }

    fun triggerCooldown(
        nowEpochMillis: Long = System.currentTimeMillis(),
        durationMillis: Long = COOLDOWN_DURATION_MILLIS
    ) {
        val targetEpoch = nowEpochMillis + durationMillis
        setCooldownUntilEpochMillis(targetEpoch)
    }

    fun clearCooldown() {
        prefs.edit()
            .remove(KEY_COOLDOWN_UNTIL_EPOCH)
            .remove(KEY_COOLDOWN_TRIGGERED_MONOTONIC)
            .apply()
    }
}
