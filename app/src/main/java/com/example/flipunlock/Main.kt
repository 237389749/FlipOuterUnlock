package com.example.flipunlock

import com.example.flipunlock.hook.ActivityLifecycleHook
//import com.example.flipunlock.hook.LauncherDensityHook  // TODO: density tweak not working
import com.example.flipunlock.hook.SogouInputHook
import com.example.flipunlock.hook.CutoutHook
import com.example.flipunlock.hook.DeviceIdentityHook
import com.example.flipunlock.hook.WatchOverlayHook
//import com.example.flipunlock.hook.ScreenTypeHook  // ⚠️ 内屏样式锁屏无法上滑
import com.example.flipunlock.hook.SystemUIHook
//import com.example.flipunlock.hook.gesture.GestureHook
import com.example.flipunlock.hook.system.AppBoundsHook
import com.example.flipunlock.hook.system.CompatConfigHook
import com.example.flipunlock.hook.system.InputMethodHook
import com.example.flipunlock.hook.system.InterceptHook
import com.example.flipunlock.hook.system.LetterboxHook
//import com.example.flipunlock.hook.system.SubScreenGestureHook
import com.example.flipunlock.hook.system.SystemServicesHook
import com.example.flipunlock.hook.system.WhitelistHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal var module: Main? = null

class Main : XposedModule() {

    private val hooks = listOf(
//        ScreenTypeHook,  // ⚠️ 内屏样式锁屏无法上滑
        DeviceIdentityHook,  // ← IS_FLIP 已注释排查中
        CutoutHook,
        SystemUIHook,
//        GestureHook,
//        LauncherDensityHook,  // TODO: density tweak not working
        SogouInputHook,
        ActivityLifecycleHook,
        WatchOverlayHook,
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        CutoutHook.hookFramework(param)
        LetterboxHook.hook(param)
        WhitelistHook.hook(param)
//        SubScreenGestureHook.hook(param)
        CompatConfigHook.hook(param)
        AppBoundsHook.hook(param)
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
