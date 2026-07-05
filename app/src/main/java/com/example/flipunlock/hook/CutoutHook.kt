package com.example.flipunlock.hook

import android.graphics.Insets
import android.graphics.Path
import android.graphics.Rect
import android.view.Display
import android.view.DisplayCutout
import com.example.flipunlock.Prefs
import com.example.flipunlock.hook.util.*
import com.example.flipunlock.module
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.util.Collections

object CutoutHook : BaseHook() {
    override val targetPackages = listOf(
        "com.android.systemui",
        "com.miui.aod",
        "com.android.camera",
    )

    private var zeroCutout: DisplayCutout? = null

    private fun isFullscreenEnabled(): Boolean {
        return module?.getRemotePreferences(Prefs.NAME)
            ?.getBoolean(Prefs.GLOBAL_FULLSCREEN, false) ?: false
    }

    fun hookFramework(param: SystemServerStartingParam) {
        safeHook("CutoutHook-framework") {
            hookCutoutParser(param.classLoader)
            hookDisplayGetCutout()
        }
    }

    override fun setupHooks(param: PackageReadyParam) {
        hookCutoutParser(param.classLoader)
        hookDisplayGetCutout()
        hookDisplayUtilsGetCutoutPosition(param)
    }

    private fun hookCutoutParser(classLoader: ClassLoader) {
        runCatching {
            val parserClass = classLoader.loadClass("android.view.CutoutSpecification\$Parser")
            val parseMethod = parserClass.method("parse", String::class.java)
            // afterHookedMethod: modify parse result in-place
            hook(parseMethod, after { chain, result ->
                val spec = result ?: return@after result
                val originalSpec = chain.args[0] as? String ?: return@after result
                if (originalSpec.contains("M 604,664") || originalSpec.contains("@bind_right_cutout")) {
                    spec.setField("mLeftBound", Rect(0, 0, 0, 0))
                    spec.setField("mTopBound", Rect(0, 0, 0, 0))
                    spec.setField("mRightBound", Rect(0, 0, 0, 0))
                    spec.setField("mBottomBound", Rect(0, 0, 0, 0))
                    spec.setField("mInsets", Insets.of(0, 0, 0, 0))
                    spec.setField("mPath", Path())
                }
                result
            })
        }.onFailure { log("CutoutFix: failed hook parser", it) }
    }

    private fun hookDisplayGetCutout() {
        runCatching {
            val getCutoutMethod = Display::class.java.method("getCutout")
            hook(getCutoutMethod, Hooker { chain ->
                if (!isFullscreenEnabled()) return@Hooker chain.proceed()
                getZeroCutout() ?: chain.proceed()
            })
        }.onFailure { log("CutoutFix: failed hook Display.getCutout", it) }
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
