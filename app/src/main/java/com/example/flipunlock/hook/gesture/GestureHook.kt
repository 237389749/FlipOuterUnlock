package com.example.flipunlock.hook.gesture

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Hook fliphome to hide the outer screen desktop (FlipLauncher) while keeping
 * the gesture engine (TouchInteractionService + BaseGestureImpl) alive.
 *
 * Strategy: Let FlipLauncher create normally every time so the system
 * sees a successful launch (no black flash), then immediately finish it
 * in onResume so the inner screen launcher takes over.
 *
 * Key reverse-engineered insight:
 * - BaseGestureImpl.init() runs in FlipApplication.onCreate() BEFORE any Activity
 * - GestureInputHelper.initInputMonitor() registers InputMonitor independently
 * - TouchInteractionService bridges SystemUI → BaseGestureImpl for fold state
 * - FlipLauncher is only needed for desktop UI, gesture works without it
 */
object GestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    override fun setupHooks(param: PackageReadyParam) {
        hideFlipLauncher(param)
        keepTouchInteractionServiceAlive(param)
        forceGestureEnabled(param)
    }

    // ── 1. FlipLauncher: let it create, then immediately hide ─────────────
    // We let onCreate run normally so the system considers the launch successful.
    // In onResume, we immediately finish so the inner launcher takes over.
    // This avoids the black flash that happens when the system tries to launch
    // FlipLauncher and gets a blocked/null result.
    private fun hideFlipLauncher(param: PackageReadyParam) {
        runCatching {
            val flipLauncherClass = param.classLoader.findClass("com.miui.fliphome.FlipLauncher")

            // Finish immediately when it becomes visible
            runCatching {
                val onResumeMethod = flipLauncherClass.method("onResume")
                hook(onResumeMethod, after { chain, result ->
                    val launcher = chain.thisObject
                    launcher.callMethod("finish")
                    log("GestureFix: FlipLauncher finished in onResume")
                    result
                })
                log("GestureFix: hooked FlipLauncher.onResume")
            }

            // Prevent onDestroy from clearing launcher reference
            // (FlipApplication.setLauncher -> BaseGestureImpl.setLauncher chain)
            // This hook is defensive; the actual gesture engine works without it.
            runCatching {
                val onDestroyMethod = flipLauncherClass.method("onDestroy")
                hook(onDestroyMethod, Hooker { chain ->
                    // Let cleanup proceed but survive
                    chain.proceed()
                })
                log("GestureFix: hooked FlipLauncher.onDestroy")
            }
        }.onFailure { log("GestureFix: failed hook FlipLauncher", it) }
    }

    // ── 2. Keep TouchInteractionService alive ───────────────────────────
    // The service bridges SystemUI gesture state to BaseGestureImpl.
    // Prevent it from being destroyed (e.g. if FlipLauncher finish triggers cleanup).
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

    // ── 3. Force gesture input enabled ───────────────────────────────────
    // enableGestureInput guards: mGestureInputHelper != null &&
    //   mIsFolded && !isStatusBarExpand && isUserSwitched
    // Force setEnable(true) regardless of guard conditions, and block
    // disableGestureInput so gesture never gets turned off.
    // Also hook onDisplayFoldChanged to ensure registerInputConsumer
    // is called when the phone is folded (swipe-up InputMonitor).
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

            // Block disableGestureInput entirely
            runCatching {
                val disableMethod = baseGestureImplClass.method("disableGestureInput")
                hook(disableMethod, replaceResult(null))
                log("GestureFix: blocked BaseGestureImpl.disableGestureInput")
            }

            // Ensure registerInputConsumer is called when folded
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
