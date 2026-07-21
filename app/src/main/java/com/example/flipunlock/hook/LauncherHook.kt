package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Fix miuihome inner launcher gesture navigation on the outer screen (state=6).
 *
 * NavStubView provides the bottom touch area (onComputeInternalInsets) that
 * intercepts touches at the bottom of the screen for Home/Recents gestures.
 * Back gestures are handled separately by fliphome's GestureStubView.
 *
 * Two issues prevent bottom gestures from working:
 *
 * 1. SpecialFDeviceGestureHelper.isInSFDeviceFoldedMode() → true
 *    Causes NavStubView to be removed. Fix: → false.
 *
 * 2. NavStubView.startRecentsAnimationPre() returns immediately when
 *    mHideGestureLine=true (line 3105). This is the normal state for
 *    full-screen gesture mode — TouchInteractionService should handle
 *    gestures instead. But TouchInteractionService doesn't work on the
 *    flip outer screen, leaving bottom gestures unhandled.
 *
 *    Fix: hook startRecentsAnimationPre() to force mHideGestureLine=false
 *    before the check, so NavStubView processes the recents transition.
 */
object LauncherHook : BaseHook() {
    override val targetPackages = listOf("com.miui.home")

    override fun setupHooks(param: PackageReadyParam) {
        log("LauncherHook: loading for ${param.packageName}")
        safeHook("LauncherHook") {
            hookSpecialFDeviceFoldedMode(param)
            hookStartRecentsAnimationPre(param)
        }
    }

    /**
     * Hook SpecialFDeviceGestureHelper.isInSFDeviceFoldedMode() → always false.
     */
    private fun hookSpecialFDeviceFoldedMode(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.home.recents.SpecialFDeviceGestureHelper")
            val method = cls.getDeclaredMethod("isInSFDeviceFoldedMode")
            method.isAccessible = true
            hook(method, replaceResult(false))
            log("LauncherHook: isInSFDeviceFoldedMode → false")
        }.onFailure { log("LauncherHook: isInSFDeviceFoldedMode failed", it) }
    }

    /**
     * Hook NavStubView.startRecentsAnimationPre() → force mHideGestureLine=false.
     *
     * Original code (line 3105-3108):
     *   if (this.mHideGestureLine) {
     *       Log.e(TAG, "startRecentsAnimationPre mHideGestureLine is true");
     *       return;  // ← BLOCKS the recents transition setup
     *   }
     *
     * This hook forces mHideGestureLine=false on the instance before
     * the original method runs, then restores it after. This is more
     * reliable than hooking setHideGestureLine() (which might have
     * timing issues or be called before the hook is installed).
     */
    private fun hookStartRecentsAnimationPre(param: PackageReadyParam) {
        runCatching {
            val navClass = param.classLoader.loadClass(
                "com.miui.home.recents.NavStubView")
            val method = navClass.getDeclaredMethod("startRecentsAnimationPre")
            method.isAccessible = true
            val hideField = navClass.getDeclaredField("mHideGestureLine")
            hideField.isAccessible = true

            hook(method) { chain ->
                val orig = hideField.getBoolean(chain.thisObject)
                if (orig) {
                    hideField.setBoolean(chain.thisObject, false)
                    log("LauncherHook: startRecentsAnimationPre — forced mHideGestureLine=false")
                }
                try {
                    chain.proceed()
                } finally {
                    if (orig) {
                        hideField.setBoolean(chain.thisObject, true)
                    }
                }
            }
            log("LauncherHook: hooked startRecentsAnimationPre")
        }.onFailure { log("LauncherHook: startRecentsAnimationPre failed", it) }
    }
}
