package com.example.flipunlock

// ── always on (no toggle) ───────────────────────────────────────────
import com.example.flipunlock.hook.identity.DeviceIdentityHook
import com.example.flipunlock.hook.identity.ScreenTypeHook
import com.example.flipunlock.hook.applaunch.ActivityLifecycleHook

// ── display.dual ────────────────────────────────────────────────────
import com.example.flipunlock.hook.display.DisplayStateHook

// ── display.cutout ──────────────────────────────────────────────────
import com.example.flipunlock.hook.cutout.CutoutHook
import com.example.flipunlock.hook.cutout.GlobalCutoutHook
import com.example.flipunlock.hook.cutout.AppBoundsHook
import com.example.flipunlock.hook.cutout.LetterboxHook
import com.example.flipunlock.hook.cutout.SystemServicesHook

// ── display.aod ─────────────────────────────────────────────────────
import com.example.flipunlock.hook.aod.AodHook

// ── gesture.home + gesture.back ─────────────────────────────────────
import com.example.flipunlock.hook.gesture.GestureHook
import com.example.flipunlock.hook.gesture.LauncherHook
import com.example.flipunlock.hook.gesture.SubScreenGestureHook

// ── ui.lockscreen ───────────────────────────────────────────────────
import com.example.flipunlock.hook.lockscreen.LockScreenHook

// ── ui.controlcenter + no toggle ────────────────────────────────────
import com.example.flipunlock.hook.systemui.ControlCenterHook
import com.example.flipunlock.hook.systemui.SystemUIHook

// ── ui.widget ───────────────────────────────────────────────────────
import com.example.flipunlock.hook.widget.WatchOverlayHook

// ── ui.recentsmenu ──────────────────────────────────────────────────
import com.example.flipunlock.hook.recents.RecentsMenuHook

// ── ime ─────────────────────────────────────────────────────────────
import com.example.flipunlock.hook.ime.SogouInputHook
import com.example.flipunlock.hook.ime.InputMethodHook

// ── applaunch (always on) ───────────────────────────────────────────
import com.example.flipunlock.hook.applaunch.InterceptHook
import com.example.flipunlock.hook.applaunch.CompatConfigHook
import com.example.flipunlock.hook.applaunch.WhitelistHook

// ── camera (disabled) ───────────────────────────────────────────────
//import com.example.flipunlock.hook.camera.CameraHook

// ── util ────────────────────────────────────────────────────────────
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.Config

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal var module: Main? = null

class Main : XposedModule() {

    // ── App-process hooks (onPackageReady) ──────────────────────────
    //     Grouped by functional directory, matching setprop toggles.
    //     "*" wildcard hooks fire on firstPackage only.
    // ──────────────────────────────────────────────────────────────────

    private val hooks = listOf(
        // identity/ — always on (no toggle)
        ScreenTypeHook,                 // Configuration.getScreenType → 0
        DeviceIdentityHook,             // isFlipDevice / isFoldDevice → false

        // cutout/ — display.cutout
        GlobalCutoutHook,               // Display.getCutout + WindowInsets → zero (all apps)

        // aod/ — display.aod
        AodHook,                        // screen state fix + FlipLinkageStyleController

        // systemui/ — ui.controlcenter + no toggle
        ControlCenterHook,              // restore normal control center style
        SystemUIHook,                   // hide decor window, status bar, NavigationBar

        // cutout/ — display.cutout (targeted: SystemUI, AOD, Camera)
        CutoutHook,

        // gesture/ — gesture.back
        GestureHook,                    // block fliphome InputMonitor → system gestures

        // gesture/ — gesture.home
        LauncherHook,                   // block SpecialFDeviceGestureHelper → NavStubView

        // lockscreen/ — ui.lockscreen
        LockScreenHook,                 // lock screen layout, swipe, shortcuts

        // recents/ — ui.recentsmenu
        RecentsMenuHook,                // recents task long-press menu

        // ime/ — ime
        SogouInputHook,                 // IME toolbar + clipboard fix

        // applaunch/ — always on (no toggle)
        ActivityLifecycleHook,          // layoutInDisplayCutoutMode=ALWAYS

        // widget/ — ui.widget
        WatchOverlayHook,               // disable widget overlay

        // camera/ — disabled
        //CameraHook,
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        Config.logConfig()
    }

    // ── System-server hooks (onSystemServerStarting) ─────────────────
    //     Grouped by functional directory.
    // ──────────────────────────────────────────────────────────────────

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log("Main: onSystemServerStarting — loading system hooks")

        // cutout/ — display.cutout
        CutoutHook.hookFramework(param)     // Parser + Display.getCutout choke points
        LetterboxHook.hook(param)           // isLetterboxedForDisplayCutout
        AppBoundsHook.hook(param)           // InsetsState + app bounds
        SystemServicesHook.hook(param)      // BoundsCompatUtils + WindowManager

        // display/ — display.dual
        DisplayStateHook.hook(param)        // device state, layout, AOD power

        // applaunch/ — always on
        CompatConfigHook.hook(param)        // continuity.policy → allow
        InterceptHook.hook(param)           // block app launch interception
        WhitelistHook.hook(param)           // dumpsys whitelist all packages

        // ime/ — ime
        InputMethodHook.hook(param)         // IME enable, rotation nag, unlock choice

        // gesture/
        SubScreenGestureHook.hook(param)    // multi-finger sub-screen gestures
    }

    // ── App-process hook dispatch ────────────────────────────────────

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
