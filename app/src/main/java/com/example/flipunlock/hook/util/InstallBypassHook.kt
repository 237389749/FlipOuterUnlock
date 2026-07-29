package com.example.flipunlock.hook.util

import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Bypass persistent-app install restriction for com.android.systemui.
 *
 * Controlled by: persist.flipunlock.install.bypass (default: false)
 * Enable → reboot → adb install MiuiSystemUI.apk → disable → reboot
 *
 * Hooks PackageSetting.isPersistent() in system_server to return false
 * for the target package, allowing PackageInstallerSession to proceed
 * with the update instead of throwing INSTALL_FAILED_INVALID_APK.
 */
object InstallBypassHook {

    private const val PROP = "persist.flipunlock.install.bypass"
    private val TARGET_PACKAGES = setOf(
        "com.android.systemui",
    )

    fun hook(param: SystemServerStartingParam) {
        val enabled = try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType!!)
                .invoke(null, PROP, false) as? Boolean ?: false
        } catch (_: Exception) { false }
        if (!enabled) {
            log("InstallBypass: disabled (set $PROP=true to enable)")
            return
        }
        log("InstallBypass: ACTIVE — persistent check bypassed for $TARGET_PACKAGES")

        safeHook("InstallBypass") {
            val psClass = param.classLoader.loadClass("com.android.server.pm.PackageSetting")
            val isPersistent = psClass.getDeclaredMethod("isPersistent")
            isPersistent.isAccessible = true

            hook(isPersistent) { chain ->
                val pkg = runCatching {
                    (chain.thisObject as Any).callMethod("getPackageName") as? String
                }.getOrNull()

                if (pkg != null && pkg in TARGET_PACKAGES) {
                    log("InstallBypass: isPersistent→false for $pkg")
                    false
                } else {
                    chain.proceed()
                }
            }
            log("InstallBypass: ✓ PackageSetting.isPersistent hooked")
        }

        // Also hook PackageManagerInternal.isPackagePersistent as a fallback
        safeHook("InstallBypass-internal") {
            val pmsClass = param.classLoader.loadClass(
                "com.android.server.pm.PackageManagerService\$PackageManagerInternalImpl"
            )
            val method = pmsClass.getDeclaredMethod("isPackagePersistent", String::class.java)
            method.isAccessible = true

            hook(method) { chain ->
                val pkg = chain.args[0] as? String
                if (pkg != null && pkg in TARGET_PACKAGES) {
                    log("InstallBypass: isPackagePersistent→false for $pkg")
                    false
                } else {
                    chain.proceed()
                }
            }
            log("InstallBypass: ✓ PackageManagerInternal.isPackagePersistent hooked")
        }
    }
}
