package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.safeHook
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

abstract class BaseHook {
    abstract val targetPackages: List<String>
    private var loaded = false

    open fun hook(param: PackageReadyParam) {
        if (loaded) return
        loaded = true
        safeHook(javaClass.simpleName) {
            setupHooks(param)
        }
    }

    protected open fun setupHooks(param: PackageReadyParam) {}
}
