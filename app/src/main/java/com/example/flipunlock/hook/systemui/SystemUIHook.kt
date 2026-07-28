package com.example.flipunlock.hook.systemui
import com.example.flipunlock.hook.BaseHook

import android.content.ComponentName
import android.content.Context
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedInterface.PRIORITY_LOWEST
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * SystemUI-side hooks for the external display.
 *
 * Currently hooks:
 * - HideDisplayCutoutOrganizer.getDisplayCutoutInsetsOfNaturalOrientation → NONE
 * - DecorWindowManagerImpl.shouldHideDecorWindow → true
 * - MiuiCollapsedStatusBarFragment clock visibility (hide on outer screen)
 * - NotificationIconContainer icon expansion
 * - SystemUIToast.getGravity → 0x51 (toast centering fix)
 *
 * DeviceIdentityHook is excluded from SystemUI process (lock screen crash),
 * so this hook applies fixes unconditionally without device state checks.
 */
object SystemUIHook : BaseHook() {
    override val targetPackages = listOf("com.android.systemui")

    private const val STATUS_BAR_ICON_MAX = 8

    override fun setupHooks(param: PackageReadyParam) {
        log("SystemUIHook: loading for ${param.packageName}")
        hookHideDisplayCutoutOrganizer(param)
        hookDecorWindowManager(param)
        hookStatusBarClock(param)
        hookStatusBarIcons(param)
        hookToastGravity(param)
    }

    // ── HideDisplayCutoutOrganizer: block Shell-level cutout crop ──────
    //
    // This DisplayAreaOrganizer reads Display.getCutout().getSafeInsets()
    // and applies setWindowCrop() on the entire display area Surface at
    // the compositor (SurfaceFlinger) level. This is a SEPARATE layer
    // from system_server's WindowLayout — even if window frames are
    // correct in WMS, the Shell crops them at the Surface level after.
    //
    // On the Mix Flip outer screen, this crop reduces the display area
    // by safeInsetRight=124px → content (including toast) appears shifted.
    //
    // Hook getDisplayCutoutInsetsOfNaturalOrientation() → Insets.NONE
    // to prevent the Shell-level crop entirely.
    private fun hookHideDisplayCutoutOrganizer(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.wm.shell.hidedisplaycutout.HideDisplayCutoutOrganizer")
            val method = cls.getDeclaredMethod("getDisplayCutoutInsetsOfNaturalOrientation")
            method.isAccessible = true
            hook(method) { chain ->
                val result = chain.proceed()
                if (result != android.graphics.Insets.NONE) {
                    log("HideDisplayCutout: original insets=$result → NONE")
                }
                android.graphics.Insets.NONE
            }
            log("SystemUI: ✓ HideDisplayCutoutOrganizer → Insets.NONE")
        }.onFailure { log("SystemUI: HideDisplayCutoutOrganizer failed", it) }
    }

    // ── DecorWindowManagerImpl.shouldHideDecorWindow ────────────────────
    private fun hookDecorWindowManager(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.notification.decor.DecorWindowManagerImpl"
            )
            val method = cls.method(
                "shouldHideDecorWindow", ComponentName::class.java
            )
            hook(method, replaceResult(true))
            log("SystemUI: forced DecorWindowManagerImpl.shouldHideDecorWindow -> true")
        }.onFailure { log("SystemUI: failed hook DecorWindowManagerImpl", it) }
    }

    // ── Status bar clock hiding ──────────────────────────────────────────
    private fun hookStatusBarClock(param: PackageReadyParam) {
        runCatching {
            val fragmentClass = param.classLoader.loadClass(
                "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment"
            )

            hook(fragmentClass.method("clockHiddenMode")) { 8 }

            hook(fragmentClass.method(
                "updateStatusBarVisibilities", Boolean::class.java
            )) { chain ->
                val result = chain.proceed()
                chain.thisObject?.callMethod("hideClock", false)
                result
            }

            hook(fragmentClass.method("showClock", Boolean::class.java)) { chain ->
                if (chain.args[0] == true) {
                    chain.thisObject?.callMethod("hideClock", false)
                } else {
                    chain.proceed()
                }
            }

            log("SystemUI: hooked MiuiCollapsedStatusBarFragment clock")
        }.onFailure { log("SystemUI: failed hook status bar clock", it) }
    }

    // ── Status bar icon expansion ────────────────────────────────────────
    private fun hookStatusBarIcons(param: PackageReadyParam) {
        runCatching {
            val containerClass = param.classLoader.loadClass(
                "com.android.systemui.statusbar.phone.NotificationIconContainer"
            )

            val iconHooker = Hooker { chain ->
                val savedMaxIcons = chain.thisObject?.getField("mMaxIcons") as? Int
                chain.thisObject?.setField("mMaxIcons", STATUS_BAR_ICON_MAX)
                runWithCleanup({
                    savedMaxIcons?.let { chain.thisObject?.setField("mMaxIcons", it) }
                }) {
                    chain.proceed()
                }
            }

            hook(
                containerClass.method("calculateIconXTranslations"),
                PRIORITY_LOWEST,
                iconHooker
            )
            hook(
                containerClass.method("onMeasure", Int::class.java, Int::class.java),
                PRIORITY_LOWEST,
                iconHooker
            )

            log("SystemUI: hooked status bar icon expansion")
        }.onFailure { log("SystemUI: failed hook status bar icons", it) }
    }

    /**
     * SystemUIToast.getGravity() → return CENTER_HORIZONTAL | BOTTOM (0x51).
     *
     * MIUI renders toasts through SystemUI (ToastUI → SystemUIToast).
     * SystemUIToast has a BUG: it reads config_whenToStartHubModeDefault
     * (value=0 = NO_GRAVITY) instead of config_toastDefaultGravity
     * (value=0x51 = CENTER_HORIZONTAL | BOTTOM).
     */
    private fun hookToastGravity(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.systemui.toast.SystemUIToast")
            val method = cls.getDeclaredMethod("getGravity")
            method.isAccessible = true
            hook(method, replaceResult(0x51))
            log("SystemUI: ✓ SystemUIToast.getGravity → 0x51")
        }.onFailure { log("SystemUI: SystemUIToast.getGravity failed", it) }

        // ClickableToast — same bug
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.systemui.statusbar.views.ClickableToast")
            val ctor = cls.declaredConstructors.firstOrNull { it.parameterCount >= 3 }
            if (ctor != null) {
                ctor.isAccessible = true
                hook(ctor, after { chain, _ ->
                    runCatching { chain.thisObject?.setField("mGravity", 0x51) }
                    null
                })
                log("SystemUI: ✓ ClickableToast gravity → 0x51")
            }
        }.onFailure { log("SystemUI: ClickableToast failed", it) }
    }
}
