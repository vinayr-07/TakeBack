package com.vr.takeback.domain.security

/**
 * Resolver for social media applications.
 * Combines Android OS package category inspection (ApplicationInfo.CATEGORY_SOCIAL)
 * with a comprehensive verified set of known social networking packages.
 * Pure JVM domain logic with zero hard framework dependencies.
 */
object SocialMediaResolver {

    /**
     * Verified bundle IDs for major social networking platforms.
     */
    val KNOWN_SOCIAL_PACKAGES: Set<String> = setOf(
        "com.instagram.android",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.facebook.orca", // Messenger
        "com.twitter.android",
        "com.twitter.android.lite",
        "com.instagram.barcelona", // Threads
        "com.zhiliaoapp.musically", // TikTok Global
        "com.ss.android.ugc.trill", // TikTok Regional
        "com.ss.android.ugc.tiktok",
        "com.snapchat.android",
        "com.reddit.frontpage",
        "com.pinterest",
        "com.linkedin.android",
        "xyz.blueskyweb.app",
        "com.tumblr"
    )

    /**
     * Determines whether [packageName] represents a social media application.
     * Checks the known verified set first, then delegates to [categoryProvider]
     * (which on Android maps to ApplicationInfo.category == 4 / CATEGORY_SOCIAL).
     */
    fun isSocialMediaPackage(
        packageName: String,
        categoryProvider: ((String) -> Int?)? = null
    ): Boolean {
        if (KNOWN_SOCIAL_PACKAGES.contains(packageName)) {
            return true
        }
        val category = categoryProvider?.invoke(packageName)
        // android.content.pm.ApplicationInfo.CATEGORY_SOCIAL = 4
        return category == 4
    }
}
