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
    // This DisplayAreaOrganizer applies a SurfaceFlinger-level setWindowCrop()
    // on the entire display area, cropping content by cutout safe insets.
    // On Mix Flip outer screen: safeInsetRight=398px → display cropped to
    // 810px → toast centers at 405px instead of 604px.
    //
    // Hook updateBoundsAndOffsets(): force mDefaultCutoutInsets=NONE
    // and clear mDefaultDisplayBounds to defeat the early-return check
    // (line 151: isEmpty || dimsChanged). This forces re-read of cutout
    // on every call — and GlobalCutoutHook makes Display.getCutout()→NO_CUTOUT.
    private fun hookHideDisplayCutoutOrganizer(param: PackageReadyParam) {
        // The 398px crop is applied to the display Surface BEFORE our hooks
        // load. We can't undo it by hooking HideDisplayCutoutOrganizer methods.
        //
        // Instead, intercept SurfaceControl.Transaction.setWindowCrop() to
        // detect and fix the cutout-cropped display area in real time.
        // When a crop of ~810px width is applied (1208 - 398 cutout), expand
        // it to full 1208px. This works regardless of timing.

        runCatching {
            val txnClass = param.classLoader.loadClass("android.view.SurfaceControl\$Transaction")
            val leashClass = param.classLoader.loadClass("android.view.SurfaceControl")
            val method = txnClass.getDeclaredMethod("setWindowCrop",
                leashClass, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method, before { chain ->
                val width = chain.args[1] as? Int ?: return@before
                val height = chain.args[2] as? Int ?: return@before
                // 810px = 1208 - 398 (cutout-cropped display width)
                // 1084px = 1208 - 124 (safeInset-cropped display width)
                // If cropped to cutout size, expand to full 1208px
                if (width > 0 && width < 1100 && height > 1300) {
                    chain.args[1] = 1208
                    log("SystemUI: setWindowCrop ${width}x$height → 1208x$height (fix cutout crop)")
                }
            })
            log("SystemUI: ✓ SurfaceControl.setWindowCrop — intercepting cutout crops")
        }.onFailure { log("SystemUI: setWindowCrop hook failed", it) }
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
