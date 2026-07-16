package com.example.flipunlock.hook

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

object ActivityLifecycleHook : BaseHook() {
    override val targetPackages = listOf("*")

    override fun hook(param: PackageReadyParam) {
        if (!shouldHook(param.packageName)) return
        log("ActivityLifecycleHook: loading for ${param.packageName}")
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
            hook(onCreateMethod, after { chain, result ->
                val activity = chain.thisObject as? Activity ?: return@after result
                runCatching {
                    val attrs = activity.window?.attributes ?: return@runCatching
                    if (attrs.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
                        attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        activity.window?.attributes = attrs
                    }
                }.onFailure { log("error setting cutout mode", it) }
                result
            })
            log("hooked Activity.onCreate")
        }.onFailure { log("failed to hook Activity.onCreate", it) }
    }

    private fun hookOnResume() {
        runCatching {
            val onResumeMethod = Activity::class.java.getDeclaredMethod("onResume")
            onResumeMethod.isAccessible = true
            hook(onResumeMethod, after { chain, result ->
                val activity = chain.thisObject as? Activity ?: return@after result
                log("re-hid system bars in onResume for ${activity.packageName}")
                result
            })
            log("hooked Activity.onResume")
        }.onFailure { log("failed to hook Activity.onResume", it) }
    }
}
