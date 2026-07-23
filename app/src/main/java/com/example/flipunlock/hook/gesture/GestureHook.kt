package com.example.flipunlock.hook.gesture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Disable fliphome launcher UI and interstitial start pages.
 *
 * With state=6 (DUAL, outer=displayId=0), the inner launcher (com.miui.home)
 * runs on the outer screen. fliphome's FlipLauncher UI should be hidden.
 *
 * Bottom gesture navigation in apps is handled by SystemUI's NavigationBar
 * (fixed in SystemUIHook), not by fliphome's InputMonitor.
 *
 * Back gestures work via fliphome's GestureStubView (edge overlays),
 * which are independent of our hooks.
 */
object GestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    private var launcherDisabled = false

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.gestureBack) { log("GestureFix: DISABLED by persist.flipunlock.gesture.back"); return }
        log("GestureFix: setupHooks")
        hookNoStartPage(param)
        disableFlipLauncher(param)
        hookSideGesturePersistence(param)
    }

    // ── 1. No start page (ported from MixFlipMod) ────────────────────────
    private fun hookNoStartPage(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.fliphome.utils.PerformLaunchAction")
            val method = cls.method("onStartIntercept",
                UserHandle::class.java, Intent::class.java, Bundle::class.java, View::class.java)
            hook(method, replaceResult(false))
            log("GestureFix: disabled start page intercept")
        }.onFailure { log("GestureFix: PerformLaunchAction not found", it) }
    }

    // ── 2. Prevent fold state changes from destroying side gesture stubs ──
    //
    // BaseGestureImpl.onDisplayFoldChanged(false) triggers:
    //   clearBackStubWindow() → GestureStubView.clearGestureStub()
    //     → hideGestureStub() + wm.removeView(this)  ← WINDOW DESTROYED
    //     → mGestureStubLeft/Right = null
    //   disableGestureInput() → mGestureInputHelper.setEnable(false)
    //
    // Once the stubs are destroyed, showBackStubWindow() is a no-op
    // (null check returns early). They only come back if onDisplayFoldChanged(true)
    // fires again, which may never happen due to our display state hooks.
    //
    // Fix: block clearBackStubWindow + disableGestureInput at the source.
    // Also force mIsFolded=true so all the guards pass.
    private fun hookSideGesturePersistence(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.BaseGestureImpl")

            // Block clearBackStubWindow() — prevents GestureStubView removal
            runCatching {
                val method = cls.getDeclaredMethod("clearBackStubWindow")
                method.isAccessible = true
                hook(method) {
                    log("GestureFix: BLOCKED clearBackStubWindow")
                    null
                }
            }

            // Block disableGestureInput() — keeps InputMonitor alive
            runCatching {
                val method = cls.getDeclaredMethod("disableGestureInput")
                method.isAccessible = true
                hook(method) {
                    log("GestureFix: BLOCKED disableGestureInput")
                    null
                }
            }

            // Force mIsFolded=true on every onDisplayFoldChanged call
            runCatching {
                val foldedField = cls.getDeclaredField("mIsFolded")
                foldedField.isAccessible = true
                // Hook methods that read mIsFolded: updateFsgWindowStateForHome, enableGestureInput, onResumed
                // The simplest: hook onResumed to force mIsFolded=true first
                val onResumedMethod = cls.getDeclaredMethod("onResumed",
                    android.content.ComponentName::class.java)
                onResumedMethod.isAccessible = true
                hook(onResumedMethod, before { chain ->
                    val obj = chain.thisObject
                    runCatching { foldedField.setBoolean(obj, true) }
                })
                // Also hook the fold change path: find and hook the display fold listener
                // The BroadcastManager sends onDisplayFoldChanged via AbstractSystemEventPresenter
                // We hook hideBackStubWindow as defense-in-depth
                runCatching {
                    val hideMethod = cls.getDeclaredMethod("hideBackStubWindow")
                    hideMethod.isAccessible = true
                    hook(hideMethod) {
                        log("GestureFix: BLOCKED hideBackStubWindow")
                        null
                    }
                }
            }

            log("GestureFix: side gesture persistence hooks installed")
        }.onFailure { log("GestureFix: side gesture persistence failed", it) }
    }

    // ── 3. Disable FlipLauncher component (keep process alive) ───────────
    private fun disableFlipLauncher(param: PackageReadyParam) {
        if (launcherDisabled) return
        runCatching {
            val flipAppClass = param.classLoader.loadClass("com.miui.fliphome.FlipApplication")
            hook(flipAppClass.method("onCreate"), after { chain, result ->
                if (launcherDisabled) return@after result
                runCatching {
                    val app = chain.thisObject
                    val ctx = app.callMethod("getApplicationContext") as? Context ?: return@runCatching
                    val component = ComponentName("com.miui.fliphome", "com.miui.fliphome.FlipLauncher")
                    val pm = ctx.packageManager
                    if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        pm.setComponentEnabledSetting(component,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP)
                        launcherDisabled = true
                        log("GestureFix: disabled FlipLauncher")
                    }
                }.onFailure { log("GestureFix: disable FlipLauncher err", it) }
                result
            })
            log("GestureFix: hooked FlipApplication.onCreate")
        }.onFailure { log("GestureFix: FlipApplication failed", it) }
    }
}
