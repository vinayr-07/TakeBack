package com.vr.takeback.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

enum class SubscriptionTier(val title: String, val priceDisplay: String) {
    FREE("TakeBack Free", "$0.00"),
    PRO_MONTHLY("TakeBack Pro Monthly", "$2.99 / month"),
    PRO_YEARLY("TakeBack Pro Yearly", "$19.99 / year"),
    LIFETIME("TakeBack Lifetime", "$49.99 once")
}

enum class ProFeature {
    UNLIMITED_PROFILES,
    ADVANCED_INSIGHTS_EXPORT,
    CUSTOM_SOUND_MIXER
}

data class EntitlementState(
    val tier: SubscriptionTier = SubscriptionTier.FREE,
    val isSubscribed: Boolean = false,
    val expirationEpochMillis: Long? = null,
    val lastVerifiedEpochMillis: Long = System.currentTimeMillis()
) {
    val isProUnlocked: Boolean
        get() = isSubscribed && (expirationEpochMillis == null || expirationEpochMillis > System.currentTimeMillis())
}

/**
 * Repository governing Google Play Billing abstraction and entitlement verification.
 *
 * ETHICAL PRODUCT PROMISE:
 * Core safety, app blocking, site blocking, Strict Mode, and scheduled restrictions
 * are PERMANENTLY FREE and never locked behind a paywall. Pro features are strictly
 * value-add productivity enhancements.
 */
class BillingRepository(context: Context? = null) {

    private val prefs = context?.getSharedPreferences("takeback_billing_prefs", Context.MODE_PRIVATE)

    private val _entitlementState = MutableStateFlow(loadPersistedState())
    val entitlementState: StateFlow<EntitlementState> = _entitlementState.asStateFlow()

    private fun loadPersistedState(): EntitlementState {
        if (prefs == null) return EntitlementState()

        val tierName = prefs.getString("subscription_tier", SubscriptionTier.FREE.name) ?: SubscriptionTier.FREE.name
        val tier = try { SubscriptionTier.valueOf(tierName) } catch (e: Exception) { SubscriptionTier.FREE }
        val isSubscribed = prefs.getBoolean("is_subscribed", false)
        val exp = if (prefs.contains("expiration_millis")) prefs.getLong("expiration_millis", 0L) else null
        val lastVerified = prefs.getLong("last_verified_millis", System.currentTimeMillis())

        // Offline grace period check (30 days)
        val thirtyDaysMillis = TimeUnit.DAYS.toMillis(30)
        val now = System.currentTimeMillis()
        val isWithinGracePeriod = (now - lastVerified) <= thirtyDaysMillis

        val finalSubscribed = isSubscribed && (exp == null || exp > now) && isWithinGracePeriod

        return EntitlementState(
            tier = if (finalSubscribed) tier else SubscriptionTier.FREE,
            isSubscribed = finalSubscribed,
            expirationEpochMillis = exp,
            lastVerifiedEpochMillis = lastVerified
        )
    }

    /**
     * Checks whether a specific Pro feature is unlocked.
     */
    fun isFeatureUnlocked(feature: ProFeature): Boolean {
        return _entitlementState.value.isProUnlocked
    }

    /**
     * Determines if a user can create another profile.
     * Free tier allows up to 3 profiles; Pro allows unlimited.
     */
    fun canCreateNewProfile(currentProfileCount: Int): Boolean {
        if (_entitlementState.value.isProUnlocked) return true
        return currentProfileCount < 3
    }

    /**
     * Simulates or executes Google Play Billing purchase.
     */
    fun purchaseTier(tier: SubscriptionTier): Boolean {
        if (tier == SubscriptionTier.FREE) {
            cancelSubscription()
            return true
        }

        val now = System.currentTimeMillis()
        val expirationMillis = when (tier) {
            SubscriptionTier.PRO_MONTHLY -> now + TimeUnit.DAYS.toMillis(30)
            SubscriptionTier.PRO_YEARLY -> now + TimeUnit.DAYS.toMillis(365)
            SubscriptionTier.LIFETIME -> null
            SubscriptionTier.FREE -> null
        }

        val newState = EntitlementState(
            tier = tier,
            isSubscribed = true,
            expirationEpochMillis = expirationMillis,
            lastVerifiedEpochMillis = now
        )

        persistState(newState)
        _entitlementState.value = newState
        return true
    }

    /**
     * Cancels / downgrades subscription back to Free.
     */
    fun cancelSubscription() {
        val newState = EntitlementState(
            tier = SubscriptionTier.FREE,
            isSubscribed = false,
            expirationEpochMillis = null,
            lastVerifiedEpochMillis = System.currentTimeMillis()
        )
        persistState(newState)
        _entitlementState.value = newState
    }

    private fun persistState(state: EntitlementState) {
        prefs?.edit()?.apply {
            putString("subscription_tier", state.tier.name)
            putBoolean("is_subscribed", state.isSubscribed)
            if (state.expirationEpochMillis != null) {
                putLong("expiration_millis", state.expirationEpochMillis)
            } else {
                remove("expiration_millis")
            }
            putLong("last_verified_millis", state.lastVerifiedEpochMillis)
            apply()
        }
    }
}
