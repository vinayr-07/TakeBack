package com.vr.takeback.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.vr.takeback.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ProfileRepository(private val context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("takeback_profiles_prefs", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow<List<Profile>>(loadProfiles())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    fun getProfileById(id: String): Profile? {
        return _profiles.value.find { it.id == id }
    }

    fun toggleProfile(id: String) {
        val updated = _profiles.value.map { profile ->
            if (profile.id == id) {
                profile.copy(isEnabled = !profile.isEnabled, updatedAtMillis = System.currentTimeMillis())
            } else profile
        }
        _profiles.value = updated
        persistProfiles(updated)
    }

    fun saveProfile(profile: Profile) {
        val current = _profiles.value.toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            current[index] = profile.copy(updatedAtMillis = System.currentTimeMillis())
        } else {
            current.add(profile)
        }
        _profiles.value = current
        persistProfiles(current)
    }

    fun deleteProfile(id: String) {
        val updated = _profiles.value.filter { it.id != id }
        _profiles.value = updated
        persistProfiles(updated)
    }

    fun duplicateProfile(id: String): Profile? {
        val original = getProfileById(id) ?: return null
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            name = "${original.name} (Copy)",
            isPreset = false,
            createdAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis()
        )
        saveProfile(copy)
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
                            put("dailyLimitMinutes", lim.dailyLimitMinutes)
                            put("isEnabled", lim.isEnabled)
                        })
                    }
                    put("usageLimits", limitsArray)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString("saved_profiles_json", jsonArray.toString()).apply()
        } catch (e: Exception) {
            // Log fallback
        }
    }

    private fun loadProfiles(): List<Profile> {
        val jsonStr = prefs?.getString("saved_profiles_json", null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<Profile>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.getString("id")
                    val name = obj.getString("name")
                    val iconName = obj.optString("iconName", "shield")
                    val colorHex = obj.optString("colorHex", "#10B981")
                    val isEnabled = obj.optBoolean("isEnabled", false)
                    val isPreset = obj.optBoolean("isPreset", false)

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
                                    ruleType = try { WebsiteRuleType.valueOf(wObj.optString("ruleType", "EXACT_DOMAIN")) } catch(e: Exception) { WebsiteRuleType.EXACT_DOMAIN },
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
                            limits.add(
                                UsageLimit(
                                    id = lObj.optLong("id", System.currentTimeMillis()),
                                    profileId = lObj.optString("profileId", id),
                                    dailyLimitMinutes = lObj.optInt("dailyLimitMinutes", 30),
                                    isEnabled = lObj.optBoolean("isEnabled", true)
                                )
                            )
                        }
                    }

                    list.add(
                        Profile(
                            id = id,
                            name = name,
                            iconName = iconName,
                            colorHex = colorHex,
                            isEnabled = isEnabled,
                            isPreset = isPreset,
                            targetApps = targetApps,
                            targetWebsites = targetWebsites,
                            schedules = schedules,
                            usageLimits = limits
                        )
                    )
                }
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                // Fallback to defaults
            }
        }
        val defaults = createDefaultProfiles()
        persistProfiles(defaults)
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
                        daysOfWeekMask = 31, // Mon-Fri (1+2+4+8+16 = 31)
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
                        daysOfWeekMask = 127, // All 7 days (1..7)
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
