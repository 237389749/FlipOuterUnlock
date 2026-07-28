package com.example.flipunlock.hook.cutout
import com.example.flipunlock.hook.BaseHook

import android.graphics.Insets
import android.graphics.Path
import android.graphics.Rect
import android.view.Display
import android.view.DisplayCutout
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.util.Collections

/**
 * Cutout blocking at framework choke points — system_server + targeted apps.
 *
 * Division of labor with GlobalCutoutHook:
 *   CutoutHook:   CutoutSpecification.Parser, pathAndDisplayCutoutFromSpec,
 *                 getFlipFoldedCutout, getCutoutPosition (AOD). These are
 *                 NOT covered by GlobalCutoutHook.
 *   CutoutHook (system_server): additionally hooks Display.getCutout() +
 *                 WindowInsets.getDisplayCutout() — system_server is not
 *                 covered by GlobalCutoutHook (app-only).
 *   GlobalCutoutHook (app processes): Display.getCutout() +
 *                 WindowInsets.getDisplayCutout() + isFlipFolded +
 *                 sizeCompatScale — covers ALL app processes.
 */
object CutoutHook : BaseHook() {
    override val targetPackages = listOf(
        "com.android.systemui",
        "com.miui.aod",
        "com.android.camera",
    )

    private var zeroCutout: DisplayCutout? = null

    fun hookFramework(param: SystemServerStartingParam) {
        if (!Config.displayCutout) { log("CutoutHook: DISABLED by persist.flipunlock.display.cutout"); return }
        log("CutoutHook-framework: setting up in system_server")
        safeHook("CutoutHook-framework") {
            hookCutoutParser(param.classLoader)
            hookPathAndDisplayCutoutFromSpec(param.classLoader)
            hookDisplayGetCutout()
            hookDisplayFlipFoldedCutout()
            hookWindowInsetsGetCutout()
            clearDisplayCutoutNow(param.classLoader)
        }
    }

