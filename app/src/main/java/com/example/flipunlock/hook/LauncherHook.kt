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
            hookDiagnostic(param)
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

    /**
     * DIAGNOSTIC: hook onComputeInternalInsets → log region + force it active.
     * Also hook onPointerEvent → log if touches arrive.
     * Remove after confirming touch routing.
     */
    private fun hookDiagnostic(param: PackageReadyParam) {
        runCatching {
            val navClass = param.classLoader.loadClass("com.miui.home.recents.NavStubView")

            // Find the TouchableRegionCompat.OnComputeInternalInsetsListener implementation
            // inside NavStubView — the anonymous class at line ~1125-1145.
            // Hook each method differently: find the field that holds the listener.
            val fields = navClass.declaredFields
            var listenerField: java.lang.reflect.Field? = null
            var listenerObj: Any? = null
            for (f in fields) {
                f.isAccessible = true
                val v = f.get(null) // static field
                if (v != null && v.javaClass.name.contains("OnComputeInternalInsetsListener")) {
                    listenerField = f
                    listenerObj = v
                    break
                }
            }
            // Try instance field approach
            if (listenerObj == null) {
                for (f in fields) {
                    if (f.name.contains("Insets") || f.name.contains("Touchable") || f.name.contains("touchable")) {
                        log("DIAG: NavStubView field ${f.name} type=${f.type.name}")
                    }
                }
            }

            // Simpler: just hook the helper method that sets the region.
            // The actual onComputeInternalInsets sets region via the InsetsListener.
            // Hook shouldUseEmptyTouchableRegion to log and force false.
            val shouldUseMethod = navClass.getDeclaredMethod("shouldUseEmptyTouchableRegion")
            shouldUseMethod.isAccessible = true
            hook(shouldUseMethod) { chain ->
                val result = chain.proceed() as? Boolean ?: false
                log("DIAG: shouldUseEmptyTouchableRegion → $result (keepHidden=${chain.thisObject.getField("mKeepHidden")} disableTouch=${chain.thisObject.getField("mDisableTouch")} disableHomeRecents=${chain.thisObject.getField("mDisableHomeRecents")})")
                false // force never empty
            }
            log("LauncherHook: DIAG hooked shouldUseEmptyTouchableRegion")

            // Also hook onTouchEvent to see if View-level touches arrive
            runCatching {
                val touchMethod = navClass.getDeclaredMethod("onTouchEvent",
                    android.view.MotionEvent::class.java)
                touchMethod.isAccessible = true
                hook(touchMethod, before { chain ->
                    val ev = chain.args[0] as? android.view.MotionEvent
                    val action = ev?.actionMasked ?: -1
                    if (action == 0 || action == 1) {
                        log("DIAG: onTouchEvent action=$action rawX=${ev?.rawX} rawY=${ev?.rawY}")
                    }
                })
                log("LauncherHook: DIAG hooked onTouchEvent")
            }.onFailure { log("LauncherHook: DIAG onTouchEvent failed", it) }
        }.onFailure { log("LauncherHook: DIAG failed", it) }
    }
}
