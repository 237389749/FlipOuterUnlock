package com.example.flipunlock

/**
 * Preference keys for the module. Uses LSPosed remote SharedPreferences
 * so the Xposed service can read them in any process.
 */
object Prefs {
    const val NAME = "flipouterunlock_prefs"

    // Global fullscreen master switch
    const val GLOBAL_FULLSCREEN = "global_fullscreen"

    // Per-app key prefix: fullscreen_<packageName>
    const val FULLSCREEN_PREFIX = "fullscreen_"

    fun fullscreenKey(pkg: String) = "${FULLSCREEN_PREFIX}$pkg"
}
