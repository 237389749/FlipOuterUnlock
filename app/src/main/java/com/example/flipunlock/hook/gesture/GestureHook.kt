package com.example.flipunlock.hook.gesture

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Hook fliphome to ensure the gesture engine (TouchInteractionService +
 * BaseGestureImpl) stays alive and active, regardless of FlipLauncher state.
 *
 * This hook does NOT modify FlipLauncher itself. The user should switch
 * the default home activity separately (e.g. via LSPosed scope or
 * `cmd package set-home-activity`).
 *
 * Gesture engine initialization chain (from reverse engineering):
 *   FlipApplication.attachBaseContext() → new BaseGestureImpl
 *   FlipApplication.onCreate() → BaseGestureImpl.init()
 *     → GestureInputHelper.initInputMonitor("swipe-up", displayId, ctx)
 *     → InputMonitor registered independently of FlipLauncher
 *   TouchInteractionService.onSystemUiFlagsChanged()
 *     → BaseGestureImpl.onSystemUiFlagsChanged()
 *
 * Hooks applied:
 * 1. Block TouchInteractionService.onDestroy → keep service alive
 * 2. Force BaseGestureImpl.enableGestureInput → always setEnable(true)
 * 3. Block BaseGestureImpl.disableGestureInput → prevent gesture loss
 * 4. Hook onDisplayFoldChanged → force registerInputConsumer when folded
 */
object GestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    override fun setupHooks(param: PackageReadyParam) {
        keepTouchInteractionServiceAlive(param)
        forceGestureEnabled(param)
    }

    // ── 1. Keep TouchInteractionService alive ───────────────────────────
    // This service bridges SystemUI gesture state to BaseGestureImpl.
    // The system binds it as QUICKSTEP_SERVICE. Prevent onDestroy so it
    // never gets killed even if FlipLauncher is removed.
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

    // ── 2. Force gesture input always enabled ────────────────────────────
    // enableGestureInput has guard conditions:
    //   mGestureInputHelper != null && mIsFolded
    //   && !isStatusBarExpand && isUserSwitched
    // We force setEnable(true) after every call to enableGestureInput,
    // and block disableGestureInput entirely.
    private fun forceGestureEnabled(param: PackageReadyParam) {
        runCatching {
            val baseGestureImplClass = param.classLoader.findClass(
                "com.miui.fliphome.gesture.BaseGestureImpl"
            )

            // After enableGestureInput, force setEnable(true)
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

            // Force registerInputConsumer when phone is folded
            // (normally called in onDisplayFoldChanged(true))
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
