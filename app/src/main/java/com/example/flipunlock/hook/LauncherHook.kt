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
            hookBypassRecentsAnimation(param)
            hookRecentsDisplayFilter(param)
            hookAnotherDisplay(param)
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
     * Gate 6: Bypass broken recents animation — go home or recents directly.
     *
     * The entire gesture animation pipeline is broken on the flip outer screen:
     *
     * 1. AppWaitToDragState.enter() → mIsWaitingCallback=true, waits for
     *    Shell/WindowTransition animation callback (msg 11) — NEVER arrives.
     * 2. processMessage stores mMsgUpType on ACTION_UP, waits for msg 11.
     * 3. After 800ms timeout, finishRecentsActivityDirectly() only resets
     *    state — NO home/recents action.
     * 4. Even if msg 11 → onRecentsAnimationStart() → performAppToHome() →
     *    finishAppToHome() → finish() → finishController() — also hangs on
     *    the broken Shell transition.
     * 5. showRecents() checks isNeedStopBecauseRecentsRemoteAnimStartFailed()
     *    at the top → true → returns immediately, never shows recents view.
     *
     * FIX: Three-pronged bypass:
     *   a) isNeedStopBecauseRecentsRemoteAnimStartFailed() → false
     *      Allows showRecents() to run and desktop recents to display.
     *   b) Hook performAppToHome() → redirect to checkAndLauncherHome()
     *      Skips the finishController() hang.
     *   c) Hook AppWaitToDragState.processMessage() — on ACTION_UP,
     *      check drag distance (isSafeArea) to decide home vs recents,
     *      then call the appropriate method directly.
     */
    private fun hookBypassRecentsAnimation(param: PackageReadyParam) {
        val navClass = param.classLoader.loadClass(
            "com.miui.home.recents.NavStubView")

        // 6a. isNeedStopBecauseRecentsRemoteAnimStartFailed() → false
        //     Unblocks showRecents(), startAppToHomeAnim(), and other methods
        //     that bail out when they think the remote animation failed.
        runCatching {
            val method = navClass.getDeclaredMethod(
                "isNeedStopBecauseRecentsRemoteAnimStartFailed")
            method.isAccessible = true
            hook(method, replaceResult(false))
            fLog("LauncherHook: Gate 6a — isNeedStopBecauseRecentsRemoteAnimStartFailed → false")
        }.onFailure { fLog("LauncherHook: Gate 6a failed ${it.message}") }

        // 6b. Hook performAppToHome → go home directly, skip broken animation.
        //     Also restore haptic feedback (vibration) that the original method
        //     provides at line 3773 — our bypass was silently dropping it.
        runCatching {
            val method = navClass.getDeclaredMethod("performAppToHome")
            method.isAccessible = true
            hook(method) { chain ->
                fLog("Gate6b: performAppToHome → direct home + haptic")
                val nav = chain.thisObject
                // Trigger haptic feedback — original performAppToHome calls
                // HapticFeedbackCompat.getInstance().performHomeGestureAccessibilitySwitch(this)
                runCatching {
                    val hapticClass = param.classLoader.loadClass(
                        "com.miui.home.common.hapticfeedback.HapticFeedbackCompat")
                    val instance = hapticClass.getDeclaredMethod("getInstance").invoke(null)
                    hapticClass.getDeclaredMethod("performHomeGestureAccessibilitySwitch",
                        android.view.View::class.java).invoke(instance, nav)
                }
                // Go home
                runCatching {
                    navClass.getDeclaredMethod("checkAndLauncherHome")
                        .apply { isAccessible = true }.invoke(nav)
                }
                null  // Skip original — don't go through finishController
            }
            fLog("LauncherHook: Gate 6b — performAppToHome bypass installed")
        }.onFailure { fLog("LauncherHook: Gate 6b failed ${it.message}") }

        // 6c. Hook AppWaitToDragState.processMessage() — go home or recents on ACTION_UP.
        runCatching {
            val smClass = param.classLoader.loadClass(
                "com.miui.home.recents.GestureStateMachine")
            val appWaitClass = smClass.declaredClasses.firstOrNull {
                it.simpleName == "AppWaitToDragState"
            } ?: return
            val processMethod = appWaitClass.getDeclaredMethod("processMessage",
                android.os.Message::class.java)
            processMethod.isAccessible = true
            val navField = smClass.getDeclaredField("mNavStubView")
            navField.isAccessible = true

            hook(processMethod) { chain ->
                val msg = chain.args[0] as? android.os.Message
                val msgWhat = msg?.what ?: 0
                val result = chain.proceed()  // Let original store mMsgUpType
                if (msgWhat in 4..10) {
                    val sm = runCatching {
                        appWaitClass.getDeclaredField("this\$0")
                            .apply { isAccessible = true }.get(chain.thisObject)
                    }.getOrNull() ?: return@hook result
                    val nav = navField.get(sm) ?: return@hook result

                    // Check drag progress to decide home vs recents.
                    // mCalculator.getPer() returns 0.0–1.0 drag completion.
                    val dragPer = runCatching {
                        val calc = navClass.getDeclaredField("mCalculator")
                            .apply { isAccessible = true }.get(nav)
                        calc?.javaClass?.getDeclaredMethod("getPer")
                            ?.apply { isAccessible = true }?.invoke(calc) as? Float
                    }.getOrNull() ?: 0f

                    // Trigger haptic feedback — normally done by performAppToHome()
                    // at line 3773, which our bypass skips. Do it here instead.
                    triggerHaptic(param, nav)

                    fLog("Gate6c: UP msg $msgWhat dragPer=${"%.2f".format(dragPer)}")
                    if (dragPer > 0.5f) {
                        // Long drag → recents. Strategy: go home first (works),
                        // then after launcher is visible, transition to OVERVIEW.
                        // This avoids the broken showRecents() executor.
                        runCatching {
                            navClass.getDeclaredMethod("checkAndLauncherHome")
                                .apply { isAccessible = true }.invoke(nav)
                        }
                        // Post delayed: wait for launcher foreground.
                        // Phase 1 (300ms): reload task list from system
                        // Phase 2 (800ms): transition to OVERVIEW after tasks arrive
                        val ctx = (nav as android.view.View).context
                        val rmClass = param.classLoader.loadClass(
                            "com.miui.home.recents.RecentsModel")
                        val rmInstance = rmClass.getDeclaredMethod("getInstance",
                            android.content.Context::class.java)
                            .invoke(null, ctx)

                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            // Phase 1: reload task list
                            runCatching {
                                rmClass.getDeclaredMethod("notifyRecentTasksChanged")
                                    .apply { isAccessible = true }.invoke(rmInstance)
                                fLog("Gate6c: notifyRecentTasksChanged → reloaded")
                            }
                        }, 300L)

                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            // Phase 2: check task count + transition to OVERVIEW
                            val taskCount = runCatching {
                                val taskList = rmClass.getDeclaredMethod("getTaskList",
                                    Boolean::class.javaPrimitiveType!!)
                                    .invoke(rmInstance, false) as? java.util.List<*>
                                taskList?.size ?: -1
                            }.getOrNull() ?: -1
                            fLog("Gate6c: taskList size=$taskCount before goToState")
                            runCatching {
                                val launcher = navClass.getDeclaredField("mLauncher")
                                    .apply { isAccessible = true }.get(nav)
                                if (launcher != null) {
                                    val sm = launcher.javaClass.getMethod("getStateManager").invoke(launcher)
                                    val lsClass = param.classLoader
                                        .loadClass("com.miui.home.launcher.LauncherState")
                                    val overview = lsClass.getDeclaredField("OVERVIEW").get(null)
                                    sm.javaClass.methods.firstOrNull { m ->
                                        m.name == "goToState" && m.parameterCount == 2 &&
                                        m.parameterTypes[1] == Boolean::class.javaPrimitiveType
                                    }?.invoke(sm, overview, false)
                                }
                            }
                        }, 800L)
                        fLog("Gate6c: → home+recents (delayed goToState)")
                    } else {
                        runCatching {
                            navClass.getDeclaredMethod("checkAndLauncherHome")
                                .apply { isAccessible = true }.invoke(nav)
                        }
                        fLog("Gate6c: → checkAndLauncherHome")
                    }
                }
                result
            }
            fLog("LauncherHook: Gate 6c — home/recents dispatch installed")
        }.onFailure { fLog("LauncherHook: Gate 6c failed ${it.message}") }
    }

    /**
     * Gate 8: Force isAnotherDisplay() → false.
     *
     * BaseRecentsImpl.activityResumed(Intent) calls isAnotherDisplay(intent)
     * at line 176. If it returns true, the entire activity-resume callback is
     * skipped: gesture window visibility NOT updated, back-gesture-break
     * controller NOT reset, touch-by-swipe-status-bar NOT disabled.
     *
     * The check compares mContext.getDisplay().getDisplayId() against
     * intent.getIntExtra("app_dc_displayid", ...). On the flip outer screen
     * with state=6 (DUAL, outer=display 0), this mismatch can occur if the
     * intent carries a different display ID from the app's original display.
     *
     * Fix: force false — always process activity resume events.
     */
    private fun hookAnotherDisplay(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.home.recents.BaseRecentsImpl")
            val method = cls.getDeclaredMethod("isAnotherDisplay",
                android.content.Intent::class.java)
            method.isAccessible = true
            hook(method, replaceResult(false))
            fLog("LauncherHook: Gate 8 — isAnotherDisplay → false")
        }.onFailure { fLog("LauncherHook: Gate 8 failed ${it.message}") }
    }

    /**
     * Gate 7: Fix empty recents — disable display-based task filtering.
     *
     * RecentsModel.removeOtherDisplayTask() removes all tasks whose display ID
     * doesn't match this.mDisplay.getDisplayId(). On the flip outer screen,
     * mDisplay (from getDefaultDisplay()) may return a different display ID
     * than the tasks (which run on the outer screen). This causes ALL tasks
     * to be filtered out, resulting in an empty recents view.
     *
     * Fix: hook removeOtherDisplayTask → no-op. Allow all tasks regardless
     * of display ID. There's only one active display in state=6 anyway.
     */
    private fun hookRecentsDisplayFilter(param: PackageReadyParam) {
        fLog("LauncherHook: Gate 7 — entering hookRecentsDisplayFilter")
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.home.recents.RecentsModel")
            fLog("LauncherHook: Gate 7 — RecentsModel loaded")

            // Gate 7: removeOtherDisplayTask → no-op
            val method = cls.getDeclaredMethod("removeOtherDisplayTask",
                java.util.List::class.java)
            method.isAccessible = true
            hook(method) {
                // No-op: don't filter tasks by display ID
                null
            }
            fLog("LauncherHook: Gate 7 — removeOtherDisplayTask → no-op")

            // Diagnostic: hook ActivityManagerWrapper.getRecentTasks to see
            // what the system actually returns
            runCatching {
                val amwClass = param.classLoader.loadClass(
                    "com.android.systemui.shared.recents.system.ActivityManagerWrapper")
                val getRT = amwClass.getDeclaredMethod("getRecentTasks",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!)
                getRT.isAccessible = true
                var callCount = 0
                hook(getRT) { chain ->
                    callCount++
                    val result = chain.proceed()
                    val size = (result as? java.util.ArrayList<*>)?.size ?: -1
                    if (callCount <= 10) {
                        fLog("DIAG: AMW.getRecentTasks(flags=${chain.args[1]}, userId=${chain.args[2]}) → $size tasks")
                    }
                    result
                }
            }

            // Diagnostic: check task list size when recents view queries it
            runCatching {
                val getTaskList = cls.getDeclaredMethod("getTaskList",
                    Boolean::class.javaPrimitiveType!!)
                getTaskList.isAccessible = true
                var lastLoggedCount = -1
                hook(getTaskList) { chain ->
                    val result = chain.proceed()
                    val tasks = result as? java.util.List<*>
                    val count = tasks?.size ?: -1
                    if (count != lastLoggedCount) {
                        lastLoggedCount = count
                        fLog("DIAG: RecentsModel.getTaskList → $count tasks ${if (count == 0) "★ EMPTY" else ""}")
                    }
                    result
                }
            }

            // Diagnostic: log task list size when recents is queried
            runCatching {
                val getTaskList = cls.getDeclaredMethod("getTaskList",
                    Boolean::class.javaPrimitiveType!!)
                getTaskList.isAccessible = true
                var lastLoggedCount = -1
                hook(getTaskList) { chain ->
                    val result = chain.proceed()
                    val count = (result as? java.util.List<*>)?.size ?: -1
                    if (count != lastLoggedCount) {
                        lastLoggedCount = count
                        fLog("DIAG: RecentsModel.getTaskList → $count tasks")
                    }
                    result
                }
            }
        }.onFailure { fLog("LauncherHook: Gate 7 failed ${it.message}") }
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

    /**
     * Trigger haptic feedback for bottom gesture completion.
     *
     * Original path: performAppToHome() line 3773 calls:
     *   HapticFeedbackCompat.getInstance().performHomeGestureAccessibilitySwitch(this)
     *
     * Our Gate 6b/6c bypass skips performAppToHome(), so we call haptic
     * directly before checkAndLauncherHome() or showRecents().
     */
    private fun triggerHaptic(param: PackageReadyParam, nav: Any) {
        runCatching {
            val hapticClass = param.classLoader.loadClass(
                "com.miui.home.common.hapticfeedback.HapticFeedbackCompat")
            val instance = hapticClass.getDeclaredMethod("getInstance").invoke(null)
            hapticClass.getDeclaredMethod("performHomeGestureAccessibilitySwitch",
                android.view.View::class.java).invoke(instance, nav)
            fLog("haptic: ✓ performHomeGestureAccessibilitySwitch")
        }.onFailure { fLog("haptic: FAILED — ${it.message}") }
    }
}
