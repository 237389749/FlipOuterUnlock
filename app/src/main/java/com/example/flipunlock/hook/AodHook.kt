package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Enable Always-On Display on the outer screen when folded.
 *
 * com.miui.aod.Utils.isAodEnable() normally goes through
 * FlipLinkageStyleController.isUsingFlip() when folded, which returns
 * false unless the linkage style (flip case) is enabled.
 *
 * We hook two methods:
 *   isAodEnable(Context) → force true on outer screen
 *   isFolded(Context)    → force true on outer screen
 *
 * DeviceIdentityHook makes isFlipDevice→false, which makes isFolded→false.
 * We override isFolded back to true for the outer screen so AOD stays on.
 *
 * Works together with DisplayStateHook §4 which prevents the framework
 * from putting the device to sleep when folded.
 */
object AodHook : BaseHook() {
    override val targetPackages = listOf("com.miui.aod")

    override fun setupHooks(param: PackageReadyParam) {
        runCatching {
            val utilsClass = param.classLoader.loadClass("com.miui.aod.Utils")

            // isAodEnable(Context) — force true on outer screen
            runCatching {
                val method = utilsClass.method(
                    "isAodEnable", android.content.Context::class.java)
                hook(method) { chain ->
                    val ctx = chain.args[0] as? android.content.Context
                    val metrics = ctx?.resources?.displayMetrics
                    if (metrics != null && metrics.heightPixels in 1000..1500) {
                        true  // outer screen (1208x1392): force AOD enabled
                    } else {
                        chain.proceed()  // inner screen: original logic
                    }
                }
                log("AodHook: hooked isAodEnable")
            }.onFailure { log("AodHook: isAodEnable failed", it) }

            // isFolded(Context) — force true on outer screen
            runCatching {
                val method = utilsClass.method(
                    "isFolded", android.content.Context::class.java)
                hook(method) { chain ->
                    val ctx = chain.args[0] as? android.content.Context
                    val metrics = ctx?.resources?.displayMetrics
                    if (metrics != null && metrics.heightPixels in 1000..1500) {
                        true  // outer screen: force folded
                    } else {
                        chain.proceed()  // inner screen: original logic
                    }
                }
                log("AodHook: hooked isFolded")
            }.onFailure { log("AodHook: isFolded failed", it) }

            // getShowStyle(Context) — force non-temporary on outer screen
            // Default is 0 (Temporary=5s). Force 2 (Always on) or 3 (Smart).
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
        }.onFailure { log("AodHook: failed", it) }
    }
}
