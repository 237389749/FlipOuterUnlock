package com.example.flipunlock.hook

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

object ActivityLifecycleHook : BaseHook() {
    override val targetPackages = listOf("*")

    override fun hook(param: PackageReadyParam) {
        if (!shouldHook(param.packageName)) return
        safeHook("ActivityLifecycleHook") {
            hookOnCreate()
            hookOnResume()
        }
    }

    private fun shouldHook(pkg: String): Boolean = true

    private fun hookOnCreate() {
        runCatching {
            val onCreateMethod = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
            onCreateMethod.isAccessible = true
            hook(onCreateMethod, after { chain, _ ->
                val activity = chain.thisObject as? Activity ?: return@after chain.result
                runCatching {
                    val attrs = activity.window?.attributes ?: return@runCatching
                    if (attrs.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
                        attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        activity.window?.attributes = attrs
                    }
                }.onFailure { log("error setting cutout mode", it) }
                chain.result
            })
            log("hooked Activity.onCreate")
        }.onFailure { log("failed to hook Activity.onCreate", it) }
    }

    private fun hookOnResume() {
        runCatching {
            val onResumeMethod = Activity::class.java.getDeclaredMethod("onResume")
            onResumeMethod.isAccessible = true
            hook(onResumeMethod, after { chain, _ ->
                val activity = chain.thisObject as? Activity ?: return@after chain.result
                hideSystemBars(activity)
                chain.result
            })
            log("hooked Activity.onResume")
        }.onFailure { log("failed to hook Activity.onResume", it) }
    }

    private fun hideSystemBars(activity: Activity) {
        if (activity.window == null) return
        activity.window?.decorView?.post {
            runCatching {
                val controller = activity.window?.insetsController ?: return@post
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
            }.onFailure { log("failed to hide system bars", it) }
        }
    }
}
