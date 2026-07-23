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

    private fun fLog(msg: String) {
        runCatching {
            java.io.FileWriter("/sdcard/flip_gesture.log", true).use { fw ->
                fw.append("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg\n")
            }
        }
        log(msg)
    }

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.gestureHome) { log("LauncherHook: DISABLED by persist.flipunlock.gesture.home"); return }
        fLog("LauncherHook: loading for ${param.packageName}")
        safeHook("LauncherHook") {
            hookSpecialFDeviceFoldedMode(param)
            hookStartRecentsAnimationPre(param)
            hookIsDefaultHome(param)
            hookDisableHomeRecents(param)
            hookWaitingCallback(param)
            hookGestureDiagnostics(param)
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
                if (orig) hideField.setBoolean(obj, false)
                val listener = listenerField.get(obj)
                var listenerState = -1
                if (listener != null) {
                    listenerState = runCatching { listener.javaClass.getDeclaredMethod("getState").invoke(listener) as? Int }.getOrNull() ?: -1
                    if (listenerState == 1) {
                        listenerField.set(obj, null)
                    }
                }
                fLog("startRecentsAnimationPre: hideLine=$orig listenerState=$listenerState")
                try { chain.proceed() }
                finally { if (orig) hideField.setBoolean(obj, true) }
            }
            fLog("LauncherHook: hooked startRecentsAnimationPre")
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
     * Hook getCurrentWindowMode → override mode=0, AND hook isWaitingCallback → false.
     * Two locks to break: mode=0 check + stuck GestureStateMachine.
     */
    private fun hookWaitingCallback(param: PackageReadyParam) {
        // 5a. GestureStateMachine.isWaitingCallback() → false
        runCatching {
            val smClass = param.classLoader.loadClass(
                "com.miui.home.recents.GestureStateMachine")
            val smMethod = smClass.getDeclaredMethod("isWaitingCallback")
            smMethod.isAccessible = true
            hook(smMethod, replaceResult(false))
            fLog("LauncherHook: GestureStateMachine.isWaitingCallback → false")
        }.onFailure { log("LauncherHook: isWaitingCallback failed", it) }

        // 5b. getCurrentWindowMode → override 0 to 2
        runCatching {
            val navClass = param.classLoader.loadClass(
                "com.miui.home.recents.NavStubView")
            val method = navClass.getDeclaredMethod("getCurrentWindowMode",
                android.view.MotionEvent::class.java,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!)
            method.isAccessible = true
            var callCount = 0
            hook(method) { chain ->
                val result = chain.proceed() as? Int ?: 0
                callCount++
                if (result == 0) {
                    if (callCount <= 5) fLog("getCurrentWindowMode → 0, overriding to 2 (#$callCount)")
                    2
                } else {
                    if (callCount <= 3) fLog("getCurrentWindowMode → $result (#$callCount)")
                    result
                }
            }
            fLog("LauncherHook: hooked getCurrentWindowMode")
        }.onFailure { log("LauncherHook: getCurrentWindowMode failed", it) }
    }

    /**
     * Diagnostic hooks to trace the full gesture pipeline from touch to action.
     *
     * Logs every decision point in onPointerEvent:
     * 1. Entry — did the touch reach NavStubView at all?
     * 2. initValueAndCheckRunningTaskValidity — does the task info exist?
     * 3. getCurrentWindowMode — what mode was determined?
     * 4. appTouchResolution — did we enter the app gesture handler?
     *
     * All output goes to /sdcard/flip_gesture.log for reliable capture.
     */
    private fun hookGestureDiagnostics(param: PackageReadyParam) {
        val navClass = runCatching {
            param.classLoader.loadClass("com.miui.home.recents.NavStubView")
        }.getOrNull() ?: return

        // Hook initValueAndCheckRunningTaskValidity — the SILENT KILLER
        // If this returns false, onPointerEvent returns immediately with NO log.
        runCatching {
            val method = navClass.getDeclaredMethod("initValueAndCheckRunningTaskValidity",
                android.view.MotionEvent::class.java)
            method.isAccessible = true
            hook(method) { chain ->
                val result = chain.proceed() as? Boolean ?: false
                if (!result) {
                    // Get runningTaskInfo state to understand why
                    val taskInfo = runCatching {
                        navClass.getDeclaredMethod("getRunningTaskForGesture")
                            .apply { isAccessible = true }
                            .invoke(chain.thisObject)
                    }.getOrNull()
                    val baseActivity = runCatching {
                        taskInfo?.javaClass?.getField("baseActivity")?.get(taskInfo)
                    }.getOrNull()
                    fLog("DIAG: initValueAndCheckRunningTaskValidity → FALSE (taskInfo=$taskInfo baseActivity=$baseActivity)")
                }
                result
            }
            fLog("DIAG: hooked initValueAndCheckRunningTaskValidity")
        }.onFailure { log("DIAG: initValueAndCheckRunningTaskValidity failed", it) }

        // Hook checkRunningTaskValidity — logs what it sees
        runCatching {
            val method = navClass.getDeclaredMethod("checkRunningTaskValidity")
            method.isAccessible = true
            hook(method) { chain ->
                val result = chain.proceed() as? Boolean ?: false
                if (!result) {
                    val taskInfo = runCatching {
                        navClass.getDeclaredMethod("getRunningTaskForGesture")
                            .apply { isAccessible = true }
                            .invoke(chain.thisObject)
                    }.getOrNull()
                    fLog("DIAG: checkRunningTaskValidity → FALSE (taskInfo=$taskInfo)")
                }
                result
            }
            fLog("DIAG: hooked checkRunningTaskValidity")
        }.onFailure { log("DIAG: checkRunningTaskValidity failed", it) }

        // Hook onComputeInternalInsets — is touch region empty?
        runCatching {
            // Find the OnComputeInternalInsetsListenerCompat anonymous class is tricky.
            // Instead, hook shouldUseEmptyTouchableRegion which controls the region.
            val method = navClass.getDeclaredMethod("shouldUseEmptyTouchableRegion")
            method.isAccessible = true
            var lastResult = true
            hook(method) { chain ->
                val result = chain.proceed() as? Boolean ?: true
                if (result != lastResult) {
                    lastResult = result
                    val disableHome = runCatching {
                        navClass.getDeclaredField("mDisableHomeRecents")
                            .apply { isAccessible = true }
                            .getBoolean(chain.thisObject)
                    }.getOrNull()
                    fLog("DIAG: shouldUseEmptyTouchableRegion → $result (mDisableHomeRecents=$disableHome)")
                }
                result
            }
            fLog("DIAG: hooked shouldUseEmptyTouchableRegion")
        }.onFailure { log("DIAG: shouldUseEmptyTouchableRegion failed", it) }

        // onSystemUiFlagsChanged is already hooked by hookDisableHomeRecents (Gate 4).
        // Don't double-hook — the existing hook forces mDisableHomeRecents=false.
    }
}
