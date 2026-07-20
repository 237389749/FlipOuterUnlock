package com.example.flipunlock.hook

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
 * - DecorWindowManagerImpl.shouldHideDecorWindow() to hide widget overlay
 * - MiuiNotificationMenuRow.createMenuViews() with isTinyScreen -> false scope
 * - MiuiCollapsedStatusBarFragment clock visibility (hide on flip outer screen)
 * - NotificationIconContainer / MiuiStatusIconContainer icon expansion
 *
 * DeviceIdentityHook is excluded from SystemUI process (lock screen crash),
 * so this hook applies fixes unconditionally without device state checks.
 */
object SystemUIHook : BaseHook() {
    override val targetPackages = listOf("com.android.systemui")

    // Default max icons for status bar. 8 ensures the always-on display
    // shows enough notification icons.
    private const val STATUS_BAR_ICON_MAX = 8

    override fun setupHooks(param: PackageReadyParam) {
        log("SystemUIHook: loading for ${param.packageName}")
        hookDecorWindowManager(param)
        hookNotificationMenu(param)
        hookStatusBarClock(param)
        hookStatusBarIcons(param)
        hookNavigationBar(param)
    }

    // ── DecorWindowManagerImpl.shouldHideDecorWindow ────────────────────
    // Returns true = hide widget, false = show widget.
    // We force true to always hide from SystemUI side.
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

    // ── Notification menu fix ───────────────────────────────────────────
    // MiuiNotificationMenuRow.createMenuViews runs within a scope where
    // MiuiConfigs.isTinyScreen(Context) returns false.
    // Belt-and-suspenders with DeviceIdentityHook's global override.
    private fun hookNotificationMenu(param: PackageReadyParam) {
        runCatching {
            val miuiConfigs = param.classLoader.loadClass(
                "com.miui.utils.configs.MiuiConfigs"
            )
            val fakeTinyScreen = hookScope(
                miuiConfigs.method("isTinyScreen", Context::class.java)
            ) { false }

            val rowClass = param.classLoader.loadClass(
                "com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow"
            )
            hook(rowClass.method("createMenuViews", Boolean::class.java)) { chain ->
                fakeTinyScreen.run { chain.proceed() }
            }
            log("SystemUI: hooked MiuiNotificationMenuRow.createMenuViews")
        }.onFailure { log("SystemUI: failed hook notification menu", it) }
    }

