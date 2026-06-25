package com.example.flipunlock.hook.gesture

import android.content.ComponentName
import android.content.pm.PackageManager
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Hook fliphome to:
 * 1. Disable FlipLauncher (outer screen desktop) so the system falls back
 *    to the inner screen launcher (com.miui.home)
 * 2. Keep the gesture engine (TouchInteractionService + BaseGestureImpl)
 *    alive and active
 *
 * Disabling via PackageManager.setComponentEnabledSetting with DONT_KILL_APP
 * keeps the fliphome process alive (TouchInteractionService still runs as
 * QUICKSTEP_SERVICE) while hiding FlipLauncher from the system's home
 * activity resolution.
 *
 * Gesture engine initialization (from reverse engineering):
 *   FlipApplication.attachBaseContext() → new BaseGestureImpl
 *   FlipApplication.onCreate() → BaseGestureImpl.init()
 *     → GestureInputHelper.initInputMonitor("swipe-up", displayId, ctx)
 *     → InputMonitor registered independently of FlipLauncher
 */
object GestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    private var launcherDisabled = false

    override fun setupHooks(param: PackageReadyParam) {
        disableFlipLauncher(param)
        keepTouchInteractionServiceAlive(param)
        forceGestureEnabled(param)
    }

    // ── 1. Disable FlipLauncher via PackageManager ────────────────────────
    // Called from fliphome's own process, so we have permission to disable
    // our own component. DONT_KILL_APP keeps the process alive.
    private fun disableFlipLauncher(param: PackageReadyParam) {
        if (launcherDisabled) return
        runCatching {
            val flipAppClass = param.classLoader.findClass(
                "com.miui.fliphome.FlipApplication"
            )
            // Hook onCreate which runs early — before FlipLauncher is created
            val onCreateMethod = flipAppClass.method("onCreate")
            hook(onCreateMethod, after { chain, result ->
                if (launcherDisabled) return@after
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
                        log("GestureFix: disabled FlipLauncher, keeping process alive")
                    }
                }.onFailure { log("GestureFix: failed to disable FlipLauncher", it) }
                result
            })
            log("GestureFix: hooked FlipApplication.onCreate for launcher disable")
        }.onFailure { log("GestureFix: failed hook FlipApplication", it) }
    }

    // ── 2. Keep TouchInteractionService alive ───────────────────────────
    private fun keepTouchInteractionServiceAlive(param: PackageReadyParam) {
        runCatching {
            val serviceClass = param.classLoader.findClass(
                "com.miui.fliphome.gesture.service.TouchInteractionService"
            )
            val onDestroyMethod = serviceClass.method("onDestroy")
            hook(onDestroyMethod, replaceResult(null))
            log("GestureFix: blocked TouchInteractionService.onDestroy")
        }.onFailure { log("GestureFix: failed hook TouchInteractionService", it) }
    }

    // ── 3. Force gesture input always enabled ────────────────────────────
    private fun forceGestureEnabled(param: PackageReadyParam) {
        runCatching {
            val baseGestureImplClass = param.classLoader.findClass(
                "com.miui.fliphome.gesture.BaseGestureImpl"
            )

            // After enableGestureInput, force setEnable(true) regardless of guards
            runCatching {
                val enableMethod = baseGestureImplClass.method("enableGestureInput")
                hook(enableMethod, after { chain, result ->
                    val helper = chain.thisObject.getField("mGestureInputHelper")
                    if (helper != null) {
                        helper.callMethod("setEnable", true)
                    }
                    result
                })
                log("GestureFix: force-enabled BaseGestureImpl.enableGestureInput")
            }

            // Block disableGestureInput completely
            runCatching {
                val disableMethod = baseGestureImplClass.method("disableGestureInput")
                hook(disableMethod, replaceResult(null))
                log("GestureFix: blocked BaseGestureImpl.disableGestureInput")
            }

            // Force registerInputConsumer when folded
            runCatching {
                val onFoldChanged = baseGestureImplClass.method(
                    "onDisplayFoldChanged", Boolean::class.javaPrimitiveType!!
                )
                hook(onFoldChanged, after { chain, result ->
                    val isFolded = chain.args[0] as? Boolean ?: false
                    if (isFolded) {
                        val helper = chain.thisObject.getField("mGestureInputHelper")
                        if (helper != null) {
                            val isRegistered = helper.callMethod("hasRegisterInputConsumer") as? Boolean
                            if (isRegistered != true) {
                                helper.callMethod("registerInputConsumer")
                                log("GestureFix: force-registered InputConsumer")
                            }
                        }
                    }
                    result
                })
                log("GestureFix: hooked BaseGestureImpl.onDisplayFoldChanged")
            }
        }.onFailure { log("GestureFix: failed hook BaseGestureImpl", it) }
    }
}
