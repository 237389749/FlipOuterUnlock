package com.example.flipunlock.hook.gesture

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Block fliphome gesture engine so system NavigationBar gestures work on the outer screen.
 *
 * Two mutually exclusive gesture systems exist:
 *   - fliphome (BaseGestureImpl / GestureInputHelper): intercepts ALL touches via InputMonitor
 *     for fliphome's custom gesture navigation on the outer screen
 *   - SystemUI NavigationBar: standard Android gesture navigation (Back, Home, Recents)
 *
 * With state=6 (DUAL, outer leads, displayId=0), the inner screen launcher runs on the outer
 * screen. System gestures should be provided by SystemUI's NavigationBar, NOT fliphome.
 *
 * fliphome's InputMonitor (created via InputManager.monitorGestureInput()) intercepts all
 * touch events before any app window receives them. We block its creation so touches reach
 * SystemUI NavigationBar normally.
 *
 * Strategy:
 *   1. Block GestureInputHelper.initInputMonitor() → prevents InputMonitor creation
 *   2. Block GestureInputHelper.registerInputConsumer() → prevents InputConsumer registration
 *   3. Disable FlipLauncher component (keeps fliphome process alive but hides its UI)
 *   4. Block interstitial start pages (ported from MixFlipMod)
 *
 * The fliphome process stays alive (we don't kill TouchInteractionService) but its gesture
 * engine can't intercept touches without an InputMonitor.
 */
object GestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    private var launcherDisabled = false

    override fun setupHooks(param: PackageReadyParam) {
        hookNoStartPage(param)
        disableFlipLauncher(param)
        blockGestureInputMonitor(param)
    }

    // ── 1. No start page (ported from MixFlipMod FlipHomeHook) ───────────
    // Some apps (MiHome, Weibo, NetEaseMusic) show an interstitial start
    // page when launched on the outer screen. This hook disables that.
    private fun hookNoStartPage(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.fliphome.utils.PerformLaunchAction")
            val method = cls.method("onStartIntercept",
                UserHandle::class.java, Intent::class.java, Bundle::class.java, View::class.java)
            hook(method, replaceResult(false))
            log("GestureFix: disabled start page intercept")
        }.onFailure { log("GestureFix: PerformLaunchAction not found (harmless)", it) }
    }

    // ── 2. Disable FlipLauncher via PackageManager ────────────────────────
    // Disables the fliphome-specific launcher UI component.
    // With state=6, the inner launcher handles the desktop; FlipLauncher is not needed.
    private fun disableFlipLauncher(param: PackageReadyParam) {
        if (launcherDisabled) return
        runCatching {
            val flipAppClass = param.classLoader.loadClass(
                "com.miui.fliphome.FlipApplication"
            )
            val onCreateMethod = flipAppClass.method("onCreate")
            hook(onCreateMethod, after { chain, result ->
                if (launcherDisabled) return@after result
                runCatching {
                    val app = chain.thisObject
                    val ctx = app.callMethod("getApplicationContext") as? android.content.Context
                        ?: return@runCatching
                    val component = ComponentName("com.miui.fliphome", "com.miui.fliphome.FlipLauncher")
                    val pm = ctx.packageManager
                    val currentState = pm.getComponentEnabledSetting(component)
                    if (currentState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        pm.setComponentEnabledSetting(
                            component,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        launcherDisabled = true
                        log("GestureFix: disabled FlipLauncher component")
                    }
                }.onFailure { log("GestureFix: failed to disable FlipLauncher", it) }
                result
            })
            log("GestureFix: hooked FlipApplication.onCreate")
        }.onFailure { log("GestureFix: failed hook FlipApplication", it) }
    }

    // ── 3. Block fliphome gesture InputMonitor ───────────────────────────
    //
    // Fliphome's gesture engine creates an InputMonitor via:
    //   GestureInputHelper.initInputMonitor(name, displayId, context)
    //     → new InputMonitorCompat(name, displayId)
    //       → InputManager.monitorGestureInput(name, displayId)
    //         ← THIS intercepts ALL touch events before any app receives them
    //
    // By blocking initInputMonitor(), the InputMonitor is never created.
    // Touches flow through to SystemUI's NavigationBar normally.
    //
    // We also block registerInputConsumer() for defense-in-depth.
    private fun blockGestureInputMonitor(param: PackageReadyParam) {
        // 3a. Block InputMonitor creation
        runCatching {
            val helperClass = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.GestureInputHelper"
            )
            val initMethod = helperClass.getDeclaredMethod("initInputMonitor",
                String::class.java, Int::class.javaPrimitiveType!!,
                android.content.Context::class.java)
            initMethod.isAccessible = true
            hook(initMethod) { chain ->
                log("GestureFix: BLOCKED GestureInputHelper.initInputMonitor")
                // Don't call chain.proceed() — InputMonitor is never created
            }
            log("GestureFix: blocked GestureInputHelper.initInputMonitor")
        }.onFailure { log("GestureFix: GestureInputHelper.initInputMonitor failed", it) }

        // 3b. Block InputConsumer registration (defense-in-depth)
        runCatching {
            val helperClass = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.GestureInputHelper"
            )
            val registerMethod = helperClass.getDeclaredMethod("registerInputConsumer")
            registerMethod.isAccessible = true
            hook(registerMethod) { chain ->
                log("GestureFix: BLOCKED GestureInputHelper.registerInputConsumer")
                // Don't call chain.proceed() — InputConsumer is never registered
            }
            log("GestureFix: blocked GestureInputHelper.registerInputConsumer")
        }.onFailure { log("GestureFix: GestureInputHelper.registerInputConsumer failed", it) }
    }
}
