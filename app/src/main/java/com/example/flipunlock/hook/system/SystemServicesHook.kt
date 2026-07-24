package com.example.flipunlock.hook.system

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

object SystemServicesHook {

    fun hook(param: SystemServerStartingParam) {
        log("SystemServicesHook: setting up")
        safeHook("SystemServicesHook") {
            hookBoundsCompatUtilsByApp(param)
            hookBoundsCompatUtilsByActivity(param)
            hookWindowManagerGetFullScreenValue(param)
            hookCompatGravity(param)
        }
        log("SystemServicesHook: done")
    }

    /**
     * BoundsCompatUtils.getCompatGravity() → 0 (no special gravity).
     *
     * In system_server, this method reads the internal DisplayCutout (not
     * affected by our Display.getCutout() API hook). It checks cutout safe
     * insets to choose gravity:
     *   safeInsetLeft>0  → RIGHT gravity (5)
     *   safeInsetTop>0   → BOTTOM gravity (80)
     *   safeInsetRight>0 → LEFT gravity (3)
     *   safeInsetBottom>0 → TOP gravity (48)
     *
     * The chosen gravity feeds into positionCompatBounds() which computes
     * bounds.left/top offsets. These become the SizeCompatBounds that
     * applyViewLocation() uses to shift views — causing the left-shift
     * of popups/toasts.
     *
     * By returning 0 (NO_GRAVITY), we prevent the cutout-based offset
     * from entering the compat bounds pipeline at its source.
     */
    private fun hookCompatGravity(param: SystemServerStartingParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.server.wm.BoundsCompatUtils")
            // getCompatGravity(DisplayCutout) → int
            val method = cls.declaredMethods.firstOrNull {
                it.name == "getCompatGravity" && it.parameterCount == 1
            }
            if (method != null) {
                method.isAccessible = true
                hook(method, replaceResult(0))
                log("SystemServicesHook: getCompatGravity → 0")
            }
        }.onFailure { log("SystemServicesHook: getCompatGravity failed", it) }
    }

    private fun hookBoundsCompatUtilsByApp(param: SystemServerStartingParam) {
        runCatching {
            val boundsCompatUtils = param.classLoader.loadClass(
                "com.android.server.wm.BoundsCompatUtils"
            )
            val atmsClass = param.classLoader.loadClass(
                "android.app.ActivityTaskManagerService"
            )
            val method = boundsCompatUtils.method(
                "getFlipCompatModeByApp", atmsClass, String::class.java
            )
            hook(method, replaceResult(0))
            log("forced BoundsCompatUtils.getFlipCompatModeByApp -> 0")
        }.onFailure { log("failed to hook getFlipCompatModeByApp", it) }
    }

    private fun hookBoundsCompatUtilsByActivity(param: SystemServerStartingParam) {
        runCatching {
            val boundsCompatUtils = param.classLoader.loadClass(
                "com.android.server.wm.BoundsCompatUtils"
            )
            val activityRecordClass = param.classLoader.loadClass(
                "com.android.server.wm.ActivityRecord"
            )
            val method = boundsCompatUtils.method(
                "getFlipCompatModeByActivity", activityRecordClass
            )
            hook(method, replaceResult(0))
            log("forced BoundsCompatUtils.getFlipCompatModeByActivity -> 0")
        }.onFailure { log("failed to hook getFlipCompatModeByActivity", it) }
    }

    private fun hookWindowManagerGetFullScreenValue(param: SystemServerStartingParam) {
        runCatching {
            val wmsImpl = param.classLoader.loadClass(
                "com.android.server.wm.WindowManagerServiceImpl"
            )
            val packageItemInfoClass = param.classLoader.loadClass(
                "android.content.pm.PackageItemInfo"
            )
            val method = wmsImpl.method(
                "getFullScreenValue", packageItemInfoClass
            )
            hook(method, replaceResult(0))
            log("forced WindowManagerServiceImpl.getFullScreenValue -> 0")
        }.onFailure { log("failed to hook getFullScreenValue", it) }
    }
}
