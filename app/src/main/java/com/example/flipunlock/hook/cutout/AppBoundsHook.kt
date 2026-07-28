package com.example.flipunlock.hook.cutout

import android.graphics.Rect
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

object AppBoundsHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayCutout) { log("AppBoundsHook: DISABLED by persist.flipunlock.display.cutout"); return }
        log("AppBoundsHook: setting up")
        safeHook("AppBounds") {
            hookDisplayCutoutSafe(param)
            hookFillInsetsState(param)
            hookLaunchActivityItem(param)
            hookScheduleConfigurationChanged(param)
            hookScheduleClientTransactionItem(param)
        }
        log("AppBoundsHook: done")
    }

    /**
     * InsetsState.getDisplayCutoutSafe(Rect) → restore full bounds.
     *
     * This is THE hook that controls toast/hint centering. WindowLayout.computeFrames()
     * calls getDisplayCutoutSafe() to get the cutout-safe display rect, then clips
     * the parent frame to that rect via intersectOrClamp(). The clipped frame feeds
     * into Gravity.apply(CENTER_HORIZONTAL) for toast positioning:
     *
     *   parentWidth = displayWidth - safeInsetRight = 1208 - 124 = 1084
     *   toast X = (1084 - toastWidth) / 2               ← 62px LEFT of center
     *
     * Hooking Display.getCutout() → NO_CUTOUT doesn't fix this because InsetsState
     * reads from an internal mDisplayCutout supplier that was set at boot time
     * (DisplayFrames construction, before LSPosed hooks load). That cached cutout
     * has safeInsetRight=124 baked in.
     *
     * By restoring outBounds to full screen after getDisplayCutoutSafe() runs,
     * computeFrames() sees an unclipped parent frame → toast centers correctly.
     */
    private fun hookDisplayCutoutSafe(param: SystemServerStartingParam) {
        runCatching {
            val insetsStateClass = param.classLoader.loadClass("android.view.InsetsState")
            val method = insetsStateClass.getDeclaredMethod("getDisplayCutoutSafe",
                android.graphics.Rect::class.java)
            method.isAccessible = true
            hook(method) { chain ->
                chain.proceed()  // original method narrows outBounds by safeInsetRight=124
                val outBounds = chain.args[0] as? android.graphics.Rect
                // Restore full-screen bounds — default values from InsetsState source.
                outBounds?.set(-100000, -100000, 100000, 100000)
            }
            log("AppBounds: ✓ getDisplayCutoutSafe → full bounds restored")
        }.onFailure { log("AppBounds: getDisplayCutoutSafe failed", it) }
    }

    /**
     * Remove display cutout from InsetsState after fillInsetsState runs.
     * This feeds both sync paths (addWindowInner / relayoutWindow) and async
     * paths (reportResized / notifyInsetsControlChanged).
     */
    private fun hookFillInsetsState(param: SystemServerStartingParam) {
        val displayCutoutClass = param.classLoader.loadClass("android.view.DisplayCutout")
        val noCutout = displayCutoutClass.field("NO_CUTOUT").get(null)
        val insetsTypeClass = param.classLoader.loadClass("android.view.WindowInsets\$Type")
        val displayCutoutType = insetsTypeClass.method("displayCutout").invoke(null) as? Int ?: 0

        val windowStateClass = param.classLoader.loadClass("com.android.server.wm.WindowState")
        val insetsStateClass = param.classLoader.loadClass("android.view.InsetsState")
        hook(
            windowStateClass.method("fillInsetsState", insetsStateClass, Boolean::class.javaPrimitiveType!!),
            after { chain, _ ->
                runCatching {
                    val state = chain.args[0]
                    noCutout?.let { state.callMethod("setDisplayCutout", it) }
                    for (i in (state.callMethod("sourceSize") as? Int ?: 0) - 1 downTo 0) {
                        val source = state.callMethod("sourceAt", i) ?: continue
                        if (source.callMethod("getType") as? Int == displayCutoutType) {
                            state.callMethod("removeSourceAt", i)
                        }
                    }
                }
                null
            }
        )
    }

    /**
     * Fix appBounds in LaunchActivityItem for cold starts.
     * Both mOverrideConfig and mCurConfig need appBounds fixed.
     */
    private fun hookLaunchActivityItem(param: SystemServerStartingParam) {
        val launchActivityItemClass =
            param.classLoader.loadClass("android.app.servertransaction.LaunchActivityItem")
        hook(
            launchActivityItemClass.constructors.first { it.parameterCount > 10 },
            after { chain, result ->
                runCatching {
                    chain.thisObject?.getField("mOverrideConfig")
                        ?.let { fixConfigurationAppBounds(it) }
                    chain.thisObject?.getField("mCurConfig")
                        ?.let { fixConfigurationAppBounds(it) }
                }
                result // must return constructed instance, not null
            }
        )
    }

    /**
     * Fix appBounds for per-activity config updates.
     * args[0] is mMergedOverrideConfiguration — a persistent system field,
     * so restore original appBounds after proceed() to avoid pollution.
     */
    private fun hookScheduleConfigurationChanged(param: SystemServerStartingParam) {
        val activityRecord = param.classLoader.loadClass("com.android.server.wm.ActivityRecord")
        val activityWindowInfoClass =
            param.classLoader.loadClass("android.window.ActivityWindowInfo")
        hook(
            activityRecord.method(
                "scheduleConfigurationChanged",
                android.content.res.Configuration::class.java,
                activityWindowInfoClass
            )
        ) { chain ->
            val windowConfig =
                runCatching { chain.args[0].getField("windowConfiguration") }.getOrNull()
            val originalAppBounds =
                (windowConfig?.callMethod("getAppBounds") as? Rect)?.let { Rect(it) }
            val bounds = windowConfig?.callMethod("getBounds") as? Rect
            if (bounds != null && !bounds.isEmpty) {
                windowConfig.callMethod("setAppBounds", bounds)
            }
            runWithCleanup({ windowConfig?.callMethod("setAppBounds", originalAppBounds) }) {
                chain.proceed()
            }
        }
    }

    /**
     * Fix appBounds in ConfigurationChangeItem (process-global config).
     * mConfiguration is a copy, so no restore needed.
     */
    private fun hookScheduleClientTransactionItem(param: SystemServerStartingParam) {
        val windowProcessController =
            param.classLoader.loadClass("com.android.server.wm.WindowProcessController")
        val iApplicationThread =
            param.classLoader.loadClass("android.app.IApplicationThread")
        val clientTransactionItem =
            param.classLoader.loadClass("android.app.servertransaction.ClientTransactionItem")
        val configurationChangeItemClass =
            param.classLoader.loadClass("android.app.servertransaction.ConfigurationChangeItem")
        hook(
            windowProcessController.method(
                "scheduleClientTransactionItem",
                iApplicationThread,
                clientTransactionItem
            )
        ) { chain ->
            val item = chain.args[1]
            if (item != null && configurationChangeItemClass.isInstance(item)) {
                item.getField("mConfiguration")?.let { fixConfigurationAppBounds(it) }
            }
            chain.proceed()
        }
    }

    /**
     * Set appBounds = bounds on the given Configuration's windowConfiguration.
     * This forces FULL_SCREEN app bounds for the activity.
     */
    private fun fixConfigurationAppBounds(configuration: Any?) {
        val config = configuration ?: return
        runCatching {
            val windowConfiguration =
                config.getField("windowConfiguration") ?: return@runCatching
            val bounds = windowConfiguration.callMethod("getBounds") as? Rect
            if (bounds != null && !bounds.isEmpty) {
                windowConfiguration.callMethod("setAppBounds", bounds)
            }
        }
    }

}
