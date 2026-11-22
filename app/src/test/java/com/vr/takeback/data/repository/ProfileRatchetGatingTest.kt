package com.vr.takeback.data.repository

import com.vr.takeback.domain.model.Profile
import com.vr.takeback.domain.model.TargetApp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProfileRatchetGatingTest {

    private lateinit var repository: ProfileRepository
    private var isStrictActive = false

    @Before
    fun setUp() {
        isStrictActive = false
        repository = ProfileRepository(
            context = null,
            isStrictActiveProvider = { isStrictActive }
        )

        // Seed initial profiles
        val p1 = Profile(
            id = "work_profile",
            name = "Work Profile",
            isEnabled = true,
            targetApps = listOf(
                TargetApp("com.instagram.android", "Instagram"),
                TargetApp("com.reddit.frontpage", "Reddit")
            )
        )
        val p2 = Profile(
            id = "study_profile",
            name = "Study Profile",
            isEnabled = false,
            targetApps = listOf(
                TargetApp("com.google.android.youtube", "YouTube")
            )
        )
        repository.saveProfile(p1, isSystemOrigin = true)
        repository.saveProfile(p2, isSystemOrigin = true)
    }

    // 1. In Strict Mode, active profiles CANNOT be disabled
    @Test
    fun strictMode_ratchet_cannotDisableActiveProfile() {
        isStrictActive = true

        val toggleResult = repository.toggleProfile("work_profile")
        assertFalse("Disabling active profile in Strict Mode must be strictly rejected", toggleResult)

        val profile = repository.getProfileById("work_profile")
        assertNotNull(profile)
        assertTrue("Profile must remain enabled", profile!!.isEnabled)
    }

    // 2. In Strict Mode, inactive profiles CAN be enabled
    @Test
    fun strictMode_ratchet_canEnableInactiveProfile() {
        isStrictActive = true

        val toggleResult = repository.toggleProfile("study_profile")
        assertTrue("Enabling inactive profile in Strict Mode must be allowed", toggleResult)

        val profile = repository.getProfileById("study_profile")
        assertNotNull(profile)
        assertTrue("Profile must become enabled", profile!!.isEnabled)
    }

    // 3. In Strict Mode, user CAN add apps to an existing profile
    @Test
    fun strictMode_ratchet_canAddAppsToProfile() {
        isStrictActive = true

        val existing = repository.getProfileById("work_profile")!!
        val updatedApps = existing.targetApps + TargetApp("com.tiktok.android", "TikTok")
        val saveResult = repository.saveProfile(existing.copy(targetApps = updatedApps))

        assertTrue("Adding new target apps in Strict Mode must be allowed", saveResult)
        val updated = repository.getProfileById("work_profile")!!
        assertEquals(3, updated.targetApps.size)
        assertTrue(updated.targetApps.any { it.packageName == "com.tiktok.android" })
    }

    // 4. In Strict Mode, user CANNOT remove apps from an existing profile
    @Test
    fun strictMode_ratchet_cannotRemoveAppsFromProfile() {
        isStrictActive = true

        val existing = repository.getProfileById("work_profile")!!
        // Remove Instagram, keep only Reddit
        val reducedApps = existing.targetApps.filter { it.packageName == "com.reddit.frontpage" }
        val saveResult = repository.saveProfile(existing.copy(targetApps = reducedApps))

        assertFalse("Removing target apps in Strict Mode must be strictly rejected", saveResult)
        val afterAttempt = repository.getProfileById("work_profile")!!
        assertEquals(2, afterAttempt.targetApps.size)
        assertTrue(afterAttempt.targetApps.any { it.packageName == "com.instagram.android" })
    }

    // 5. In Strict Mode, user CANNOT delete a profile
    @Test
    fun strictMode_ratchet_cannotDeleteProfile() {
        isStrictActive = true

        val deleteResult = repository.deleteProfile("work_profile")
        assertFalse("Deleting profiles in Strict Mode must be strictly rejected", deleteResult)
        assertNotNull("Profile must still exist in repository", repository.getProfileById("work_profile"))
    }
}
