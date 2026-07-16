package com.example.flipunlock.hook

import android.content.res.Configuration
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Hook Configuration.getScreenType() to always return 0 (SCREEN_TYPE_EXPAND).
 *
 * From point.txt analysis:
 * - MIUI injects a custom getScreenType() into Configuration
 * - SCREEN_TYPE_FOLD = 1  identifies the external/outer screen
 * - SCREEN_TYPE_EXPAND = 0 identifies the inner/normal screen
 *
 * This is more precise than isFlipDevice → false because:
 * - It only affects "which screen am I on?" checks
 * - Does NOT affect fold sensors, camera posture, or other flip hardware
 * - Used by: shouldShowCurrentInput, DeviceUtils.isFlipTinyScreen,
 *   DeviceHelper.isTinyScreen, and many layout decisions
 *
 * By forcing return 0 everywhere, ALL code paths that check screen type
 * will behave as if they're on the inner/normal screen.
 *
 * Targets all processes since Configuration is a framework class.
 */
object ScreenTypeHook : BaseHook() {
    override val targetPackages = listOf("*")

    override fun hook(param: PackageReadyParam) {
        log("ScreenTypeHook: loading for ${param.packageName}")
        safeHook("ScreenTypeHook") {
            runCatching {
                // Hook the MIUI-injected getScreenType() on Configuration
                val method = Configuration::class.java.method("getScreenType")
                hook(method, replaceResult(0))
                log("ScreenType: forced Configuration.getScreenType -> 0 (EXPAND)")
            }.onFailure { log("ScreenType: failed hook Configuration.getScreenType", it) }
        }
    }
}
