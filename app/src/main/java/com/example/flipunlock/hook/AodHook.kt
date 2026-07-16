package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Enable Always-On Display on the outer screen when folded.
 *
 * DeviceIdentityHook makes isFlipDevice→false globally. This breaks AOD:
 *   1. dealWithFlipChange() skipped → no flip init
 *   2. DozeService.create() skips fold listener registration
 *   3. isAodEnable() blocked by FlipLinkageStyleController gate
 *   4. getShowStyle() defaults to 0 (Temporary, 5s timeout)
 *   5. isFullAod() removes clock container → black screen
 *
 * Fixes: restore isFlipDevice, then override individual methods.
 */
object AodHook : BaseHook() {
    override val targetPackages = listOf("com.miui.aod", "com.android.systemui")

    override fun setupHooks(param: PackageReadyParam) {
        runCatching {
            val utilsClass = param.classLoader.loadClass("com.miui.aod.Utils")

            // 1. isFlipDevice() → true (reverses DeviceIdentityHook)
            //    Fixes: dealWithFlipChange init + DozeService fold listener
            runCatching {
                val method = utilsClass.method("isFlipDevice")
                hook(method, replaceResult(true))
                log("AodHook: isFlipDevice → true")
            }.onFailure { log("AodHook: isFlipDevice failed", it) }

            // 2. isAodEnable(Context) → force true on outer screen
            //    FlipLinkageStyleController.isUsingFlip may return false
            runCatching {
                val method = utilsClass.method(
                    "isAodEnable", android.content.Context::class.java)
                hook(method) { chain ->
                    val ctx = chain.args[0] as? android.content.Context
                    val metrics = ctx?.resources?.displayMetrics
                    if (metrics != null && metrics.heightPixels in 1000..1500) {
                        true  // outer screen (1208x1392)
                    } else {
                        chain.proceed()  // inner screen: original logic
                    }
                }
                log("AodHook: hooked isAodEnable")
            }.onFailure { log("AodHook: isAodEnable failed", it) }

            // 3. isFolded(Context) → force true on outer screen
            runCatching {
                val method = utilsClass.method(
                    "isFolded", android.content.Context::class.java)
                hook(method) { chain ->
                    val ctx = chain.args[0] as? android.content.Context
                    val metrics = ctx?.resources?.displayMetrics
                    if (metrics != null && metrics.heightPixels in 1000..1500) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
                log("AodHook: hooked isFolded")
            }.onFailure { log("AodHook: isFolded failed", it) }

            // 4. getShowStyle(Context) → force Always-on (2) on outer screen
            runCatching {
                val method = utilsClass.method(
                    "getShowStyle", android.content.Context::class.java)
                hook(method) { chain ->
                    val result = (chain.proceed() as? Int) ?: 0
                    val ctx = chain.args[0] as? android.content.Context
                    val metrics = ctx?.resources?.displayMetrics
                    if (metrics != null && metrics.heightPixels in 1000..1500 && result == 0) {
                        log("AodHook: getShowStyle 0 → 2 (outer screen)")
                        2  // Always on
                    } else {
                        result
                    }
                }
                log("AodHook: hooked getShowStyle")
            }.onFailure { log("AodHook: getShowStyle failed", it) }
        }.onFailure { log("AodHook: Utils hooks failed", it) }

        // 5. DozeHost.isFullAod() → false
        //    When true, prepareAodViewAndShow() removes clock container → black AOD
        runCatching {
            val dozeHostClass = param.classLoader.loadClass("com.miui.aod.DozeHost")
            val method = dozeHostClass.method("isFullAod")
            hook(method, replaceResult(false))
            log("AodHook: DozeHost.isFullAod → false")
        }.onFailure { log("AodHook: isFullAod failed", it) }

        // 6. AODSettings.needKeepScreenOnAtFirst() → false
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.aod.widget.AODSettings")
            val method = cls.method("needKeepScreenOnAtFirst")
            hook(method, replaceResult(false))
            log("AodHook: needKeepScreenOnAtFirst → false")
        }.onFailure { log("AodHook: needKeepScreenOnAtFirst failed", it) }

        // 7. DozeService.setDozeScreenState(int) → block OFF/SUSPEND
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.aod.doze.DozeService")
            val method = cls.method(
                "setDozeScreenState", Int::class.javaPrimitiveType!!)
            hook(method) { chain ->
                val state = chain.args[0] as? Int ?: return@hook chain.proceed()
                if (state == 1 || state == 4) {
                    log("AodHook: blocked setDozeScreenState($state)")
                    return@hook null
                }
                chain.proceed()
            }
            log("AodHook: hooked DozeService.setDozeScreenState")
        }.onFailure { log("AodHook: setDozeScreenState failed", it) }
    }
}
