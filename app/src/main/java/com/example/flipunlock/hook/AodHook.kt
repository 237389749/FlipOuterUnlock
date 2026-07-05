package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Enable Always-On Display on the outer screen when folded.
 *
 * com.miui.aod.Utils.isAodEnable() normally redirects to
 * FlipLinkageStyleController.isUsingFlip() when the device is
 * folded. Hooking it to return true bypasses all flip-specific
 * gating and lets AOD display whenever the user has AOD enabled
 * in system settings.
 */
object AodHook : BaseHook() {
    override val targetPackages = listOf("com.miui.aod")

    override fun setupHooks(param: PackageReadyParam) {
        runCatching {
            val utilsClass = param.classLoader.loadClass("com.miui.aod.Utils")

            // Force AOD to think it's allowed
            val enableMethod = utilsClass.method("isAodEnable", android.content.Context::class.java)
            hook(enableMethod, replaceResult(true))
            log("AodHook: forced isAodEnable → true")

            // DeviceIdentityHook makes isFlipDevice()→false, which makes
            // isFolded()→false. AOD checks isFolded() to decide whether
            // to stay on — false means "phone is open, hide AOD".
            // Override to prevent the on/off flashing cycle.
            val foldedMethod = utilsClass.method("isFolded", android.content.Context::class.java)
            hook(foldedMethod, replaceResult(true))
            log("AodHook: forced isFolded → true")
        }.onFailure { log("AodHook: failed", it) }
    }
}
