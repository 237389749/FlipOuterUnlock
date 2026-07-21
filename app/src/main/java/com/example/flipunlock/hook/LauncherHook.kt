package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Fix miuihome inner launcher gesture navigation on the outer screen (state=6).
 *
 * Three issues prevent bottom gestures (Home/Recents) from working:
 *
 * 1. SpecialFDeviceGestureHelper detects physical fold → removes NavStubView.
 *    Fix: isInSFDeviceFoldedMode() → false.
 *
 * 2. NavStubView.startRecentsAnimationPre() returns when mHideGestureLine=true.
 *    Fix: force mHideGestureLine=false before the check.
 *
 * 3. mIsUseMiuiHomeAsDefaultHome=false because com.miui.fliphome is the flip's
 *    default home (we disabled FlipLauncher, making miuihome the actual launcher
 *    but NOT the system's default home app).
 *    When false: NavStubView is removed onExpand, NavStubView is not created
 *    in addFsgGestureWindow, isUseLauncherRecentsAndFsGesture→false, and
 *    NavStubGestureEventManager blocks actions with "third home mode".
 *    Fix: getIsUseMiuiHomeAsDefaultHome() → true.
 */
object LauncherHook : BaseHook() {
    override val targetPackages = listOf("com.miui.home")

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.gestureHome) { log("LauncherHook: DISABLED by persist.flipunlock.gesture.home"); return }
        log("LauncherHook: loading for ${param.packageName}")
        safeHook("LauncherHook") {
            hookSpecialFDeviceFoldedMode(param)
            hookStartRecentsAnimationPre(param)
            hookIsDefaultHome(param)
            hookDisableHomeRecents(param)
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
            val listenerField = navClass.getDeclaredField("mRecentsAnimationListenerImpl")
            listenerField.isAccessible = true

            hook(method) { chain ->
                val obj = chain.thisObject
                val orig = hideField.getBoolean(obj)
                if (orig) {
                    hideField.setBoolean(obj, false)
                }
                // Clear stuck recents animation listener (state=1 blocks subsequent gestures)
                val listener = listenerField.get(obj)
                if (listener != null) {
                    val state = runCatching { listener.javaClass.getDeclaredMethod("getState").invoke(listener) as? Int }.getOrNull()
                    if (state == 1) {
                        listenerField.set(obj, null)
                        log("LauncherHook: startRecentsAnimationPre — cleared stuck listener (state=$state)")
                    }
                }
                try {
                    chain.proceed()
                } finally {
                    if (orig) {
                        hideField.setBoolean(obj, true)
                    }
                }
            }
            log("LauncherHook: hooked startRecentsAnimationPre")
        }.onFailure { log("LauncherHook: startRecentsAnimationPre failed", it) }
    }

    /**
     * Hook BaseRecentsImpl.getIsUseMiuiHomeAsDefaultHome() → true.
     *
     * This is the ROOT GATE for gesture functionality in miuihome:
     *
     * Line 454: onExpand → removeNavStubView() if !mIsUseMiuiHomeAsDefaultHome
     * Line 629: addFsgGestureWindow → skip createAndAddNavStubView if false
     * Line 637: isUseLauncherRecentsAndFsGesture() returns this value
     *
     * By forcing true, miuihome's gesture system fully trusts itself to
     * handle Home/Recents transitions regardless of system default home setting.
     */
    private fun hookIsDefaultHome(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.home.recents.BaseRecentsImpl")
            val method = cls.getDeclaredMethod("getIsUseMiuiHomeAsDefaultHome")
            method.isAccessible = true
            hook(method, replaceResult(true))
            log("LauncherHook: getIsUseMiuiHomeAsDefaultHome → true")
        }.onFailure { log("LauncherHook: getIsUseMiuiHomeAsDefaultHome failed", it) }
    }

    /**
     * Hook NavStubView.onSystemUiFlagsChanged() → force mDisableHomeRecents=false.
     *
     * When switching from launcher to an app, SystemUI sends flags that may
     * set mDisableHomeRecents=true (line 1516-1520):
     *   boolean z3 = isHomeDisabled() && isOverviewDisabled();
     *   this.mDisableHomeRecents = z3;
     *   this.mUseEmptyTouchableRegion = shouldUseEmptyTouchableRegion();
     *
     * This makes the touch region empty → bottom gestures stop working in apps.
     * Force both fields false after the original method runs.
     */
    private fun hookDisableHomeRecents(param: PackageReadyParam) {
        runCatching {
            val navClass = param.classLoader.loadClass(
                "com.miui.home.recents.NavStubView")
            val method = navClass.getDeclaredMethod("onSystemUiFlagsChanged",
                Long::class.javaPrimitiveType!!)
            method.isAccessible = true
            val disableField = navClass.getDeclaredField("mDisableHomeRecents")
            disableField.isAccessible = true
            val emptyField = navClass.getDeclaredField("mUseEmptyTouchableRegion")
            emptyField.isAccessible = true

            hook(method, after { chain, result ->
                val obj = chain.thisObject
                if (disableField.getBoolean(obj)) {
                    disableField.setBoolean(obj, false)
                    emptyField.setBoolean(obj, false)
                    log("LauncherHook: onSystemUiFlagsChanged → forced mDisableHomeRecents=false")
                }
                result
            })
            log("LauncherHook: hooked onSystemUiFlagsChanged (disableHomeRecents guard)")
        }.onFailure { log("LauncherHook: onSystemUiFlagsChanged failed", it) }
    }

}
