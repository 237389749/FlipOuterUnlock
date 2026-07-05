package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Enable Always-On Display on the outer screen when folded.
 * EXPERIMENTAL — see known issues in README.
 *
 * com.miui.aod.Utils.isAodEnable() normally goes through
 * FlipLinkageStyleController.isUsingFlip() when folded,
 * which returns false unless the linkage style (flip case)
 * is enabled. We bypass this gate ONLY for the outer screen.
 */
object AodHook : BaseHook() {
    override val targetPackages = listOf("com.miui.aod")

    override fun setupHooks(param: PackageReadyParam) {
        runCatching {
            val utilsClass = param.classLoader.loadClass("com.miui.aod.Utils")

            val enableMethod = utilsClass.method("isAodEnable", android.content.Context::class.java)
            hook(enableMethod) { chain ->
                val ctx = chain.args[0] as? android.content.Context
                val metrics = ctx?.resources?.displayMetrics
                if (metrics != null && metrics.heightPixels in 1000..1500) {
                    // Outer screen: bypass FlipLinkageStyleController gate
                    true
                } else {
                    chain.proceed()
                }
            }
            log("AodHook: outer screen isAodEnable forced true, inner screen normal")

            // isFolded: DeviceIdentityHook makes isFlipDevice→false, so the
            // flip branch is skipped. isFoldDevice is false (Mix Flip is not
            // a large fold). So isFolded always returns false — force true.
            val foldedMethod = utilsClass.method("isFolded", android.content.Context::class.java)
            hook(foldedMethod) { chain ->
                val ctx = chain.args[0] as? android.content.Context
                val metrics = ctx?.resources?.displayMetrics
                if (metrics != null && metrics.heightPixels in 1000..1500) {
                    true
                } else {
                    chain.proceed()
                }
            }
            log("AodHook: outer screen isFolded forced true")
        }.onFailure { log("AodHook: failed", it) }
    }
}
