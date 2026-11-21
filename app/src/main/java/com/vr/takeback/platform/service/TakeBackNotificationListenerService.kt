package com.vr.takeback.platform.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.vr.takeback.data.repository.ProfileRepository
import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.engine.SystemContextSnapshot
import com.vr.takeback.domain.model.BlockDecision
import java.time.LocalDate
import java.time.LocalTime

class TakeBackNotificationListenerService : NotificationListenerService() {

    private val engine = CentralRestrictionEngine()
    private val profileRepository = ProfileRepository()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName ?: return

        // Never mute TakeBack itself or emergency dialer
        if (packageName == applicationContext.packageName) return
        if (CentralRestrictionEngine.EMERGENCY_ALLOWLIST_PACKAGES.contains(packageName)) return

        // Evaluate whether the app is currently restricted
        val now = System.currentTimeMillis()
        val localTime = LocalTime.now()
        val dayOfWeek = LocalDate.now().dayOfWeek

        val snapshot = SystemContextSnapshot(
            timestampMillis = now,
            localTime = localTime,
            dayOfWeek = dayOfWeek,
            foregroundPackageName = packageName
        )

        val activeProfiles = profileRepository.profiles.value
        val decision = engine.evaluate(activeProfiles, snapshot)

        if (decision is BlockDecision.Blocked) {
            // Dismiss/suppress notification from restricted app during active focus
            try {
                cancelNotification(sbn.key)
            } catch (e: Exception) {
                // Ignore if permission revoked
            }
        }
    }
}
