package com.example.flipunlock

import com.example.flipunlock.hook.ActivityLifecycleHook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.Config
//import com.example.flipunlock.hook.LauncherDensityHook  // TODO: density tweak not working
import com.example.flipunlock.hook.SogouInputHook
import com.example.flipunlock.hook.AodHook
//import com.example.flipunlock.hook.CameraHook  // TODO: not working
import com.example.flipunlock.hook.CutoutHook
import com.example.flipunlock.hook.DeviceIdentityHook
import com.example.flipunlock.hook.WatchOverlayHook
import com.example.flipunlock.hook.ScreenTypeHook
import com.example.flipunlock.hook.SystemUIHook
import com.example.flipunlock.hook.gesture.GestureHook
import com.example.flipunlock.hook.LauncherHook
import com.example.flipunlock.hook.LockScreenHook
import com.example.flipunlock.hook.system.AppBoundsHook
import com.example.flipunlock.hook.system.CompatConfigHook
import com.example.flipunlock.hook.system.DisplayStateHook
import com.example.flipunlock.hook.system.InputMethodHook
import com.example.flipunlock.hook.system.InterceptHook
import com.example.flipunlock.hook.system.LetterboxHook
import com.example.flipunlock.hook.system.SubScreenGestureHook
import com.example.flipunlock.hook.system.SystemServicesHook
import com.example.flipunlock.hook.system.WhitelistHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal var module: Main? = null

class Main : XposedModule() {

    private val hooks = listOf(
        ScreenTypeHook,  // Configuration.getScreenType → 0
        DeviceIdentityHook,  // IS_FLIP / isFlipDevice / isFoldDevice → false
        AodHook,  // v2.3: screen state fix + FlipLinkageStyleController
//        CameraHook,  // TODO: front camera redirect not working — HAL reports all cameras as LENS_FACING_BACK
        CutoutHook,
        SystemUIHook,
        GestureHook,  // v2: block fliphome InputMonitor → system gestures
        LauncherHook,  // block SpecialFDeviceGestureHelper → keep NavStubView on outer screen
        LockScreenHook,  // fix lock screen: swipe, shortcuts, wallpaper on outer screen
//        LauncherDensityHook,  // Not needed: state=6 already adapts launcher to outer screen
        SogouInputHook,
        ActivityLifecycleHook,
        WatchOverlayHook,
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        Config.logConfig()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log("Main: onSystemServerStarting — loading system hooks")
        CutoutHook.hookFramework(param)
        LetterboxHook.hook(param)
        WhitelistHook.hook(param)
        SubScreenGestureHook.hook(param)
        DisplayStateHook.hook(param)
        CompatConfigHook.hook(param)
        AppBoundsHook.hook(param)
        SystemServicesHook.hook(param)
        InputMethodHook.hook(param)
        InterceptHook.hook(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log("Main: onPackageReady pkg=${param.packageName} first=${param.isFirstPackage}")
        hooks.forEach { hook ->
            val isWildcard = hook.targetPackages.contains("*")
            val isTargeted = hook.targetPackages.contains(param.packageName)

            if (!isWildcard && !isTargeted) return@forEach

            // "*" hooks use the first package's classloader (framework classes).
            // Skip them for subsequent packages to avoid duplicate hooking.
            if (isWildcard && !param.isFirstPackage) return@forEach

            log("Main: loading ${hook.javaClass.simpleName} for ${param.packageName}")
            hook.hook(param)
        }
    }
}
