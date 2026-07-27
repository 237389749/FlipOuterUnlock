package com.example.flipunlock.hook.util

/**
 * Centralized exclusion registry for packages that need original device behavior.
 *
 * Some system packages rely on real flip identity or cutout data for correct
 * layout. All hooks MUST check these sets before applying spoofing.
 *
 * WHY EACH EXCLUSION:
 *   Sogou IME  — keyboard height depends on isTinyScreen (DeviceIdentityHook)
 *                and keyboard layout reads Display.getCutout() (GlobalCutoutHook)
 *   SystemUI    — lock screen panel (TinyKeyguardPanelViewController) needs
 *                isFlipDevice=true for correct tiny-screen layout
 */
object Exclusions {

    const val SYSTEMUI = "com.android.systemui"
    const val SOGOU_IME = "com.sohu.inputmethod.sogou.xiaomi"

    /** Packages that must see real flip identity (isFlipDevice, isTinyScreen, etc.) */
    val DEVICE_IDENTITY = setOf(SYSTEMUI, SOGOU_IME)

    /** Packages that must see real display cutout for correct layout */
    val GLOBAL_CUTOUT = setOf(SOGOU_IME)
}
