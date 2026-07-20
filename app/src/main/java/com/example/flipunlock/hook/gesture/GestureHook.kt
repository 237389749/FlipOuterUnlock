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
 * v3: Let fliphome InputMonitor capture touches, redirect outcomes to standard HOME.
 *
 * Why blocking InputMonitor (v2) broke Home/Recents:
 *   SystemUI's NavigationBar does NOT handle bottom gestures on flip outer screens —
 *   the gesture pill/area is absent. Blocking initInputMonitor() killed the only
 *   bottom gesture handler. Back kept working via GestureStubView (edge overlays,
 *   independent of InputMonitor).
 *
 * v3 approach:
 *   1. Let InputMonitor work (not blocked) → bottom gestures are captured
 *   2. isDisableHomeRecents() → false → GestureModeHelper never returns Empty
 *   3. GestureModeApp.performAppToHome/Recents → after: inject HOME intent
 *      (the recents animation controller is null without FlipLauncher)
 */
object GestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    private var launcherDisabled = false

    override fun setupHooks(param: PackageReadyParam) {
        log("GestureFix: setupHooks START for ${param.packageName}")
        hookNoStartPage(param)
        disableFlipLauncher(param)
        keepGesturesEnabled(param)
        fixGestureOutcome(param)
        log("GestureFix: setupHooks DONE")
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

    // ── 2. Disable FlipLauncher component (keep process alive) ───────────
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
                    val state = pm.getComponentEnabledSetting(component)
                    if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
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
        }.onFailure { log("GestureFix: FlipApplication hook failed", it) }
    }

    // ── 3. Keep gestures always enabled ──────────────────────────────────
    private fun keepGesturesEnabled(param: PackageReadyParam) {
        // 3a. isDisableHomeRecents() → false (never GestureModeEmpty)
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.fliphome.gesture.GestureModeHelper")
            val method = cls.getDeclaredMethod("isDisableHomeRecents")
            method.isAccessible = true
            hook(method, replaceResult(false))
            log("GestureFix: isDisableHomeRecents -> false")
        }.onFailure { log("GestureFix: isDisableHomeRecents failed", it) }
    }

    // ── 4. Fix gesture outcomes: inject HOME intent ──────────────────────
    //    Two key paths need fixing:
    //    A. GestureModeHome.performHomeToHome() — mLauncher is null → no HOME sent
    //    B. GestureModeApp.performAppToHome/Recents() — animation controller null → no-op
    //
    //    We hook performHomeToHome + performAppToHome + performAppToRecents
    //    with after-hooks that inject a standard HOME intent.
    //    The original code runs first (animations, cleanup), then our HOME fires.
    private fun fixGestureOutcome(param: PackageReadyParam) {
        // Shared: read mContext field from GestureMode superclass
        fun getContext(instance: Any?): Context? = runCatching {
            val modeClass = param.classLoader.loadClass("com.miui.fliphome.gesture.GestureMode")
            val field = modeClass.getDeclaredField("mContext")
            field.isAccessible = true
            field.get(instance) as? Context
        }.getOrNull()

        val homeIntentFactory: () -> Intent = {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        // 4a. GestureModeHome.performHomeToHome() — home screen swipe-up
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.fliphome.gesture.GestureModeHome")
            val method = cls.getDeclaredMethod("performHomeToHome")
            method.isAccessible = true
            hook(method, after { chain, result ->
                val ctx = getContext(chain.thisObject)
                if (ctx != null) {
                    ctx.startActivity(homeIntentFactory())
                    log("GestureFix: HOME from GestureModeHome.performHomeToHome")
                }
                result
            })
            log("GestureFix: hooked GestureModeHome.performHomeToHome")
        }.onFailure { log("GestureFix: GestureModeHome.performHomeToHome failed", it) }

        // 4b. GestureModeApp.performAppToHome() — app foreground swipe-up → home
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.fliphome.gesture.GestureModeApp")
            val method = cls.getDeclaredMethod("performAppToHome")
            method.isAccessible = true
            hook(method, after { chain, result ->
                val ctx = getContext(chain.thisObject)
                if (ctx != null) {
                    ctx.startActivity(homeIntentFactory())
                    log("GestureFix: HOME from GestureModeApp.performAppToHome")
                }
                result
            })
            log("GestureFix: hooked GestureModeApp.performAppToHome")
        }.onFailure { log("GestureFix: GestureModeApp.performAppToHome failed", it) }

        // 4c. GestureModeApp.performAppToRecents(boolean) — swipe-up-hold → recents
        //     With FlipLauncher disabled, recents view can't be shown → redirect to home.
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.fliphome.gesture.GestureModeApp")
            val method = cls.getDeclaredMethod("performAppToRecents",
                Boolean::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method, after { chain, result ->
                val ctx = getContext(chain.thisObject)
                if (ctx != null) {
                    ctx.startActivity(homeIntentFactory())
                    log("GestureFix: HOME from GestureModeApp.performAppToRecents")
                }
                result
            })
            log("GestureFix: hooked GestureModeApp.performAppToRecents")
        }.onFailure { log("GestureFix: GestureModeApp.performAppToRecents failed", it) }

        // 4d. GestureModeRecents.performRecentsToHome() — recents → home
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.fliphome.gesture.GestureModeRecents")
            val method = cls.getDeclaredMethod("performRecentsToHome")
            method.isAccessible = true
            hook(method, after { chain, result ->
                val ctx = getContext(chain.thisObject)
                if (ctx != null) {
                    ctx.startActivity(homeIntentFactory())
                    log("GestureFix: HOME from GestureModeRecents.performRecentsToHome")
                }
                result
            })
            log("GestureFix: hooked GestureModeRecents.performRecentsToHome")
        }.onFailure { log("GestureFix: GestureModeRecents.performRecentsToHome failed", it) }
    }
}
