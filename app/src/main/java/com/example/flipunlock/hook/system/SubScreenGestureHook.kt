package com.example.flipunlock.hook.system

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Enable MiuiSubScreenMultiFingerGestureManager for Mix Flip (type 4).
 *
 * From point.txt and ref docs (Gesture_Widget_Overlay.md section 11):
 * - This gesture manager provides system-level multi-finger gestures on sub-screens
 * - It targets displayId=1 (matches Mix Flip's external screen!)
 * - But init() guards on isIndependentRearDevice() == true (type 6 only)
 * - Mix Flip is type 4 (flip), so it never initializes
 *
 * We hook init() to bypass the device type guard and force initialization.
 * The isIndependentRearDevice hook is intentionally NOT used here because
 * it would have system-wide side effects.
 *
 * Gestures supported:
 * - MiuiSubscreenDoubleTapGesture (double-tap the external screen)
 * - MiuiSubscreenThreeFingerDownGesture (three-finger swipe down)
 */
object SubScreenGestureHook {

    fun hook(param: SystemServerStartingParam) {
        safeHook("SubScreenGestureHook") {
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.miui.server.input.gesture.multifingergesture.MiuiSubScreenMultiFingerGestureManager"
                )

                // Hook init(Context). Original code:
                //   if (isIndependentRearDevice() && sInstance == null) {
                //       sInstance = new MiuiSubScreenMultiFingerGestureManager(ctx);
                //   }
                // We hook init BEFORE it runs, create the instance ourselves
                // via reflection, and set sInstance. Then the original init()
                // will see sInstance != null and skip.
                val initMethod = cls.method("init", android.content.Context::class.java)
                hook(initMethod) { chain ->
                    val existing = runCatching {
                        cls.callMethod("getInstance")
                    }.getOrNull()
                    if (existing != null) {
                        // Already initialized, let original run (it will skip)
                        chain.proceed()
                    } else {
                        val context = chain.args[0] as? android.content.Context
                        if (context != null) {
                            // Find constructor: MiuiSubScreenMultiFingerGestureManager(Context)
                            val constructor = cls.declaredConstructors.firstOrNull {
                                it.parameterCount == 1
                            }
                            if (constructor != null) {
                                constructor.isAccessible = true
                                val instance = constructor.newInstance(context)
                                // Set static sInstance field
                                cls.field("sInstance").set(null, instance)
                                log("SubScreenGesture: initialized for Mix Flip external display!")
                            }
                        }
                        // Let original init run (will see sInstance != null and no-op)
                        chain.proceed()
                    }
                }
                log("SubScreenGesture: hooked MiuiSubScreenMultiFingerGestureManager.init()")
            }.onFailure { log("SubScreenGesture: failed", it) }
        }
    }
}
