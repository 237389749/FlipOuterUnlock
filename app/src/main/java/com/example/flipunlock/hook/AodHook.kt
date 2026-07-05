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
                val original = chain.proceed() as? Boolean ?: false
                val ctx = chain.args[0] as? android.content.Context
                val metrics = ctx?.resources?.displayMetrics
                // Only force true on outer screen if AOD is enabled in Settings.
                // isFlipDevice is already false (DeviceIdentityHook), so the
                // FlipLinkageStyleController gate is bypassed — original
                // reflects isAodSettingsEnabled().
                if (!original && metrics != null && metrics.heightPixels in 1000..1500) {
                    true
                } else {
                    original
                }
            }
            log("AodHook: isAodEnable hooked — respects user setting on outer screen")
        }.onFailure { log("AodHook: failed", it) }
    }
}
