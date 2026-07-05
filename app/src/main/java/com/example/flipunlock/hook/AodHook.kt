package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Enable Always-On Display on the outer screen when folded.
 *
 * com.miui.aod.Utils.isAodEnable() normally goes through
 * FlipLinkageStyleController.isUsingFlip() when folded,
 * which returns false unless the linkage style (flip case)
 * is enabled. We bypass this gate ONLY for the outer screen,
 * preserving normal AOD on/off behavior for the inner screen.
 */
object AodHook : BaseHook() {
    override val targetPackages = listOf("com.miui.aod")

    override fun setupHooks(param: PackageReadyParam) {
        runCatching {
            val utilsClass = param.classLoader.loadClass("com.miui.aod.Utils")

            // Hook isAodEnable: only override when on outer screen.
            // Let original logic handle inner screen (uses isAodSettingsEnabled).
            val enableMethod = utilsClass.method("isAodEnable", android.content.Context::class.java)
            hook(enableMethod) { chain ->
                val ctx = chain.args[0] as? android.content.Context
                val metrics = ctx?.resources?.displayMetrics
                if (metrics != null && metrics.heightPixels in 1000..1500) {
                    // Outer screen (1208x1392): force true
                    true
                } else {
                    // Inner screen: original logic
                    chain.proceed()
                }
            }
            log("AodHook: hooked isAodEnable — outer screen forced, inner screen normal")

            // DeviceIdentityHook makes isFlipDevice()→false, which makes
            // isFolded()→false. AOD checks isFolded to decide whether
            // to stay on — must be true on outer screen.
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
            log("AodHook: hooked isFolded — outer screen forced, inner screen normal")
        }.onFailure { log("AodHook: failed", it) }
    }
}
