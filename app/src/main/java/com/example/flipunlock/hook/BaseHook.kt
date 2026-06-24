package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.safeHook
import com.example.flipunlock.module
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

abstract class BaseHook {
    abstract val targetPackages: List<String>

    open fun hook(param: PackageReadyParam) {
        safeHook(javaClass.simpleName) {
            setupHooks(param)
        }
    }

    protected open fun setupHooks(param: PackageReadyParam) {}
}
