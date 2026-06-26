package com.example.flipunlock.hook.system

import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.method
import com.example.flipunlock.hook.util.replaceResult
import com.example.flipunlock.hook.util.safeHook
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Hook isLetterboxedForDisplayCutout() in WindowState to always return false.
 *
 * From reverse engineering (DisplayCutout.md):
 * This method determines whether to apply letterboxing around the display
 * cutout. It checks parentFrameWasClippedByDisplayCutout() and
 * layoutInDisplayCutoutMode. If layoutInDisplayCutoutMode == 3 (ALWAYS)
 * or isMiuiLayoutInCutoutAlways() returns true, letterboxing is bypassed.
 *
 * However, the current fullscreen approach (CutoutHook + ActivityLifecycleHook)
 * doesn't hook this method. Even with Display.getCutout() returning zero and
 * layoutInDisplayCutoutMode set to ALWAYS on each activity, some windows
 * (especially system windows added before the activity sets its cutout mode)
 * may still be letterboxed. Hooking this provides defense-in-depth.
 */
object LetterboxHook {

    fun hook(param: SystemServerStartingParam) {
        safeHook("LetterboxHook") {
            runCatching {
                val windowStateClass = param.classLoader.findClass(
                    "com.android.server.wm.WindowState"
                )
                val method = windowStateClass.method("isLetterboxedForDisplayCutout")
                hook(method, replaceResult(false))
                log("LetterboxFix: forced isLetterboxedForDisplayCutout -> false")
            }.onFailure { log("LetterboxFix: failed", it) }
        }
    }
}
