package com.example.flipunlock.hook.gesture

import android.os.Bundle
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Hook fliphome to hide the outer screen desktop (FlipLauncher) while keeping
 * the gesture engine (TouchInteractionService + BaseGestureImpl) alive.
 *
 * Strategy: Let FlipLauncher create once for full initialization (gesture
 * bindings, InputMonitor registration, fold-state callbacks), then immediately
 * finish it so the system falls back to the inner screen launcher.
 * Subsequent launch attempts are blocked.
 *
 * Key reverse-engineered insight:
 * - BaseGestureImpl.init() runs in FlipApplication.onCreate() BEFORE any Activity
 * - GestureInputHelper.initInputMonitor() registers InputMonitor independently
 * - TouchInteractionService bridges SystemUI → BaseGestureImpl for fold state
 * - FlipLauncher.setLauncher() passes reference for transition animations (optional)
 */
object GestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    // Track whether FlipLauncher has already been initialized once.
    // We let the first creation through so gesture callbacks fire,
    // then block subsequent ones to prevent the launcher from reappearing.
    @Volatile
    private var flipLauncherInitialized = false

    override fun setupHooks(param: PackageReadyParam) {
        handleFlipLauncherLifecycle(param)
        keepTouchInteractionServiceAlive(param)
        forceGestureEnabled(param)
    }

    // ── 1. FlipLauncher: create once, then hide forever ───────────────────
    private fun handleFlipLauncherLifecycle(param: PackageReadyParam) {
        runCatching {
            val flipLauncherClass = param.classLoader.findClass("com.miui.fliphome.FlipLauncher")

            // Hook onResume: finish after first successful creation
            runCatching {
                val onResumeMethod = flipLauncherClass.method("onResume")
                hook(onResumeMethod, after { chain, result ->
                    if (!flipLauncherInitialized) {
                        flipLauncherInitialized = true
                        log("GestureFix: FlipLauncher first init done, hiding")
                        // Post to main thread: finish the activity so system
                        // falls back to inner screen launcher
                        val launcher = chain.thisObject
                        launcher.callMethod("moveTaskToBack", true)
                        launcher.callMethod("finish")
                    }
                    result
                })
                log("GestureFix: hooked FlipLauncher.onResume")
            }

            // Block subsequent onCreate to prevent re-start
            runCatching {
                val onCreateMethod = flipLauncherClass.method("onCreate", Bundle::class.java)
                hook(onCreateMethod, Hooker { chain ->
                    if (flipLauncherInitialized) {
                        // Already initialized, block re-creation
                        null
                    } else {
                        chain.proceed()
                    }
                })
                log("GestureFix: hooked FlipLauncher.onCreate")
            }

            // Prevent onDestroy cleanup (keeps BaseGestureImpl launcher ref)
            runCatching {
                val onDestroyMethod = flipLauncherClass.method("onDestroy")
                hook(onDestroyMethod, Hooker { chain ->
                    // Allow the first destroy (cleanup after finish)
                    // but prevent clearing the launcher reference from
                    // FlipApplication by skipping the cleanup code.
                    // Actually, let the original run but survive.
                    chain.proceed()
                })
                log("GestureFix: hooked FlipLauncher.onDestroy")
            }
        }.onFailure { log("GestureFix: failed hook FlipLauncher", it) }
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

    // ── 3. Force gesture input enabled ───────────────────────────────────
    // enableGestureInput guards: mGestureInputHelper != null &&
    //   mIsFolded && !isStatusBarExpand && isUserSwitched
    // Force setEnable(true) regardless, and block disableGestureInput
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