    // ── Status bar clock hiding ──────────────────────────────────────────
    // Always hide the status bar clock on the external display since
    // the always-on/outer screen has its own clock layout.
    private fun hookStatusBarClock(param: PackageReadyParam) {
        runCatching {
            val fragmentClass = param.classLoader.loadClass(
                "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment"
            )

            // clockHiddenMode -> return 8 (GONE) to hide clock
            hook(fragmentClass.method("clockHiddenMode")) { 8 }

            // updateStatusBarVisibilities -> after proceed, force hideClock
            hook(fragmentClass.method(
                "updateStatusBarVisibilities", Boolean::class.java
            )) { chain ->
                val result = chain.proceed()
                chain.thisObject?.callMethod("hideClock", false)
                result
            }

            // showClock -> if arg is true, hide clock instead of showing it
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
    // Expand max notification icons shown on the external display and
    // fake isFlipTinyScreen -> false during measure/layout.
    private fun hookStatusBarIcons(param: PackageReadyParam) {
        runCatching {
            val miuiConfigs = param.classLoader.loadClass(
                "com.miui.utils.configs.MiuiConfigs"
            )
            val fakeFlipTinyScreen = hookScope(
                miuiConfigs.method("isFlipTinyScreen", Context::class.java)
            ) { false }

            // ── NotificationIconContainer ────────────────────────────────
            val containerClass = param.classLoader.loadClass(
                "com.android.systemui.statusbar.phone.NotificationIconContainer"
            )

            val iconHooker = Hooker { chain ->
                val savedMaxIcons = chain.thisObject?.getField("mMaxIcons") as? Int
                chain.thisObject?.setField("mMaxIcons", STATUS_BAR_ICON_MAX)
                runWithCleanup({
                    savedMaxIcons?.let { chain.thisObject?.setField("mMaxIcons", it) }
                }) {
                    fakeFlipTinyScreen.run { chain.proceed() }
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

            // ── MiuiStatusIconContainer ──────────────────────────────────
            val statusIconClass = param.classLoader.loadClass(
                "com.android.systemui.statusbar.views.MiuiStatusIconContainer"
            )
            hook(
                statusIconClass.method("onMeasure", Int::class.java, Int::class.java)
            ) { chain ->
                fakeFlipTinyScreen.run { chain.proceed() }
            }

            log("SystemUI: hooked status bar icon expansion")
        }.onFailure { log("SystemUI: failed hook status bar icons", it) }
    }

    // ── NavigationBar fix: force creation on flip outer screen ─────────
    //
    // NavigationBarControllerImpl creates the gesture navigation bar.
    // Two guards prevent it on flip outer screens:
    //
    // 1. createNavigationBar() checks:
    //      isFlipTinyScreen(context) → return (fixed by LockScreenHook)
    //      mIsFsgMode && mHideGestureLine → return (hooked below)
    //
    // 2. onScreenLayoutSizeChanged() checks:
    //      configuration.screenType == 1 → removeNavigationBar(0)
    //      This uses the RAW screenType FIELD, bypassing ScreenTypeHook!
    //      Fix: temporarily force screenType=0 so the check fails,
    //      allowing the nav bar to be created/kept.
    //
    // Without NavigationBar, bottom gestures (Home/Recents) are absent
    // in all apps. Desktop works via miuihome's NavStubView (LauncherHook).
    private fun hookNavigationBar(param: PackageReadyParam) {
        val implClass = param.classLoader.loadClass(
            "com.android.systemui.navigationbar.NavigationBarControllerImpl")

        // Hook onScreenLayoutSizeChanged(Configuration)
        // Original: if (configuration.screenType == 1) removeNavigationBar(0)
        // Fix: temporarily set screenType=0 so isFlipTinyScreen check fails
        runCatching {
            val method = implClass.getDeclaredMethod("onScreenLayoutSizeChanged",
                android.content.res.Configuration::class.java)
            method.isAccessible = true
            val stField = android.content.res.Configuration::class.java
                .getDeclaredField("screenType")
            stField.isAccessible = true
            hook(method) { chain ->
                val config = chain.args[0] as? android.content.res.Configuration
                val orig = config?.screenType ?: -1
                if (orig == 1) {
                    stField.setInt(config, 0)  // fool screenType == 1 check
                }
                try {
                    chain.proceed()
                } finally {
                    if (orig == 1 && config != null) {
                        stField.setInt(config, orig)  // restore
                    }
                }
            }
            log("NavBar: hooked onScreenLayoutSizeChanged")
        }.onFailure { log("NavBar: onScreenLayoutSizeChanged failed", it) }

        // Hook createNavigationBar(Display, Bundle, RegisterStatusBarResult)
        // Bypass the mIsFsgMode && mHideGestureLine guard (line 254).
        // Force both fields to false before the original method runs.
        runCatching {
            val method = implClass.getDeclaredMethod("createNavigationBar",
                android.view.Display::class.java,
                android.os.Bundle::class.java,
                Class.forName("com.android.internal.statusbar.RegisterStatusBarResult"))
            method.isAccessible = true
            hook(method, before { chain ->
                runCatching {
                    val obj = chain.thisObject
                    val injectorField = implClass.getDeclaredField("mNavigationModeControllerInjector")
                    injectorField.isAccessible = true
                    val injector = injectorField.get(obj)
                    if (injector != null) {
                        injector.javaClass.getDeclaredField("mIsFsgMode")
                            .apply { isAccessible = true; setBoolean(injector, false) }
                        injector.javaClass.getDeclaredField("mHideGestureLine")
                            .apply { isAccessible = true; setBoolean(injector, false) }
                    }
                }
            })
            log("NavBar: hooked createNavigationBar")
        }.onFailure { log("NavBar: createNavigationBar failed", it) }
    }

}
