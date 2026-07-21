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
 *
 * ═══ Dependency / Coupling Notes ═══
 *
 * display.dual ── FOUNDATION ──┐
 *   If disabled, state=6 is not forced. The system may fall back to native
 *   flip behavior (state=0 CLOSED or state=2 OPEN). All hooks that depend
 *   on outer=displayId=0 topology may still work but layout may differ.
 *   ↓ affects: display.aod, gesture.home, ui.lockscreen (display routing)
 *   ✗ no effect: display.cutout, gesture.back, ui.widget, ime
 *
 * gesture.back ←──── gesture.home ────→ gesture.back
 *   Coupled! Both target the same launcher identity problem:
 *   - gesture.back disables FlipLauncher (com.miui.fliphome)
 *   - gesture.home forces getIsUseMiuiHomeAsDefaultHome→true
 *   If gesture.back=OFF (FlipLauncher active) + gesture.home=ON:
 *     FlipLauncher is default home → conflict with miuihome NavStubView
 *   If gesture.back=ON (FlipLauncher disabled) + gesture.home=OFF:
 *     No default home app → HOME intent may show "choose launcher" dialog
 *   Recommended: keep both ON or both OFF together.
 *
 * display.aod ── depends on ── display.dual
 *   AOD targets displayId from DreamService (outer screen in state=6).
 *   display.dual must be ON for correct display routing.
 *
 * ui.lockscreen ── depends on ── display.dual
 *   LockScreenHook hooks SystemUI which runs on displayId=0 (outer in state=6).
 *   Independent of gesture toggles.
 *
 * display.cutout ── INDEPENDENT ──
 *   Cutout/letterbox hooks operate at WindowManager/framework level.
 *   No dependency on any other toggle.
 *
 * ui.widget ── INDEPENDENT ──
 *   WatchOverlayHook operates in fliphome process only.
 *   No dependency on any other toggle.
 *
 * ime ── INDEPENDENT ──
 *   Input method hooks in system_server + Sogou process.
 *   No dependency on any other toggle.
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

    // Gesture — keep together
    val gestureHome: Boolean get() = enabled && raw("persist.flipunlock.gesture.home", true)
    val gestureBack: Boolean get() = enabled && raw("persist.flipunlock.gesture.back", true)

    // UI
    val uiLockScreen: Boolean get() = enabled && raw("persist.flipunlock.ui.lockscreen", true)
    val uiWidget: Boolean get() = enabled && raw("persist.flipunlock.ui.widget", true)

    // Other
    val ime: Boolean get() = enabled && raw("persist.flipunlock.ime", true)

    /** Print all toggle keys and values, plus coupling warnings. */
    fun logConfig() {
        val sb = StringBuilder("═══ FlipOuterUnlock Config ═══\n")
        for (key in keys) {
            sb.append("  $key = ${readProp(key)}\n")
        }
        // Coupling warnings
        if (gestureHome != gestureBack) {
            sb.append("  ⚠ gesture.home($gestureHome) ≠ gesture.back($gestureBack) — recommended keep together\n")
        }
        if (displayAod && !displayDual) {
            sb.append("  ⚠ display.aod=ON but display.dual=OFF — AOD may route to wrong display\n")
        }
        sb.append("  (getprop | grep persist.flipunlock)")
        log(sb.toString())
    }

    private fun raw(key: String, default: Boolean): Boolean {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType!!)
                .invoke(null, key, default) as? Boolean ?: default
        } catch (_: Exception) {
            default
        }
    }

    private fun readProp(key: String): String {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java, String::class.java)
                .invoke(null, key, "") as? String ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
