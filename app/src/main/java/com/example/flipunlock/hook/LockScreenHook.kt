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
        if (!Config.uiLockScreen) { log("LockScreenHook: DISABLED by persist.flipunlock.ui.lockscreen"); return }
        log("LockScreenHook: loading for ${param.packageName}")
        safeHook("LockScreenHook") {
            hookTinyScreen(param)
            hookFlipTinyScreen(param)
            hookInstantFlipTinyScreen(param)
            hookControlCenterRelayout(param)
            hookReplaceController(param)
        }
    }

    // B. isTinyScreen + isFlipTinyScreen → false
    //    isTinyScreen: max(px)/density <= 670dp → compact layout on outer screen
    //    isFlipTinyScreen: isFlipDevice && isTinyScreen → tiny panel path
    //    Both → false forces inner-screen (large) lock screen style on outer screen.
    private fun hookTinyScreen(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.utils.configs.MiuiConfigs")
            val method = cls.getDeclaredMethod("isTinyScreen",
                android.content.Context::class.java)
            method.isAccessible = true
            hook(method) { chain ->
                log("LockScreen: isTinyScreen called → returning false")
                false
            }
            log("LockScreen: ✓ isTinyScreen → false")
        }.onFailure { log("LockScreen: isTinyScreen failed", it) }
    }

    private fun hookFlipTinyScreen(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.utils.configs.MiuiConfigs")
            val method = cls.getDeclaredMethod("isFlipTinyScreen",
                android.content.Context::class.java)
            method.isAccessible = true
            hook(method) { chain ->
                log("LockScreen: isFlipTinyScreen called → returning false")
                false
            }
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
            hook(onConfigMethod, after { _, result ->
                runCatching {
                    val configClass = param.classLoader.loadClass(
                        "com.miui.utils.configs.MiuiConfigs")
                    val field = configClass.getDeclaredField("sInstantAppConfig")
                    field.isAccessible = true
                    val config = field.get(null)
                    val stField = android.content.res.Configuration::class.java
                        .getDeclaredField("screenType")
                    stField.isAccessible = true
                    stField.setInt(config, 0)
                }
                result
            })
            log("LockScreen: ✓ sInstantAppConfig.screenType → 0 after config change")
        }.onFailure { log("LockScreen: sInstantAppConfig fix failed", it) }
    }

    // C. Force control center to use large-screen tile layout.
    //    Column count comes from resources.getInteger(quick_settings_num_columns):
    //      default: 4 cols, sw600dp-port: 3 cols (larger tiles).
    //    Outer screen 371dp always matches default (compact 4-col layout).
    //    Hook updateResources() to override mResourceColumns after resource load.
    private fun hookControlCenterRelayout(param: PackageReadyParam) {
        // Try both possible package names (JADX p037qs vs runtime qs)
        val classNames = listOf(
            "com.android.systemui.p037qs.MiuiTileLayout",
            "com.android.systemui.qs.MiuiTileLayout")
        var hooked = false
        for (name in classNames) {
            runCatching {
                val cls = param.classLoader.loadClass(name)
                log("LockScreen/QS: found class $name")
                // Log all methods with "update" in name to find the right one
                cls.declaredMethods.filter { it.name.contains("update", true) }
                    .forEach { log("LockScreen/QS:   method: ${it.name}(${it.parameterTypes.joinToString()})") }
                val method = cls.getDeclaredMethod("updateResources")
                method.isAccessible = true
                hook(method) { chain ->
                    log("LockScreen/QS: updateResources() called on ${chain.thisObject.javaClass.name}")
                    val result = chain.proceed() as? Boolean ?: false
                    val res = (chain.thisObject as? android.view.View)?.context?.resources
                    val orient = res?.configuration?.orientation ?: 0
                    val largeCols = if (orient == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 5 else 3
                    val colsField = cls.getDeclaredField("mResourceColumns")
                    colsField.isAccessible = true
                    val currentCols = colsField.getInt(chain.thisObject)
                    log("LockScreen/QS: currentCols=$currentCols, target=$largeCols")
                    if (currentCols != largeCols) {
                        colsField.setInt(chain.thisObject, largeCols)
                        val updateMethod = cls.declaredMethods.firstOrNull {
                            it.name.startsWith("updateColumns") && it.parameterCount == 0
                        }
                        updateMethod?.apply { isAccessible = true; invoke(chain.thisObject) }
                        (chain.thisObject as? android.view.View)?.requestLayout()
                        log("LockScreen/QS: columns $currentCols → $largeCols")
                    }
                    result
                }
                log("LockScreen/QS: hooked $name.updateResources()")
                hooked = true
                break
            }.onFailure { log("LockScreen/QS: $name not found: ${it.message}") }
        }
        if (!hooked) log("LockScreen/QS: FAILED — MiuiTileLayout not found in any package")
    }

    // D. Replace TinyKeyguardPanelViewControllerImpl with Dummy
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
