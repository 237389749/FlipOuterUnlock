package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Hook device identity detection to make the system treat the Mix Flip
 * as a regular phone. This closes gaps identified in the ref docs where
 * DeviceUtils and DeviceHelper implement independent tiny-screen detection
 * paths that were not covered by existing hooks.
 *
 * Detection paths (from ref docs):
 *   1. MiuiMultiDisplayTypeInfo.isFlipDevice()   → persist.sys.multi_display_type == 4
 *   2. MiuiConfigs.isFlipTinyScreen(Context)      → isFlipDevice() && maxDim/density <= 670
 *   3. MiuiConfigs.isTinyScreen(Context)          → maxDim/density <= 670
 *   4. MiuiConfigs.isFoldableDevice()             → IS_FOLD || isFlipDevice()
 *   5. DeviceUtils.isFlipTinyScreen(Context)      → isFlipDevice() && screenType == 1  [GAP]
 *   6. DeviceHelper.isTinyScreen(Context)          → detectType()==4 && screenType==1  [GAP]
 *
 * This hook targets all processes so device identity is spoofed everywhere.
 */
object DeviceIdentityHook : BaseHook() {
    override val targetPackages = listOf("*")

    override fun hook(param: PackageReadyParam) {
        safeHook("DeviceIdentityHook") {
            hookDeviceUtils(param)
            hookDeviceHelper(param)
            hookMiuiConfigsFoldable(param)
        }
    }

    // ── DeviceUtils.isFlipTinyScreen (miuix.jar, miuix.device) ──────────
    // Gap from ref docs: this is an independent detection path using
    // Configuration.getScreenType() == 1, not covered by MiuiConfigs hooks.
    private fun hookDeviceUtils(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.findClass("miuix.device.DeviceUtils")
            runCatching {
                val method = cls.method("isFlipTinyScreen", android.content.Context::class.java)
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked DeviceUtils.isFlipTinyScreen")
            }
            runCatching {
                val method = cls.method("isFlipDevice")
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked DeviceUtils.isFlipDevice")
            }
        }.onFailure { log("DeviceIdentity: DeviceUtils not found", it) }
    }

    // ── DeviceHelper.isTinyScreen (miuix.jar, miuix.os) ──────────────────
    // Gap from ref docs: for flip devices, uses screenType==1, not density.
    private fun hookDeviceHelper(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.findClass("miuix.os.DeviceHelper")
            runCatching {
                val method = cls.method("isTinyScreen", android.content.Context::class.java)
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked DeviceHelper.isTinyScreen")
            }
            runCatching {
                val method = cls.method("detectType", android.content.Context::class.java)
                // Return 1 = DEVICE_PHONE_TYPE
                hook(method, replaceResult(1))
                log("DeviceIdentity: forced DeviceHelper.detectType -> 1 (phone)")
            }
        }.onFailure { log("DeviceIdentity: DeviceHelper not found", it) }
    }

    // ── MiuiConfigs.isFoldableDevice (miui-framework.jar, miui.util) ────
    // Gap from ref docs: returns IS_FOLD || isFlipDevice()
    private fun hookMiuiConfigsFoldable(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.findClass("miui.util.MiuiConfigs")
            runCatching {
                val method = cls.method("isFoldableDevice")
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked MiuiConfigs.isFoldableDevice")
            }
            // Also hook isFlipTinyScreen and isTinyScreen (may already be
            // covered by TinyScreenHook, but defense-in-depth)
            runCatching {
                val method = cls.method("isFlipTinyScreen", android.content.Context::class.java)
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked MiuiConfigs.isFlipTinyScreen")
            }
            runCatching {
                val method = cls.method("isTinyScreen", android.content.Context::class.java)
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked MiuiConfigs.isTinyScreen")
            }
        }.onFailure { log("DeviceIdentity: MiuiConfigs not found", it) }
    }
}
