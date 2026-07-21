package com.example.flipunlock.hook.util

/**
 * Feature toggles via SystemProperties. All default to true (enabled).
 *
 * List all keys and current values:
 *   getprop | grep persist.flipunlock
 *
 * Set before reboot:
 *   setprop persist.flipunlock.enable false              # master kill switch
 *   setprop persist.flipunlock.display.dual false        # dual display
 *   setprop persist.flipunlock.display.aod false         # outer screen AOD
 *   setprop persist.flipunlock.display.cutout false      # remove cutout
 *   setprop persist.flipunlock.gesture.home false        # bottom gestures
 *   setprop persist.flipunlock.gesture.back false        # back gestures
 *   setprop persist.flipunlock.ui.lockscreen false       # lock screen layout
 *   setprop persist.flipunlock.ui.widget false           # disable widget overlay
 *   setprop persist.flipunlock.ime false                 # input method freedom
 */
object Config {
    private val keys = listOf(
        "persist.flipunlock.enable",
        "persist.flipunlock.display.dual",
        "persist.flipunlock.display.aod",
        "persist.flipunlock.display.cutout",
        "persist.flipunlock.gesture.home",
        "persist.flipunlock.gesture.back",
        "persist.flipunlock.ui.lockscreen",
        "persist.flipunlock.ui.widget",
        "persist.flipunlock.ime",
    )

    // Master switch
    val enabled: Boolean get() = raw("persist.flipunlock.enable", true)

    // Display
    val displayDual: Boolean get() = enabled && raw("persist.flipunlock.display.dual", true)
    val displayAod: Boolean get() = enabled && raw("persist.flipunlock.display.aod", true)
    val displayCutout: Boolean get() = enabled && raw("persist.flipunlock.display.cutout", true)

    // Gesture
    val gestureHome: Boolean get() = enabled && raw("persist.flipunlock.gesture.home", true)
    val gestureBack: Boolean get() = enabled && raw("persist.flipunlock.gesture.back", true)

    // UI
    val uiLockScreen: Boolean get() = enabled && raw("persist.flipunlock.ui.lockscreen", true)
    val uiWidget: Boolean get() = enabled && raw("persist.flipunlock.ui.widget", true)

    // Other
    val ime: Boolean get() = enabled && raw("persist.flipunlock.ime", true)

    /** Print all toggle keys and values to logcat on module startup. */
    fun logConfig() {
        val sb = StringBuilder("═══ FlipOuterUnlock Config ═══\n")
        for (key in keys) {
            sb.append("  $key = ${readProp(key)}\n")
        }
        sb.append("  (getprop | grep persist.flipunlock)")
        log(sb.toString())
    }

    private fun raw(key: String, default: Boolean): Boolean {
        val v = readProp(key) ?: return default
        return v == "true" || v == "1"
    }

    private fun readProp(key: String): String? {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java)
                .invoke(null, key) as? String
        } catch (_: Exception) {
            null
        }
    }
}
