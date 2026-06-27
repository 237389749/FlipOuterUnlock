package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Hook all device identity detection paths to make the system treat the
 * Mix Flip as a regular phone. Merges the original TinyScreenHook.java
 * (previously unported) + gap analysis from refMD/cleaned docs.
 *
 * Full detection chain (from ref docs):
 *   MiuiMultiDisplayTypeInfo.isFlipDevice()  ← ROOT (persist.sys.multi_display_type == 4)
 *     ├── MiuiConfigs.isFlipTinyScreen()     ← isFlipDevice() && density <= 670
 *     ├── MiuiConfigs.isTinyScreen()         ← density <= 670
 *     ├── MiuiConfigs.isFoldableDevice()     ← IS_FOLD || isFlipDevice()
 *     ├── DeviceUtils.isFlipTinyScreen()     ← isFlipDevice() && screenType == 1
 *     ├── DeviceHelper.isTinyScreen()        ← detectType()==4 && screenType == 1
 *     ├── miui.os.Build.isFlipDevice()       ← delegates to MiuiMultiDisplayTypeInfo
 *     └── miuix.os.Build.IS_FLIP             ← static field from same property
 *
 * Targeting all processes so spoofing works everywhere.
 */
object DeviceIdentityHook : BaseHook() {
    override val targetPackages = listOf("*")

    override fun hook(param: PackageReadyParam) {
        safeHook("DeviceIdentityHook") {
            hookRootDeviceType(param)       // MiuiMultiDisplayTypeInfo
            hookMiuiBuild(param)            // miui.os.Build
            hookMiuixBuildStatic(param)     // miuix.os.Build.IS_FLIP
            hookDeviceUtils(param)          // miuix.device.DeviceUtils
            hookDeviceHelper(param)         // miuix.os.DeviceHelper
            hookMiuiConfigs(param)          // miui.util.MiuiConfigs
        }
    }

    // ── ROOT: MiuiMultiDisplayTypeInfo.isFlipDevice() ────────────────────
    // The single source of truth. Returns true when persist.sys.multi_display_type == 4.
    // If we block this, all downstream detection paths that delegate to it
    // (miui.os.Build, DeviceUtils) also return false.
    private fun hookRootDeviceType(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("miui.util.MiuiMultiDisplayTypeInfo")
            runCatching {
                val method = cls.method("isFlipDevice")
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked MiuiMultiDisplayTypeInfo.isFlipDevice")
            }
            // Also hook isFoldDevice to prevent fold-specific behaviors
            runCatching {
                val method = cls.method("isFoldDevice")
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked MiuiMultiDisplayTypeInfo.isFoldDevice")
            }
        }.onFailure { log("DeviceIdentity: MiuiMultiDisplayTypeInfo not found", it) }
    }

    // ── miui.os.Build.isFlipDevice() ─────────────────────────────────────
    // From original TinyScreenHook.java: delegates to MiuiMultiDisplayTypeInfo
    private fun hookMiuiBuild(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("miui.os.Build")
            runCatching {
                val method = cls.method("isFlipDevice")
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked miui.os.Build.isFlipDevice")
            }
        }.onFailure { log("DeviceIdentity: miui.os.Build not found", it) }
    }

    // ── miuix.os.Build.IS_FLIP (static final field) ──────────────────────
    // From original TinyScreenHook.java: set via reflection.
    // This is a static final field initialized from persist.sys.multi_display_type.
    // Not a compile-time constant, so reflection set will work.
    @Suppress("BanDiscouragedJavaApi")
    private fun hookMiuixBuildStatic(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("miuix.os.Build")
            val field = cls.field("IS_FLIP")
            field.isAccessible = true
            // Remove final modifier via reflection on Field.modifiers
            runCatching {
                val modifiersField = java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
                modifiersField.isAccessible = true
                modifiersField.setInt(field, field.modifiers and 0xFFFFFFEF.toInt()) // clear ACC_FINAL
            }
            field.setBoolean(null, false)
            log("DeviceIdentity: set miuix.os.Build.IS_FLIP = false")
        }.onFailure { log("DeviceIdentity: miuix.os.Build not found", it) }
    }

    // ── DeviceUtils.isFlipTinyScreen / isFlipDevice (miuix.device) ─────
    private fun hookDeviceUtils(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("miuix.device.DeviceUtils")
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

    // ── DeviceHelper.isTinyScreen / detectType (miuix.os) ─────────────────
    private fun hookDeviceHelper(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("miuix.os.DeviceHelper")
            runCatching {
                val method = cls.method("isTinyScreen", android.content.Context::class.java)
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked DeviceHelper.isTinyScreen")
            }
            runCatching {
                val method = cls.method("detectType", android.content.Context::class.java)
                hook(method, replaceResult(1)) // 1 = DEVICE_PHONE_TYPE
                log("DeviceIdentity: forced DeviceHelper.detectType -> 1")
            }
        }.onFailure { log("DeviceIdentity: DeviceHelper not found", it) }
    }

    // ── MiuiConfigs: isFoldableDevice, isFlipTinyScreen, isTinyScreen ───
    private fun hookMiuiConfigs(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("miui.util.MiuiConfigs")
            runCatching {
                val method = cls.method("isFoldableDevice")
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked MiuiConfigs.isFoldableDevice")
            }
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
