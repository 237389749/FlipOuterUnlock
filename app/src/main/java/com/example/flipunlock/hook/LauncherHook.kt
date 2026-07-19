package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Fix miuihome inner launcher behavior when running on the outer screen (state=6).
 *
 * The inner screen launcher (com.miui.home) has its own flip-detection system
 * separate from the framework's isFlipDevice(). It uses MD5 hashes of Build.DEVICE
 * to detect "Special F" devices (flip phones), and monitors physical fold state
 * via DeviceStateManager to remove/recreate the gesture navigation bar (NavStubView).
 *
 * When the device is physically folded AND identified as a SpecialFDevice:
 *   SpecialFDeviceGestureHelper.isInSFDeviceFoldedMode() → true
 *     → BaseRecentsImpl.createAndAddNavStubView() → returns immediately
 *     → BaseRecentsImpl.showNavStubView() → returns immediately
 *     → BaseRecentsImpl.addBackStubWindow() → returns immediately
 *     → NavStubView (gesture pill) is REMOVED
 *
 * With state=6 (DUAL), the inner launcher runs on the outer screen. We need
 * the gesture navigation bar to be present. Block the fold state check so
 * NavStubView is always created regardless of physical fold state.
 */
object LauncherHook : BaseHook() {
    override val targetPackages = listOf("com.miui.home")

    override fun setupHooks(param: PackageReadyParam) {
        log("LauncherHook: loading for ${param.packageName}")
        safeHook("LauncherHook") {
            hookSpecialFDeviceFoldedMode(param)
        }
    }

    /**
     * Hook SpecialFDeviceGestureHelper.isInSFDeviceFoldedMode() → always false.
     *
     * This is the single choke point used in 7 places in BaseRecentsImpl
     * to decide whether to allow the gesture navigation bar (NavStubView)
     * and back stub windows to be created/shown.
     *
     * By forcing false, the launcher always creates its NavStubView regardless
     * of physical fold state. SystemUI's NavigationBar and the launcher's
     * NavStubView can then coexist normally.
     */
    private fun hookSpecialFDeviceFoldedMode(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.home.recents.SpecialFDeviceGestureHelper"
            )
            val method = cls.getDeclaredMethod("isInSFDeviceFoldedMode")
            method.isAccessible = true
            hook(method, replaceResult(false))
            log("LauncherHook: SpecialFDeviceGestureHelper.isInSFDeviceFoldedMode → false")
        }.onFailure { log("LauncherHook: SpecialFDeviceGestureHelper failed", it) }
    }
}
