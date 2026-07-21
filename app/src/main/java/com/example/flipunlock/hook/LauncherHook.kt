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
/**
 * Fix miuihome inner launcher behavior when running on the outer screen (state=6).
 *
 * Two issues:
 * 1. SpecialFDeviceGestureHelper detects physical fold → removes NavStubView.
 *    Fix: isInSFDeviceFoldedMode() → false.
 *
 * 2. NavStubView.startRecentsAnimationPre() returns immediately when
 *    mHideGestureLine=true (the normal full-screen-gesture state).
 *    In that mode, TouchInteractionService is supposed to handle gestures,
 *    but it doesn't work on flip outer screens. The gesture line style
 *    (hidden vs visible) is determined by system setting "hide_gesture_line".
 *
 *    Fix: hook setHideGestureLine() → always pass false so NavStubView
 *    processes gestures and startRecentsAnimationPre() can run.
 */
object LauncherHook : BaseHook() {
    override val targetPackages = listOf("com.miui.home")

    override fun setupHooks(param: PackageReadyParam) {
        log("LauncherHook: loading for ${param.packageName}")
        safeHook("LauncherHook") {
            hookSpecialFDeviceFoldedMode(param)
            hookHideGestureLine(param)
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

    /**
     * Hook NavStubView.setHideGestureLine(boolean) → always pass false.
     *
     * When hide_gesture_line=1 (default on flip), mHideGestureLine=true causes
     * startRecentsAnimationPre() to return immediately at line 3105:
     *   if (this.mHideGestureLine) { log error; return; }
     *
     * This blocks the recents animation setup, so home/recents gestures fail.
     * TouchInteractionService should handle them instead, but it doesn't work
     * on the flip outer screen.
     *
     * By forcing false, NavStubView handles gesture animations directly.
     */
    private fun hookHideGestureLine(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.home.recents.NavStubView")
            val method = cls.getDeclaredMethod("setHideGestureLine",
                Boolean::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method) { chain ->
                log("LauncherHook: setHideGestureLine → forcing false")
                chain.args[0] = false
                chain.proceed()
            }
            log("LauncherHook: setHideGestureLine → forced false")
        }.onFailure { log("LauncherHook: setHideGestureLine failed", it) }
    }
}
