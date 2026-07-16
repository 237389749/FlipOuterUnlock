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
        }
        log("SystemServicesHook: done")
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
