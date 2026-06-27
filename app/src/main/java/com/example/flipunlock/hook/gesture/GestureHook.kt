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
 * Hook fliphome to:
 * 1. Disable FlipLauncher → inner launcher takes over outer screen
 * 2. Force-initialize the gesture engine for folded (outer screen) state
 *
 * Key insight: BaseGestureImpl.init() registers the InputMonitor, but
 * registerInputConsumer() and addBackStubWindow() are only called from
 * onDisplayFoldChanged(true), which may never fire if FlipLauncher is
 * disabled (the fold event observer is tied to Activity lifecycle).
 *
 * We bypass the event-driven model entirely: after init(), we directly
 * set mIsFolded=true, registerInputConsumer(), addBackStubWindow(),
 * and force enableGestureInput.
 */
object GestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    private var launcherDisabled = false

    override fun setupHooks(param: PackageReadyParam) {
        hookNoStartPage(param)
        disableFlipLauncher(param)
        keepTouchInteractionServiceAlive(param)
        forceInitGestureInFoldedState(param)
        forceGestureEnabled(param)
    }

    // ── 0. No start page (ported from MixFlipMod FlipHomeHook) ───────────
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

    // ── 1. Disable FlipLauncher via PackageManager ────────────────────────
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
                        log("GestureFix: disabled FlipLauncher, keeping process alive")
                    }
                }.onFailure { log("GestureFix: failed to disable FlipLauncher", it) }
                result
            })
            log("GestureFix: hooked FlipApplication.onCreate")
        }.onFailure { log("GestureFix: failed hook FlipApplication", it) }
    }

    // ── 2. Keep TouchInteractionService alive ───────────────────────────
    private fun keepTouchInteractionServiceAlive(param: PackageReadyParam) {
        runCatching {
            val serviceClass = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.service.TouchInteractionService"
            )
            val onDestroyMethod = serviceClass.method("onDestroy")
            hook(onDestroyMethod, replaceResult(null))
            log("GestureFix: blocked TouchInteractionService.onDestroy")
        }.onFailure { log("GestureFix: failed hook TouchInteractionService", it) }
    }

    // ── 3. Force-initialize gesture for folded state ─────────────────────
    // After BaseGestureImpl.init() completes, directly activate folded mode:
    //   set mIsFolded = true
    //   registerInputConsumer()  ← activates InputMonitor for swipe-up
    //   addBackStubWindow()      ← creates edge stub views for back gesture
    // This bypasses the onDisplayFoldChanged callback which may not fire
    // when FlipLauncher is disabled.
    private fun forceInitGestureInFoldedState(param: PackageReadyParam) {
        runCatching {
            val baseGestureImplClass = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.BaseGestureImpl"
            )
            val initMethod = baseGestureImplClass.method("init")
            hook(initMethod, after { chain, result ->
                val impl = chain.thisObject
                // 1. Set mIsFolded = true (bypass fold state observer)
                impl.setField("mIsFolded", true)
                log("GestureFix: set mIsFolded=true")

                // 2. Register InputConsumer (activates swipe-up gesture monitor)
                val helper = impl.getField("mGestureInputHelper")
                if (helper != null) {
                    val isRegistered = helper.callMethod("hasRegisterInputConsumer") as? Boolean
                    if (isRegistered != true) {
                        helper.callMethod("registerInputConsumer")
                        log("GestureFix: force-registered InputConsumer after init")
                    }
                    helper.callMethod("setEnable", true)
                    log("GestureFix: force-enabled gesture input after init")
                }

                // 3. Add back stub windows (edge swipe visual indicators)
                // Deferred to background executor like the original does
                impl.callMethod("addBackStubWindow")
                log("GestureFix: triggered addBackStubWindow")

                result
            })
            log("GestureFix: hooked BaseGestureImpl.init for folded-state init")
        }.onFailure { log("GestureFix: failed hook BaseGestureImpl.init", it) }
    }

    // ── 4. Force gesture input always enabled ────────────────────────────
    // Also hook onDisplayFoldChanged to ensure everything stays active.
    private fun forceGestureEnabled(param: PackageReadyParam) {
        runCatching {
            val baseGestureImplClass = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.BaseGestureImpl"
            )

            // Force enableGestureInput → always setEnable(true)
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

            // Block disableGestureInput
            runCatching {
                val disableMethod = baseGestureImplClass.method("disableGestureInput")
                hook(disableMethod, replaceResult(null))
                log("GestureFix: blocked BaseGestureImpl.disableGestureInput")
            }

            // Reinforce on fold change
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
                                log("GestureFix: re-registered InputConsumer on fold")
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
