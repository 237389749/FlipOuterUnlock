package com.example.flipunlock

import com.example.flipunlock.hook.ActivityLifecycleHook
import com.example.flipunlock.hook.CutoutHook
import com.example.flipunlock.hook.WatchOverlayHook
import com.example.flipunlock.hook.system.InputMethodHook
import com.example.flipunlock.hook.system.InterceptHook
import com.example.flipunlock.hook.system.SystemServicesHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal var module: Main? = null

class Main : XposedModule() {

    private val hooks = listOf(
        CutoutHook,
        ActivityLifecycleHook,
        WatchOverlayHook,
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        SystemServicesHook.hook(param)
        InputMethodHook.hook(param)
        InterceptHook.hook(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        hooks.forEach { hook ->
            if (hook.targetPackages.contains(param.packageName) || hook.targetPackages.contains("*")) {
                hook.hook(param)
            }
        }
    }
}