    /**
     * Clear the boot-time cached DisplayCutout on ALL DisplayContent objects
     * and force a display update to propagate NO_CUTOUT to app processes
     * (including SystemUI's HideDisplayCutoutOrganizer).
     *
     * The cutout is created in LocalDisplayDevice before LSPosed loads.
     * Hooking creation methods prevents new ones — this clears existing.
     *
     * Step 1: Find WMS → RootWindowContainer → iterate all DisplayContents
     * Step 2: Set mDisplayInfo.displayCutout = NO_CUTOUT on each
     * Step 3: Force a relayout to propagate the change
     */
    private fun clearDisplayCutoutNow(classLoader: ClassLoader) {
        val displayCutoutClass = classLoader.loadClass("android.view.DisplayCutout")
        val noCutout = displayCutoutClass.getDeclaredField("NO_CUTOUT")
            .apply { isAccessible = true }.get(null) ?: return

        // Hook DisplayContent.getDisplayInfo: clear cutout on EVERY read
        runCatching {
            val dcClass = classLoader.loadClass("com.android.server.wm.DisplayContent")
            val method = dcClass.getDeclaredMethod("getDisplayInfo")
            method.isAccessible = true
            hook(method, before { chain ->
                val info = chain.thisObject.getField("mDisplayInfo")
                info?.setField("displayCutout", noCutout)
            })
            log("CutoutHook: ✓ getDisplayInfo → zero cutout every call")
        }.onFailure { log("CutoutHook: getDisplayInfo hook failed", it) }

        // Hook relayoutWindow: first call after hooks → force display update
        runCatching {
            val wmsClass = classLoader.loadClass("com.android.server.wm.WindowManagerService")
            val method = wmsClass.getDeclaredMethod("relayoutWindow",
                classLoader.loadClass("android.view.IWindow"), Int::class.javaPrimitiveType!!,
                classLoader.loadClass("android.view.WindowManager\$LayoutParams"),
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
                classLoader.loadClass("android.view.ClientWindowFrames"),
                classLoader.loadClass("android.util.MergedConfiguration"),
                classLoader.loadClass("android.view.SurfaceControl"),
                classLoader.loadClass("android.view.InsetsState"),
                Boolean::class.javaPrimitiveType!!,
                Float::class.javaPrimitiveType!!,
                Float::class.javaPrimitiveType!!)
            method.isAccessible = true
            var triggered = false
            hook(method, before {
                if (!triggered) {
                    triggered = true
                    // Force WMS to recompute display info by requesting traversal
                    runCatching {
                        val wms = it.thisObject
                        wms.callMethod("requestTraversal")
                        log("CutoutHook: forced WMS traversal for display update")
                    }
                }
            })
        }.onFailure { log("CutoutHook: relayoutWindow hook failed", it) }
    }

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.displayCutout) return
        log("CutoutHook: loading for ${param.packageName}")
        // App-process hooks that GlobalCutoutHook does NOT cover.
        // Display.getCutout() and WindowInsets.getDisplayCutout() are
        // handled by GlobalCutoutHook in ALL app processes — duplicating
        // them here would violate "one method, one process, one hook".
        hookCutoutParser(param.classLoader)
        hookPathAndDisplayCutoutFromSpec(param.classLoader)
        hookDisplayFlipFoldedCutout()
        hookDisplayUtilsGetCutoutPosition(param)
    }

    private fun hookCutoutParser(classLoader: ClassLoader) {
        runCatching {
            val parserClass = classLoader.loadClass("android.view.CutoutSpecification\$Parser")
            val parseMethod = parserClass.method("parse", String::class.java)
            hook(parseMethod, after { chain, result ->
                val spec = result ?: return@after result
                spec.setField("mLeftBound", Rect(0, 0, 0, 0))
                spec.setField("mTopBound", Rect(0, 0, 0, 0))
                spec.setField("mRightBound", Rect(0, 0, 0, 0))
                spec.setField("mBottomBound", Rect(0, 0, 0, 0))
                spec.setField("mInsets", Insets.of(0, 0, 0, 0))
                spec.setField("mPath", Path())
                result
            })
        }.onFailure { log("CutoutFix: failed hook parser", it) }

        runCatching {
            val parserClass = classLoader.loadClass("android.view.CutoutSpecification\$Parser")
            val method = parserClass.getDeclaredMethod("computeSafeInsets",
                Int::class.javaPrimitiveType!!,
                android.graphics.Rect::class.java)
            method.isAccessible = true
            hook(method) { chain ->
                val outRect = chain.args[1] as? android.graphics.Rect
                outRect?.setEmpty()
                0
            }
        }.onFailure { log("CutoutFix: failed hook computeSafeInsets", it) }
    }

    private fun hookPathAndDisplayCutoutFromSpec(classLoader: ClassLoader) {
        runCatching {
            val dcClass = classLoader.loadClass("android.view.DisplayCutout")
            val method = dcClass.declaredMethods.firstOrNull {
                it.name == "pathAndDisplayCutoutFromSpec" && it.parameterCount == 9
            } ?: return@runCatching
            method.isAccessible = true
            val noCutout = dcClass.getDeclaredField("NO_CUTOUT").also { it.isAccessible = true }.get(null)!!
            val pairClass = classLoader.loadClass("android.util.Pair")
            val pairCtor = pairClass.getConstructor(Any::class.java, Any::class.java)
            hook(method) {
                pairCtor.newInstance(null, noCutout)
            }
        }.onFailure { log("CutoutFix: failed hook pathAndDisplayCutoutFromSpec", it) }
    }

    private fun hookDisplayGetCutout() {
        runCatching {
            val getCutoutMethod = Display::class.java.method("getCutout")
            hook(getCutoutMethod, Hooker { chain ->
                val zero = getZeroCutout()
                if (zero != null) zero else chain.proceed()
            })
        }.onFailure { log("CutoutFix: failed hook Display.getCutout", it) }
    }

    private fun hookDisplayFlipFoldedCutout() {
        runCatching {
            val method = Display::class.java.method("getFlipFoldedCutout")
            hook(method, replaceResult(null))
        }.onFailure { /* method may not exist */ }
    }

    private fun hookDisplayUtilsGetCutoutPosition(param: PackageReadyParam) {
        if (param.packageName != "com.miui.aod") return
        runCatching {
            val displayUtilsClass = param.classLoader.loadClass("com.miui.aod.util.DisplayUtils")
            val directionClass = param.classLoader.loadClass("com.miui.aod.widget.Direction")
            val noneDirection = directionClass.getField("CAMERA_CUTOUT_ON_NONE").get(null)
            val getCutoutPositionMethod = displayUtilsClass.method(
                "getCutoutPosition", android.content.Context::class.java
            )
            hook(getCutoutPositionMethod, replaceResult(noneDirection))
        }.onFailure { log("CutoutFix: failed hook DisplayUtils", it) }
    }

    private fun getZeroCutout(): DisplayCutout? {
        if (zeroCutout != null) return zeroCutout
        runCatching {
            zeroCutout = constructZeroCutout()
        }.onFailure { log("CutoutFix: construct zero cutout failed", it) }
        return zeroCutout
    }

    private fun hookWindowInsetsGetCutout() {
        runCatching {
            val insetsClass = android.view.WindowInsets::class.java
            val method = insetsClass.getDeclaredMethod("getDisplayCutout")
            method.isAccessible = true
            hook(method, replaceResult(null))
            log("CutoutFix: WindowInsets.getDisplayCutout → null")
        }.onFailure { log("CutoutFix: WindowInsets.getDisplayCutout failed", it) }
    }

    private fun constructZeroCutout(): DisplayCutout {
        val dcClass = DisplayCutout::class.java
        val constructor = dcClass.declaredConstructors.minByOrNull { it.parameterCount }
            ?: throw NoSuchMethodException("No DisplayCutout constructor")
        constructor.isAccessible = true
        val paramTypes = constructor.parameterTypes
        val args = paramTypes.map { type ->
            when (type) {
                Insets::class.java -> Insets.of(0, 0, 0, 0)
                Rect::class.java -> Rect(0, 0, 0, 0)
                Path::class.java -> Path()
                Int::class.javaPrimitiveType, Integer::class.java -> 0
                Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
                java.util.List::class.java -> Collections.emptyList<Any>()
                else -> null
            }
        }.toTypedArray()
        return constructor.newInstance(*args) as DisplayCutout
    }
}
