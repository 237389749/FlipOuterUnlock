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
            val method = utilsClass.method("isAodEnable", android.content.Context::class.java)
            hook(method, replaceResult(true))
            log("AodHook: forced isAodEnable → true")
        }.onFailure { log("AodHook: failed", it) }
    }
}
