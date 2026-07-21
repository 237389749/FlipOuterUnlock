package com.example.flipunlock.hook.util

/**
 * Feature toggles via SystemProperties. All default to true (enabled).
 *
 * Set before reboot:
 *   setprop persist.flipunlock.enable false          # master kill switch
 *   setprop persist.flipunlock.display.dual false    # dual display
 *   setprop persist.flipunlock.display.aod false     # outer screen AOD
 *   setprop persist.flipunlock.display.cutout false  # remove cutout
 *   setprop persist.flipunlock.gesture.home false    # bottom gestures (home/recents)
 *   setprop persist.flipunlock.gesture.back false    # back gestures (disable FlipLauncher)
 *   setprop persist.flipunlock.ui.lockscreen false   # lock screen large layout
 *   setprop persist.flipunlock.ui.widget false       # disable widget overlay
 *   setprop persist.flipunlock.ime false             # input method freedom
 *
 * Read at runtime:
 *   getprop persist.flipunlock.enable
 */
object Config {
    // Master switch
    val enabled: Boolean get() = prop("persist.flipunlock.enable", true)

    // Display
    val displayDual: Boolean get() = enabled && prop("persist.flipunlock.display.dual", true)
    val displayAod: Boolean get() = enabled && prop("persist.flipunlock.display.aod", true)
    val displayCutout: Boolean get() = enabled && prop("persist.flipunlock.display.cutout", true)

    // Gesture
    val gestureHome: Boolean get() = enabled && prop("persist.flipunlock.gesture.home", true)
    val gestureBack: Boolean get() = enabled && prop("persist.flipunlock.gesture.back", true)

    // UI
    val uiLockScreen: Boolean get() = enabled && prop("persist.flipunlock.ui.lockscreen", true)
    val uiWidget: Boolean get() = enabled && prop("persist.flipunlock.ui.widget", true)

    // Other
    val ime: Boolean get() = enabled && prop("persist.flipunlock.ime", true)

    private fun prop(key: String, default: Boolean): Boolean {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType!!)
                .invoke(null, key, default) as? Boolean ?: default
        } catch (_: Exception) {
            default
        }
    }
}
