package com.vr.takeback

import com.vr.takeback.data.repository.BillingRepository
import com.vr.takeback.data.repository.ProFeature
import com.vr.takeback.data.repository.SubscriptionTier
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BillingEntitlementTest {

    private lateinit var billingRepository: BillingRepository

    @Before
    fun setUp() {
        // Initialize in-memory (context = null uses defaults without SharedPreferences crash)
        billingRepository = BillingRepository(context = null)
    }

    @Test
    fun initialDefaultState_isFreeTier() {
        val state = billingRepository.entitlementState.value
        assertEquals(SubscriptionTier.FREE, state.tier)
        assertFalse(state.isSubscribed)
        assertFalse(state.isProUnlocked)
    }

    @Test
    fun freeTier_limitsProfileCreationToThree() {
        assertTrue(billingRepository.canCreateNewProfile(0))
        assertTrue(billingRepository.canCreateNewProfile(1))
        assertTrue(billingRepository.canCreateNewProfile(2))
        assertFalse(billingRepository.canCreateNewProfile(3))
        assertFalse(billingRepository.canCreateNewProfile(5))
    }

    @Test
    fun purchasingPro_unlocksFeaturesAndUnlimitedProfiles() {
        billingRepository.purchaseTier(SubscriptionTier.PRO_YEARLY)
        val state = billingRepository.entitlementState.value

        assertEquals(SubscriptionTier.PRO_YEARLY, state.tier)
        assertTrue(state.isSubscribed)
        assertTrue(state.isProUnlocked)

        // Features unlocked
        assertTrue(billingRepository.isFeatureUnlocked(ProFeature.UNLIMITED_PROFILES))
        assertTrue(billingRepository.isFeatureUnlocked(ProFeature.ADVANCED_INSIGHTS_EXPORT))
        assertTrue(billingRepository.isFeatureUnlocked(ProFeature.CUSTOM_SOUND_MIXER))

        // Unlimited profiles unlocked
        assertTrue(billingRepository.canCreateNewProfile(3))
        assertTrue(billingRepository.canCreateNewProfile(100))
    }

    @Test
    fun cancellation_revertsToFreeTier() {
        billingRepository.purchaseTier(SubscriptionTier.PRO_MONTHLY)
        assertTrue(billingRepository.entitlementState.value.isProUnlocked)

        billingRepository.cancelSubscription()
        val state = billingRepository.entitlementState.value

        assertEquals(SubscriptionTier.FREE, state.tier)
        assertFalse(state.isSubscribed)
        assertFalse(state.isProUnlocked)
        assertFalse(billingRepository.canCreateNewProfile(3))
    }
}
