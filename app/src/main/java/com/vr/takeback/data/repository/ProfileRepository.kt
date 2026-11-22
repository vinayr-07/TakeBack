package com.vr.takeback.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.vr.takeback.TakeBackApplication
import com.vr.takeback.domain.model.*
import com.vr.takeback.domain.security.MutationType
import com.vr.takeback.domain.security.StrictMutationGate
import com.vr.takeback.platform.alarm.ScheduleCoordinator
import com.vr.takeback.platform.service.TakeBackAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ProfileRepository(
    context: Context? = null,
    private val isStrictActiveProvider: (() -> Boolean)? = null
) {

    companion object {
        private const val TAG = "ProfileRepository"
        private const val PREFS_NAME = "takeback_profiles_prefs"
        private const val KEY_SAVED_PROFILES = "saved_profiles_json"
    }

    private val appContext: Context? = context?.applicationContext
        ?: runCatching { TakeBackApplication.instance.applicationContext }.getOrNull()

    private val prefs: SharedPreferences? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow<List<Profile>>(loadProfiles())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    fun getProfileById(id: String): Profile? {
        return _profiles.value.find { it.id == id }
    }

    /**
     * Toggles profile enabled state.
     * Enforces Strict Mode Ratchet Protocol: users can enable profiles in Strict Mode,
     * but cannot disable active profiles.
     */
    fun toggleProfile(id: String, token: String? = null, isSystemOrigin: Boolean = false): Boolean {
        val currentProfile = getProfileById(id) ?: return false
        val willEnable = !currentProfile.isEnabled
        val isStrict = isStrictActive()

        if (isStrict && !isSystemOrigin) {
            // Ratchet rule: Cannot disable active profiles in Strict Mode
            if (!willEnable) {
                Log.w(TAG, "Rejected toggleProfile($id): Cannot disable profile while Strict Mode is active")
                return false
            }
            // willEnable == true: Enabling profiles in Strict Mode is allowed!
        }

        val updated = _profiles.value.map { profile ->
            if (profile.id == id) {
                profile.copy(isEnabled = willEnable, updatedAtMillis = System.currentTimeMillis())
            } else profile
        }
        _profiles.value = updated
        persistProfiles(updated)
        notifySystemOfProfileChange(updated)
        return true
    }

    /**
     * Saves or creates a profile.
     * Enforces Strict Mode Ratchet Protocol: users can add apps/websites to a profile,
     * but cannot remove existing apps or websites while Strict Mode is active.
     */
    fun saveProfile(profile: Profile, token: String? = null, isSystemOrigin: Boolean = false): Boolean {
        val isStrict = isStrictActive()
        val existing = getProfileById(profile.id)
        val isNew = existing == null

        if (isStrict && !isSystemOrigin) {
            if (isNew) {
                // Creating a new profile: Allowed! Adds restrictions.
            } else {
                // Ratchet rule: Cannot remove apps from profile while Strict Mode is active
                val existingTargetPackages = existing.targetApps.map { it.packageName }.toSet()
                val newTargetPackages = profile.targetApps.map { it.packageName }.toSet()
                val removedPackages = existingTargetPackages - newTargetPackages
                if (removedPackages.isNotEmpty()) {
                    Log.w(TAG, "Rejected saveProfile(${profile.id}): Cannot remove apps ($removedPackages) while Strict Mode is active")
                    return false
                }

                // Ratchet rule: Cannot remove websites from profile while Strict Mode is active
                val existingWebsites = existing.targetWebsites.map { it.domainPattern }.toSet()
                val newWebsites = profile.targetWebsites.map { it.domainPattern }.toSet()
                val removedWebsites = existingWebsites - newWebsites
                if (removedWebsites.isNotEmpty()) {
                    Log.w(TAG, "Rejected saveProfile(${profile.id}): Cannot remove websites ($removedWebsites) while Strict Mode is active")
                    return false
                }
            }
        }

        val current = _profiles.value.toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            current[index] = profile.copy(updatedAtMillis = System.currentTimeMillis())
        } else {
            current.add(profile)
        }
        _profiles.value = current
        persistProfiles(current)
        notifySystemOfProfileChange(current)
        return true
    }

    /**
     * Deletes a profile.
     * Enforces Strict Mode Ratchet Protocol: profiles cannot be deleted while Strict Mode is active.
     */
    fun deleteProfile(id: String, token: String? = null): Boolean {
        val isStrict = isStrictActive()
        if (isStrict) {
            Log.w(TAG, "Rejected deleteProfile($id): Cannot delete profiles while Strict Mode is active")
            return false
        }

        val updated = _profiles.value.filter { it.id != id }
        _profiles.value = updated
        persistProfiles(updated)
        notifySystemOfProfileChange(updated)
        return true
    }

    /**
     * Duplicates an existing profile.
     * Enforces StrictMutationGate when Strict Mode is active.
     */
    fun duplicateProfile(id: String, token: String? = null): Profile? {
        val isStrict = isStrictActive()
        if (isStrict) {
            val valid = StrictMutationGate.consumeToken(token, MutationType.DUPLICATE_PROFILE, id)
            if (!valid) {
                Log.w(TAG, "Rejected duplicateProfile($id): Unauthorized under Strict Mode")
                return null
            }
        }

        val original = getProfileById(id) ?: return null
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            name = "${original.name} (Copy)",
            isPreset = false,
            createdAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis()
        )
        saveProfile(copy, token = null, isSystemOrigin = true)
        return copy
    }

    fun getAllRestrictedPackages(onlyEnabled: Boolean = true): Set<String> {
        val targetList = if (onlyEnabled) _profiles.value.filter { it.isEnabled } else _profiles.value
        val packages = mutableSetOf<String>()
        for (profile in targetList) {
            for (app in profile.targetApps) {
                if (!app.isAllowlist) {
                    packages.add(app.packageName)
                }
            }
        }
        return packages
    }

    private fun isStrictActive(): Boolean {
        if (isStrictActiveProvider != null) {
            return isStrictActiveProvider.invoke()
        }
        return try {
            TakeBackApplication.strictModeRepository.protectionState.value.isCurrentlyLocked
        } catch (e: Exception) {
            // Section 3.3 Fail-closed: An exception while reading Strict Mode state must NOT be interpreted as disabled.
            Log.e(TAG, "Failed to read Strict Mode state; failing closed", e)
            true
        }
    }

    private fun notifySystemOfProfileChange(updatedProfiles: List<Profile>) {
        appContext?.let { ctx ->
            try {
                ScheduleCoordinator.onProfilesChanged(ctx, updatedProfiles)
                TakeBackAccessibilityService.notifyScheduleBoundary(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "Error notifying system components of profile change", e)
            }
        }
    }

    private fun persistProfiles(list: List<Profile>) {
        if (prefs == null) return
        try {
            val jsonArray = JSONArray()
            for (p in list) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("iconName", p.iconName)
                    put("colorHex", p.colorHex)
                    put("isEnabled", p.isEnabled)
                    put("isPreset", p.isPreset)
                    put("createdAtMillis", p.createdAtMillis)
                    put("updatedAtMillis", p.updatedAtMillis)

                    val appsArray = JSONArray()
                    for (app in p.targetApps) {
                        appsArray.put(JSONObject().apply {
                            put("packageName", app.packageName)
                            put("appName", app.appName)
                            put("isAllowlist", app.isAllowlist)
                        })
                    }
                    put("targetApps", appsArray)

                    val websitesArray = JSONArray()
                    for (web in p.targetWebsites) {
                        websitesArray.put(JSONObject().apply {
                            put("domainPattern", web.domainPattern)
                            put("ruleType", web.ruleType.name)
                            put("isAllowlist", web.isAllowlist)
                        })
                    }
                    put("targetWebsites", websitesArray)

                    val schedulesArray = JSONArray()
                    for (s in p.schedules) {
                        schedulesArray.put(JSONObject().apply {
                            put("id", s.id)
                            put("profileId", s.profileId)
                            put("daysOfWeekMask", s.daysOfWeekMask)
                            put("startTimeSeconds", s.startTimeSeconds)
                            put("endTimeSeconds", s.endTimeSeconds)
                            put("isEnabled", s.isEnabled)
                        })
                    }
                    put("schedules", schedulesArray)

                    val limitsArray = JSONArray()
                    for (lim in p.usageLimits) {
                        limitsArray.put(JSONObject().apply {
                            put("id", lim.id)
                            put("profileId", lim.profileId)
                            if (lim.targetPackageName != null) {
                                put("targetPackageName", lim.targetPackageName)
                            }
                            put("dailyLimitMinutes", lim.dailyLimitMinutes)
                            put("windowType", lim.windowType.name)
                            put("isEnabled", lim.isEnabled)
                        })
                    }
                    put("usageLimits", limitsArray)

                    if (p.strictModeConfig != null) {
                        val sm = p.strictModeConfig
                        val smObj = JSONObject().apply {
                            put("profileId", sm.profileId)
                            put("isStrictActive", sm.isStrictActive)
                            put("mechanism", sm.mechanism.name)
                            put("saltBase64", sm.saltBase64)
                            put("passwordHash", sm.passwordHash)
                            put("delayMinutes", sm.delayMinutes)
                        }
                        put("strictModeConfig", smObj)
                    }
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_SAVED_PROFILES, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist profiles to SharedPreferences", e)
        }
    }

    private fun loadProfiles(): List<Profile> {
        val jsonStr = prefs?.getString(KEY_SAVED_PROFILES, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<Profile>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.getString("id")
                    val name = obj.getString("name")
                    val iconName = obj.optString("iconName", "auto_stories")
                    val colorHex = obj.optString("colorHex", "#10B981")
                    val isEnabled = obj.optBoolean("isEnabled", false)
                    val isPreset = obj.optBoolean("isPreset", false)
                    val createdAt = obj.optLong("createdAtMillis", System.currentTimeMillis())
                    val updatedAt = obj.optLong("updatedAtMillis", System.currentTimeMillis())

                    val targetApps = mutableListOf<TargetApp>()
                    val appsArray = obj.optJSONArray("targetApps")
                    if (appsArray != null) {
                        for (j in 0 until appsArray.length()) {
                            val aObj = appsArray.getJSONObject(j)
                            targetApps.add(
                                TargetApp(
                                    packageName = aObj.getString("packageName"),
                                    appName = aObj.getString("appName"),
                                    isAllowlist = aObj.optBoolean("isAllowlist", false)
                                )
                            )
                        }
                    }

                    val targetWebsites = mutableListOf<TargetWebsite>()
                    val webArray = obj.optJSONArray("targetWebsites")
                    if (webArray != null) {
                        for (j in 0 until webArray.length()) {
                            val wObj = webArray.getJSONObject(j)
                            targetWebsites.add(
                                TargetWebsite(
                                    domainPattern = wObj.getString("domainPattern"),
                                    ruleType = try {
                                        WebsiteRuleType.valueOf(wObj.optString("ruleType", "EXACT_DOMAIN"))
                                    } catch (e: Exception) {
                                        WebsiteRuleType.EXACT_DOMAIN
                                    },
                                    isAllowlist = wObj.optBoolean("isAllowlist", false)
                                )
                            )
                        }
                    }

                    val schedules = mutableListOf<Schedule>()
                    val schArray = obj.optJSONArray("schedules")
                    if (schArray != null) {
                        for (j in 0 until schArray.length()) {
                            val sObj = schArray.getJSONObject(j)
                            schedules.add(
                                Schedule(
                                    id = sObj.optLong("id", System.currentTimeMillis()),
                                    profileId = sObj.optString("profileId", id),
                                    daysOfWeekMask = sObj.optInt("daysOfWeekMask", 127),
                                    startTimeSeconds = sObj.optInt("startTimeSeconds", 9 * 3600),
                                    endTimeSeconds = sObj.optInt("endTimeSeconds", 17 * 3600),
                                    isEnabled = sObj.optBoolean("isEnabled", true)
                                )
                            )
                        }
                    }

                    val limits = mutableListOf<UsageLimit>()
                    val limArray = obj.optJSONArray("usageLimits")
                    if (limArray != null) {
                        for (j in 0 until limArray.length()) {
                            val lObj = limArray.getJSONObject(j)
                            val targetPkg = if (lObj.has("targetPackageName") && !lObj.isNull("targetPackageName")) {
                                lObj.getString("targetPackageName")
                            } else null
                            val windowType = try {
                                LimitWindowType.valueOf(lObj.optString("windowType", "FULL_DAY"))
                            } catch (e: Exception) {
                                LimitWindowType.FULL_DAY
                            }

                            limits.add(
                                UsageLimit(
                                    id = lObj.optLong("id", System.currentTimeMillis()),
                                    profileId = lObj.optString("profileId", id),
                                    targetPackageName = targetPkg,
                                    dailyLimitMinutes = lObj.optInt("dailyLimitMinutes", 30),
                                    windowType = windowType,
                                    isEnabled = lObj.optBoolean("isEnabled", true)
                                )
                            )
                        }
                    }

                    var strictConfig: StrictModeConfig? = null
                    val smObj = obj.optJSONObject("strictModeConfig")
                    if (smObj != null) {
                        val smMech = try {
                            StrictMechanism.valueOf(smObj.optString("mechanism", "PIN"))
                        } catch (e: Exception) {
                            StrictMechanism.PIN
                        }
                        strictConfig = StrictModeConfig(
                            profileId = smObj.optString("profileId", id),
                            isStrictActive = smObj.optBoolean("isStrictActive", false),
                            mechanism = smMech,
                            saltBase64 = if (smObj.has("saltBase64") && !smObj.isNull("saltBase64")) smObj.getString("saltBase64") else null,
                            passwordHash = if (smObj.has("passwordHash") && !smObj.isNull("passwordHash")) smObj.getString("passwordHash") else null,
                            delayMinutes = smObj.optInt("delayMinutes", 5)
                        )
                    }

                    list.add(
                        Profile(
                            id = id,
                            name = name,
                            iconName = iconName,
                            colorHex = colorHex,
                            isEnabled = isEnabled,
                            isPreset = isPreset,
                            createdAtMillis = createdAt,
                            updatedAtMillis = updatedAt,
                            targetApps = targetApps,
                            targetWebsites = targetWebsites,
                            schedules = schedules,
                            usageLimits = limits,
                            strictModeConfig = strictConfig
                        )
                    )
                }
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                Log.e(TAG, "Error deserializing profiles JSON, falling back", e)
            }
        }

        val defaults = createDefaultProfiles()
        if (prefs != null) {
            persistProfiles(defaults)
        }
        return defaults
    }

    private fun createDefaultProfiles(): List<Profile> {
        return listOf(
            Profile(
                id = "study_preset",
                name = "Deep Study",
                iconName = "menu_book",
                colorHex = "#10B981", // Emerald
                isEnabled = false,
                isPreset = true,
                targetApps = listOf(
                    TargetApp("com.instagram.android", "Instagram"),
                    TargetApp("com.zhiliaoapp.musically", "TikTok"),
                    TargetApp("com.google.android.youtube", "YouTube"),
                    TargetApp("com.reddit.frontpage", "Reddit"),
                    TargetApp("com.twitter.android", "X / Twitter")
                ),
                targetWebsites = listOf(
                    TargetWebsite("reddit.com"),
                    TargetWebsite("twitter.com"),
                    TargetWebsite("x.com"),
                    TargetWebsite("instagram.com"),
                    TargetWebsite("tiktok.com")
                ),
                schedules = listOf(
                    Schedule(
                        id = 1,
                        profileId = "study_preset",
                        daysOfWeekMask = 31, // Mon-Fri
                        startTimeSeconds = 14 * 3600, // 14:00
                        endTimeSeconds = 18 * 3600   // 18:00
                    )
                )
            ),
            Profile(
                id = "work_preset",
                name = "Work Hours",
                iconName = "business_center",
                colorHex = "#6366F1", // Indigo
                isEnabled = false,
                isPreset = true,
                targetApps = listOf(
                    TargetApp("com.facebook.katana", "Facebook"),
                    TargetApp("com.snapchat.android", "Snapchat"),
                    TargetApp("com.reddit.frontpage", "Reddit")
                ),
                schedules = listOf(
                    Schedule(
                        id = 2,
                        profileId = "work_preset",
                        daysOfWeekMask = 31, // Mon-Fri
                        startTimeSeconds = 9 * 3600, // 09:00
                        endTimeSeconds = 17 * 3600   // 17:00
                    )
                )
            ),
            Profile(
                id = "sleep_preset",
                name = "Sleep Sanctuary",
                iconName = "bedtime",
                colorHex = "#8B5CF6", // Purple
                isEnabled = false,
                isPreset = true,
                targetApps = listOf(
                    TargetApp("com.google.android.youtube", "YouTube"),
                    TargetApp("com.instagram.android", "Instagram"),
                    TargetApp("com.netflix.mediaclient", "Netflix")
                ),
                schedules = listOf(
                    Schedule(
                        id = 3,
                        profileId = "sleep_preset",
                        daysOfWeekMask = 127, // All 7 days
                        startTimeSeconds = 22 * 3600 + 1800, // 22:30
                        endTimeSeconds = 6 * 3600 + 1800     // 06:30 (overnight!)
                    )
                )
            ),
            Profile(
                id = "detox_preset",
                name = "Digital Detox",
                iconName = "spa",
                colorHex = "#F59E0B", // Amber
                isEnabled = false,
                isPreset = true,
                targetApps = listOf(
                    TargetApp("com.instagram.android", "Instagram"),
                    TargetApp("com.zhiliaoapp.musically", "TikTok"),
                    TargetApp("com.twitter.android", "X / Twitter"),
                    TargetApp("com.facebook.katana", "Facebook")
                )
            )
        )
    }
}
