package com.example.flipunlock.hook.cutout

import android.graphics.Rect
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

object AppBoundsHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayCutout) { log("AppBoundsHook: DISABLED by persist.flipunlock.display.cutout"); return }
        log("AppBoundsHook: setting up")
        safeHook("AppBounds") {
            hookComputeFrames(param)
            hookFillInsetsState(param)
            hookLaunchActivityItem(param)
            hookScheduleConfigurationChanged(param)
            hookScheduleClientTransactionItem(param)
        }
        log("AppBoundsHook: done")
    }

    /**
     * WindowLayout.computeFrames() → force displayCutoutSafe to full bounds.
     *
     * computeFrames() clips outParentFrame to displayCutoutSafeExceptMaybeBars
     * (a copy of displayCutoutSafe). For toast on the outer screen, this clip
     * narrows the parent frame from 1208px to 1084px (safeInsetRight=124).
     * Gravity.CENTER_HORIZONTAL then centers at (1084-w)/2 instead of (1208-w)/2
     * → 62px left-shift.
     *
     * Options A (InsetsState.getDisplayCutoutSafe) and B (DisplayFrames.update)
     * both failed — the displayCutoutSafe passed to computeFrames apparently
     * doesn't come from the global InsetsState/DisplayFrames, or it's cached
     * before our hooks can modify it.
     *
     * This is the FINAL choke point — modify displayCutoutSafe BEFORE the
     * clipping happens, directly in computeFrames. No upstream state matters.
     */
    private fun hookComputeFrames(param: SystemServerStartingParam) {
        runCatching {
            val wlClass = param.classLoader.loadClass("android.view.WindowLayout")
            // computeFrames(LayoutParams, InsetsState, Rect displayCutoutSafe,
            //   Rect windowBounds, int windowingMode, int requestedWidth,
            //   int requestedHeight, int requestedVisibleTypes,
            //   float compatScale, ClientWindowFrames)
            val method = wlClass.getDeclaredMethod("computeFrames",
                android.view.WindowManager.LayoutParams::class.java,
                param.classLoader.loadClass("android.view.InsetsState"),
                android.graphics.Rect::class.java,      // displayCutoutSafe
                android.graphics.Rect::class.java,      // windowBounds
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Float::class.javaPrimitiveType!!,
                param.classLoader.loadClass("android.view.ClientWindowFrames"))
            method.isAccessible = true
            hook(method, before { chain ->
                // args[2] = displayCutoutSafe — the Rect that clips parentFrame
                val safe = chain.args[2] as? android.graphics.Rect
                // Force full-screen bounds → no clipping → toast centers at 1208px
                safe?.set(-100000, -100000, 100000, 100000)
            })
            log("AppBounds: ✓ computeFrames displayCutoutSafe → full bounds")
        }.onFailure { log("AppBounds: computeFrames failed", it) }
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
