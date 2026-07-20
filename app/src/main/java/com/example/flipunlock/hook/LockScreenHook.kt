package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Fix lock screen on the outer screen with state=6 (DUAL, outer=displayId=0).
 *
 * Complete logic chain (from FlipRes systemui):
 *
 * View hierarchy in NotificationShadeWindowView (top→bottom Z-order):
 *   HyperOSKeyguardRootView (keyguard_root_view)          ← TOP layer
 *     └── keyguard_shortcut_container (bottom-aligned)
 *          └── shortcut views (MiuiShortcutController plugin)
 *   status_bar_expanded → NotificationPanelView → keyguard_bottom_area
 *   TinyKeyguardPanelView (tiny_keyguard_panel)            ← BOTTOM layer
 *     ├── ViewPager2 (clock templates, full-screen)
 *     ├── FlipRowContainer (notifications)
 *     └── tiny_keyguard_info_layer (owner info, face unlock, camera)
 *
 * Controller factory (isFlipDevice() gated — still true in SystemUI):
 *   FlipKeyguardModule → Impl (real) or Dummy (no-op)
 *
 * Three independent issues:
 * 1. Swipe-up blocked: fling() checks mBarState != 0
 * 2. Shortcuts blocked: shortcut plugin may not work in flip state
 * 3. Wallpaper stuck: flip uses rotation image (type 8) instead of lock
 *    wallpaper (type 2), set on wrong display in state=6
 *
 * Fixes:
 *   A. isFlipTinyScreen → false (switches panel logic to normal path)
 *   B. isInstantFlipTinyScreen → false (makes panel invisible, reveals shortcuts)
 *   C. Replace Impl with Dummy (panel controller is no-op)
 *   D. Wallpaper: force type 2 instead of type 8
 */
object LockScreenHook : BaseHook() {
    override val targetPackages = listOf("com.android.systemui")

    override fun setupHooks(param: PackageReadyParam) {
        log("LockScreenHook: loading for ${param.packageName}")
        safeHook("LockScreenHook") {
            hookTinyScreen(param)
            hookFlipTinyScreen(param)
            hookInstantFlipTinyScreen(param)
            hookReplaceController(param)
        }
    }

    // A. isTinyScreen + isFlipTinyScreen → false
    //    isTinyScreen: max(px)/density <= 670dp → compact layout on outer screen
    //    isFlipTinyScreen: isFlipDevice && isTinyScreen → tiny panel path
    //    Both → false forces inner-screen (large) lock screen style on outer screen.
    private fun hookTinyScreen(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.utils.configs.MiuiConfigs")
            val method = cls.getDeclaredMethod("isTinyScreen",
                android.content.Context::class.java)
            method.isAccessible = true
            hook(method, replaceResult(false))
            log("LockScreen: ✓ isTinyScreen → false (inner-screen style)")
        }.onFailure { log("LockScreen: isTinyScreen failed", it) }
    }

    private fun hookFlipTinyScreen(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.utils.configs.MiuiConfigs")
            val method = cls.getDeclaredMethod("isFlipTinyScreen",
                android.content.Context::class.java)
            method.isAccessible = true
            hook(method, replaceResult(false))
            log("LockScreen: ✓ isFlipTinyScreen → false")
        }.onFailure { log("LockScreen: isFlipTinyScreen failed", it) }
    }

    // B. isInstantFlipTinyScreen → false
    //    This controls shouldPanelBeVisible() and awesomeLockScreen visibility.
    //    SystemUIApplication.onConfigurationChanged() calls:
    //      sInstantAppConfig.updateFrom(configuration)
    //    which copies the screenType FIELD (not getter). ScreenTypeHook only
    //    intercepts getScreenType(), so sInstantAppConfig.screenType stays 1.
    //    Fix: hook SystemUIApplication.onConfigurationChanged to force screenType=0.
    private fun hookInstantFlipTinyScreen(param: PackageReadyParam) {
        runCatching {
            // Hook the static method itself as defense
            val cls = param.classLoader.loadClass("com.miui.utils.configs.MiuiConfigs")
            val method = cls.getDeclaredMethod("isInstantFlipTinyScreen")
            method.isAccessible = true
            hook(method, replaceResult(false))
            log("LockScreen: ✓ isInstantFlipTinyScreen → false")
        }.onFailure { log("LockScreen: isInstantFlipTinyScreen failed", it) }

        // Hook SystemUIApplication to force sInstantAppConfig.screenType = 0
        // after every configuration change. This is the ROOT FIX because
        // isInstantFlipTinyScreen reads the screenType FIELD directly.
        runCatching {
            val appClass = param.classLoader.loadClass(
                "com.android.systemui.SystemUIApplication")
            val onConfigMethod = appClass.getDeclaredMethod("onConfigurationChanged",
                android.content.res.Configuration::class.java)
            onConfigMethod.isAccessible = true
            hook(onConfigMethod, after { _, _ ->
                val configClass = param.classLoader.loadClass(
                    "com.miui.utils.configs.MiuiConfigs")
                val field = configClass.getDeclaredField("sInstantAppConfig")
                field.isAccessible = true
                val config = field.get(null) ?: return@after
                // Use reflection — screenType is a MIUI-injected field on Configuration
                val stField = android.content.res.Configuration::class.java
                    .getDeclaredField("screenType")
                stField.isAccessible = true
                stField.setInt(config, 0)
            })
            log("LockScreen: ✓ sInstantAppConfig.screenType → 0 after config change")
        }.onFailure { log("LockScreen: sInstantAppConfig fix failed", it) }
    }

    // C. Replace TinyKeyguardPanelViewControllerImpl with Dummy
    //    The Impl sets up TouchHandler, wallpaper animations, and all panel logic.
    //    The Dummy is a no-op — panel view exists but does nothing.
    private fun hookReplaceController(param: PackageReadyParam) {
        runCatching {
            val factoryClass = param.classLoader.loadClass(
                "com.android.systemui.shade.dagger.FlipKeyguardModule_ProvideTinyKeyguardPanelViewControllerFactory")
            val getMethod = factoryClass.getMethod("get")
            hook(getMethod, after { _, result ->
                val name = result?.javaClass?.name ?: ""
                if (name.contains("Impl")) {
                    log("LockScreen: replacing Impl with Dummy")
                    param.classLoader.loadClass(
                        "com.android.keyguard.tinyPanel.TinyKeyguardPanelViewControllerDummy")
                        .getDeclaredConstructor().newInstance()
                } else {
                    result
                }
            })
            log("LockScreen: ✓ controller → Dummy")
        }.onFailure { log("LockScreen: controller replacement failed", it) }
    }
}
