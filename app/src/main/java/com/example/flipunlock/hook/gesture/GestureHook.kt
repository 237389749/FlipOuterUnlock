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
 * v3: Let fliphome InputMonitor work but redirect gestures to standard HOME.
 *
 * Architecture of fliphome gesture system:
 *   GestureStubView (edge back)  ← WindowManager overlays, independent of InputMonitor
 *   GestureInputHelper.InputMonitor ← captures ALL touches on the display
 *     → GestureTouchEventTracker → GestureMode (Home / App / Recents / Keyguard / Empty)
 *
 * v2 blocked initInputMonitor() entirely. This killed Home/Recents (InputMonitor-dependent)
 * while Back continued working via GestureStubView edge overlays.
 *
 * v3 lets the InputMonitor capture touches but fixes the outcomes:
 *   1. InputMonitor works normally (not blocked)
 *   2. isDisableHomeRecents() → false (never Empty mode)
 *   3. GestureModeHome: inject HOME intent when FlipLauncher is null
 *   4. GestureModeApp: fallback HOME intent
 *   5. FlipLauncher component disabled (hide UI, keep process alive)
 */
object GestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    private var launcherDisabled = false

    override fun setupHooks(param: PackageReadyParam) {
        hookNoStartPage(param)
        disableFlipLauncher(param)
        keepGesturesEnabled(param)
        fixHomeGestureOutcome(param)
        fixAppGestureOutcome(param)
    }

    // ── 1. No start page (ported from MixFlipMod) ────────────────────────
    private fun hookNoStartPage(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.fliphome.utils.PerformLaunchAction")
            val method = cls.method("onStartIntercept",
                UserHandle::class.java, Intent::class.java, Bundle::class.java, View::class.java)
            hook(method, replaceResult(false))
            log("GestureFix: disabled start page intercept")
        }.onFailure { log("GestureFix: PerformLaunchAction not found (harmless)", it) }
    }

    // ── 2. Disable FlipLauncher component ─────────────────────────────────
    private fun disableFlipLauncher(param: PackageReadyParam) {
        if (launcherDisabled) return
        runCatching {
            val flipAppClass = param.classLoader.loadClass(
                "com.miui.fliphome.FlipApplication")
            hook(flipAppClass.method("onCreate"), after { chain, result ->
                if (launcherDisabled) return@after result
                runCatching {
                    val app = chain.thisObject
                    val ctx = app.callMethod("getApplicationContext") as? Context
                        ?: return@runCatching
                    val component = ComponentName(
                        "com.miui.fliphome", "com.miui.fliphome.FlipLauncher")
                    val pm = ctx.packageManager
                    val state = pm.getComponentEnabledSetting(component)
                    if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        pm.setComponentEnabledSetting(component,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP)
                        launcherDisabled = true
                        log("GestureFix: disabled FlipLauncher")
                    }
                }.onFailure { log("GestureFix: disable FlipLauncher failed", it) }
                result
            })
            log("GestureFix: hooked FlipApplication.onCreate")
        }.onFailure { log("GestureFix: FlipApplication hook failed", it) }
    }

    // ── 3. Keep gestures always enabled ──────────────────────────────────
    private fun keepGesturesEnabled(param: PackageReadyParam) {
        // 3a. GestureModeHelper.isDisableHomeRecents() → false
        //     When SystemUI sets flags that disable nav bar, fliphome normally
        //     switches to GestureModeEmpty (no-op). Force false so gestures
        //     are always processed.
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.GestureModeHelper")
            val method = cls.getDeclaredMethod("isDisableHomeRecents")
            method.isAccessible = true
            hook(method, replaceResult(false))
            log("GestureFix: isDisableHomeRecents → false")
        }.onFailure { log("GestureFix: isDisableHomeRecents failed", it) }

        // 3b. Block clearBackStubWindow() → keep edge back stubs alive
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.BaseGestureImpl")
            val method = cls.getDeclaredMethod("clearBackStubWindow")
            method.isAccessible = true
            hook(method) { chain ->
                log("GestureFix: BLOCKED clearBackStubWindow")
            }
            log("GestureFix: blocked clearBackStubWindow")
        }.onFailure { log("GestureFix: clearBackStubWindow failed", it) }

        // 3c. Block disableGestureInput() → always keep input enabled
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.BaseGestureImpl")
            val method = cls.getDeclaredMethod("disableGestureInput")
            method.isAccessible = true
            hook(method) { chain ->
                log("GestureFix: BLOCKED disableGestureInput")
            }
            log("GestureFix: blocked disableGestureInput")
        }.onFailure { log("GestureFix: disableGestureInput failed", it) }
    }

    // ── 4. Fix GestureModeHome — inject HOME intent ──────────────────────
    //    GestureModeHome.performHomeToHome() and checkIfStartHome() depend
    //    on FlipLauncher (mLauncher field). When FlipLauncher is disabled,
    //    mLauncher is null and no HOME action is taken.
    //
    //    Fix: hook checkIfStartHome() to send HOME intent directly.
    //    This covers swipe-up-from-home → return to real launcher.
    private fun fixHomeGestureOutcome(param: PackageReadyParam) {
        val gestureModeClass = param.classLoader.loadClass(
            "com.miui.fliphome.gesture.GestureMode")
        val mContextField = gestureModeClass.getDeclaredField("mContext")
        mContextField.isAccessible = true

        // Hook checkIfStartHome() — primary home path
        runCatching {
            val homeClass = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.GestureModeHome")
            val method = homeClass.getDeclaredMethod("checkIfStartHome")
            method.isAccessible = true
            hook(method) { chain ->
                log("GestureFix: checkIfStartHome → sending HOME intent")
                runCatching {
                    val ctx = mContextField.get(chain.thisObject) as? Context
                    if (ctx != null) {
                        ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        })
                        log("GestureFix: HOME intent sent from checkIfStartHome")
                    }
                }.onFailure { log("GestureFix: checkIfStartHome HOME failed", it) }
                chain.proceed()
            }
            log("GestureFix: hooked GestureModeHome.checkIfStartHome")
        }.onFailure { log("GestureFix: checkIfStartHome hook failed", it) }

        // Hook performHomeToHome() — secondary path (after hold state)
        runCatching {
            val homeClass = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.GestureModeHome")
            val method = homeClass.getDeclaredMethod("performHomeToHome")
            method.isAccessible = true
            hook(method, after { chain, result ->
                runCatching {
                    val ctx = mContextField.get(chain.thisObject) as? Context
                    if (ctx != null) {
                        ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                        log("GestureFix: HOME intent sent from performHomeToHome")
                    }
                }.onFailure { log("GestureFix: performHomeToHome HOME failed", it) }
                result
            })
            log("GestureFix: hooked GestureModeHome.performHomeToHome")
        }.onFailure { log("GestureFix: performHomeToHome hook failed", it) }
    }

    // ── 5. Fix GestureModeApp — redirect to home ─────────────────────────
    //    With miuihome (com.miui.home) as the active home, isHomeStackVisible()
    //    returns false (it checks for com.miui.fliphome package). So most
    //    bottom gestures end up in GestureModeApp, not GestureModeHome.
    //
    //    performAppToHome() → recents animation → finish(true). But without
    //    FlipLauncher, the recents animation controller is null → no-op.
    //    We inject HOME intent as fallback.
    //
    //    performAppToRecents() → tries to show FlipLauncher's recents view.
    //    Without FlipLauncher, this fails. Redirect to home instead.
    private fun fixAppGestureOutcome(param: PackageReadyParam) {
        val gestureModeClass = param.classLoader.loadClass(
            "com.miui.fliphome.gesture.GestureMode")
        val mContextField = gestureModeClass.getDeclaredField("mContext")
        mContextField.isAccessible = true

        val appClass = param.classLoader.loadClass(
            "com.miui.fliphome.gesture.GestureModeApp")

        // 5a. performAppToHome() → fallback HOME intent
        //     Original code calls finish(true) via recents animation controller.
        //     When FlipLauncher is disabled, the controller is null → nothing happens.
        //     Hook to send HOME intent as guaranteed fallback.
        runCatching {
            val method = appClass.getDeclaredMethod("performAppToHome")
            method.isAccessible = true
            hook(method, after { chain, result ->
                runCatching {
                    val ctx = mContextField.get(chain.thisObject) as? Context
                    if (ctx != null) {
                        ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                        log("GestureFix: HOME intent sent from GestureModeApp.performAppToHome")
                    }
                }.onFailure { log("GestureFix: performAppToHome HOME failed", it) }
                result
            })
            log("GestureFix: hooked GestureModeApp.performAppToHome")
        }.onFailure { log("GestureFix: performAppToHome hook failed", it) }

        // 5b. performAppToRecents(boolean) → redirect to home
        //     fliphome's recents view requires FlipLauncher. Without it,
        //     recents can't be shown. Redirect to home as reasonable alternative.
        runCatching {
            val method = appClass.getDeclaredMethod("performAppToRecents",
                Boolean::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method, after { chain, result ->
                runCatching {
                    val ctx = mContextField.get(chain.thisObject) as? Context
                    if (ctx != null) {
                        ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                        log("GestureFix: HOME intent sent from GestureModeApp.performAppToRecents")
                    }
                }.onFailure { log("GestureFix: performAppToRecents HOME failed", it) }
                result
            })
            log("GestureFix: hooked GestureModeApp.performAppToRecents")
        }.onFailure { log("GestureFix: performAppToRecents hook failed", it) }

        // 5c. GestureModeRecents.performRecentsToHome() → ensure HOME
        runCatching {
            val recentsClass = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.GestureModeRecents")
            val method = recentsClass.getDeclaredMethod("performRecentsToHome")
            method.isAccessible = true
            hook(method, after { chain, result ->
                runCatching {
                    val ctx = mContextField.get(chain.thisObject) as? Context
                    if (ctx != null) {
                        ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                        log("GestureFix: HOME intent sent from GestureModeRecents")
                    }
                }.onFailure { log("GestureFix: performRecentsToHome HOME failed", it) }
                result
            })
            log("GestureFix: hooked GestureModeRecents.performRecentsToHome")
        }.onFailure { log("GestureFix: performRecentsToHome hook failed", it) }
    }
}
