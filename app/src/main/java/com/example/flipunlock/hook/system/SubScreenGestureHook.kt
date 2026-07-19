package com.example.flipunlock.hook.system

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Enable MiuiSubScreenMultiFingerGestureManager for Mix Flip (type 4).
 *
 * This gesture manager provides:
 *   - MiuiSubscreenDoubleTapGesture (double-tap outer screen → sleep)
 *   - MiuiSubscreenThreeFingerDownGesture (3-finger swipe down → screenshot)
 *
 * Two problems need fixing:
 *   1. init() guards on isIndependentRearDevice() == true (type 6 only).
 *      Mix Flip is type 4 (flip), so it never initializes.
 *      Fix: hook init() to bypass device type guard, create instance directly.
 *
 *   2. The class hardcodes NEED_DISPLAY_ID = 1 everywhere:
 *        registerPointerEventListener(this, 1)
 *        onFocusedWindowChanged: if (displayId != 1) return
 *        pilferPointers(1)
 *        goToSleep(1, ...)
 *      With DisplayStateHook state=6 (DUAL), outer screen IS displayId=0.
 *      So the gesture monitor listens on the wrong display and never fires.
 *      Fix: hook registerPointerEventListener + onFocusedWindowChanged to use displayId=0.
 */
object SubScreenGestureHook {

    fun hook(param: SystemServerStartingParam) {
        safeHook("SubScreenGestureHook") {
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.miui.server.input.gesture.multifingergesture.MiuiSubScreenMultiFingerGestureManager"
                )
                val monitorCls = param.classLoader.loadClass(
                    "com.miui.server.input.gesture.MiuiGestureMonitor"
                )

                // Hook init(Context) — bypass isIndependentRearDevice() guard.
                val initMethod = cls.method("init", android.content.Context::class.java)
                hook(initMethod) { chain ->
                    val existing = runCatching {
                        cls.callMethod("getInstance")
                    }.getOrNull()
                    if (existing == null) {
                        val context = chain.args[0] as? android.content.Context
                        if (context != null) {
                            val constructor = cls.getDeclaredConstructor(
                                android.content.Context::class.java)
                            constructor.isAccessible = true
                            val instance = constructor.newInstance(context)
                            cls.field("sInstance").set(null, instance)
                            log("SubScreenGesture: initialized for Mix Flip external display!")
                        }
                    }
                    chain.proceed()
                }

                // Fix: redirect displayId 1→0 for registerPointerEventListener.
                // When the gesture manager constructor calls registerPointerEventListener(this, 1),
                // we change 1→0 so the gesture monitor listens on the outer screen.
                val gestureListenerClass = param.classLoader.loadClass(
                    "com.miui.server.input.gesture.MiuiGestureListener")
                val regMethod = monitorCls.getDeclaredMethod("registerPointerEventListener",
                    gestureListenerClass,
                    Int::class.javaPrimitiveType!!)
                regMethod.isAccessible = true
                hook(regMethod) { chain ->
                    val displayId = chain.args[1] as? Int ?: return@hook chain.proceed()
                    if (displayId == 1) {
                        log("SubScreenGesture: redirect registerPointerEventListener displayId 1→0")
                        chain.args[1] = 0
                    }
                    chain.proceed()
                }

                // Fix: redirect displayId 1→0 for onFocusedWindowChanged.
                // Original code: if (displayId != 1) return;
                // With displayId=0 in state=6, this always bails out. We need to accept 0.
                val windowStateClass = param.classLoader.loadClass(
                    "com.android.server.policy.WindowManagerPolicy\$WindowState")
                val focusMethod = cls.getDeclaredMethod("onFocusedWindowChanged",
                    Int::class.javaPrimitiveType!!,
                    windowStateClass,
                    windowStateClass)
                focusMethod.isAccessible = true
                hook(focusMethod) { chain ->
                    val displayId = chain.args[0] as? Int ?: return@hook chain.proceed()
                    if (displayId == 0) {
                        chain.args[0] = 1  // Pretend it's displayId=1 so the guard passes
                        log("SubScreenGesture: redirect onFocusedWindowChanged displayId 0→1")
                    }
                    chain.proceed()
                }

                log("SubScreenGesture: hooked with displayId fix")
            }.onFailure { log("SubScreenGesture: failed", it) }
        }
    }
}
