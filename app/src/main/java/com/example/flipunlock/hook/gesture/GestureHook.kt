package com.example.flipunlock.hook.gesture

import android.os.Bundle
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Hook fliphome to disable the outer screen desktop (FlipLauncher)
 * while keeping the gesture engine (TouchInteractionService + BaseGestureImpl) alive.
 *
 * Key insight from reverse engineering:
 * - BaseGestureImpl.init() runs in FlipApplication.onCreate(), registering InputMonitor
 *   independently of FlipLauncher
 * - TouchInteractionService bridges SystemUI gesture state to BaseGestureImpl
 * - FlipLauncher is only needed for desktop UI and optional transition animations
 * - Blocking FlipLauncher.onCreate() keeps gestures working while hiding the desktop
 */
object GestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    override fun setupHooks(param: PackageReadyParam) {
        blockFlipLauncher(param)
        keepTouchInteractionServiceAlive(param)
        forceGestureEnabled(param)
    }

    // ── 1. Block FlipLauncher from showing ──────────────────────────────
    // Prevents the outer screen desktop from rendering, while the fliphome
    // process stays alive and gesture engine continues to work.
    private fun blockFlipLauncher(param: PackageReadyParam) {
        runCatching {
            val flipLauncherClass = param.classLoader.findClass("com.miui.fliphome.FlipLauncher")
            val onCreateMethod = flipLauncherClass.method("onCreate", Bundle::class.java)
            // Prevent Activity creation entirely → no desktop UI
            hook(onCreateMethod, replaceResult(null))
            log("GestureFix: blocked FlipLauncher.onCreate")

            // Also prevent onDestroy from cleaning up gesture references
            runCatching {
                val onDestroyMethod = flipLauncherClass.method("onDestroy")
                hook(onDestroyMethod, replaceResult(null))
                log("GestureFix: blocked FlipLauncher.onDestroy")
            }
        }.onFailure { log("GestureFix: failed hook FlipLauncher", it) }
    }

    // ── 2. Keep TouchInteractionService alive ───────────────────────────
    // The service bridges SystemUI gesture state to BaseGestureImpl.
    // Prevent it from being destroyed so gesture registration persists.
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

    // ── 3. Force gesture input to stay enabled ──────────────────────────
    // BaseGestureImpl.enableGestureInput() has guard conditions:
    //   mGestureInputHelper != null && mIsFolded && !isStatusBarExpand && isUserSwitched
    // Hook enableGestureInput to always call setEnable(true) regardless of guards.
    // Also hook disableGestureInput to prevent gesture from being turned off
    // when the activity state observer fires for non-FlipLauncher activities.
    private fun forceGestureEnabled(param: PackageReadyParam) {
        runCatching {
            val baseGestureImplClass = param.classLoader.findClass(
                "com.miui.fliphome.gesture.BaseGestureImpl"
            )

            // Override enableGestureInput to always activate
            runCatching {
                val enableMethod = baseGestureImplClass.method("enableGestureInput")
                hook(enableMethod) { chain ->
                    // Call original first (may have side effects)
                    chain.proceed()
                    // Force enable regardless of guard conditions
                    val helper = chain.thisObject.getField("mGestureInputHelper")
                    if (helper != null) {
                        helper.callMethod("setEnable", true)
                    }
                    null
                }
                log("GestureFix: force-enabled BaseGestureImpl.enableGestureInput")
            }

            // Block disableGestureInput entirely
            runCatching {
                val disableMethod = baseGestureImplClass.method("disableGestureInput")
                hook(disableMethod, replaceResult(null))
                log("GestureFix: blocked BaseGestureImpl.disableGestureInput")
            }
        }.onFailure { log("GestureFix: failed hook BaseGestureImpl", it) }
    }
}
