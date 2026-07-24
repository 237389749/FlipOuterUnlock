package com.example.flipunlock.hook

import android.view.Display
import android.view.DisplayCutout
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Global cutout zeroing — runs in ALL processes.
 *
 * Unlike CutoutHook (which targets only SystemUI/AOD/Camera), this ensures
 * EVERY app on the outer screen sees no display cutout. This fixes app-level
 * toast/snackbar/dialog positioning that was still shifted by cutout insets
 * delivered through WindowInsets even though Display.getCutout() was zeroed
 * in specific processes only.
 */
object GlobalCutoutHook : BaseHook() {
    override val targetPackages = listOf("*")

    override fun hook(param: PackageReadyParam) {
        if (!Config.displayCutout) return
        val pkg = param.packageName
        safeHook("GlobalCutout") {
            hookDisplayGetCutout(pkg)
            hookWindowInsetsGetCutout(pkg)
            hookFlipFoldedCutoutStub(param)
            hookDisplayMetricsDiag(param)
        }
    }

    /**
     * DisplayCutoutStubImpl.isFlipFolded() → false.
     */
    private fun hookFlipFoldedCutoutStub(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("android.view.DisplayCutoutStubImpl")
            val method = cls.getDeclaredMethod("isFlipFolded")
            method.isAccessible = true
            hook(method, replaceResult(false))
            log("GlobalCutout: DisplayCutoutStubImpl.isFlipFolded → false")
        }.onFailure { log("GlobalCutout: isFlipFolded failed", it) }
    }

    /**
     * Diagnostic: log DisplayMetrics and cutout from WindowManager for SystemUI.
     */
    private fun hookDisplayMetricsDiag(param: PackageReadyParam) {
        if (param.packageName != "com.android.systemui") return
        runCatching {
            val wm = android.view.WindowManager::class.java
            val method = wm.getDeclaredMethod("getCurrentWindowMetrics")
            method.isAccessible = true
            var done = false
            hook(method) { chain ->
                val result = chain.proceed()
                if (!done) {
                    done = true
                    val bounds = result?.javaClass?.getDeclaredMethod("getBounds")?.invoke(result) as? android.graphics.Rect
                    log("GlobalCutout DIAG: WindowMetrics bounds=$bounds")
                    val dm = android.content.res.Resources.getSystem().displayMetrics
                    log("GlobalCutout DIAG: system DisplayMetrics=${dm.widthPixels}x${dm.heightPixels} density=${dm.density}")
                }
                result
            }
        }.onFailure { log("GlobalCutout DIAG failed", it) }
    }

    private fun hookDisplayGetCutout(pkg: String) {
        runCatching {
            val method = Display::class.java.method("getCutout")
            val zero = getZeroCutout() ?: return
            hook(method) { zero }
            log("GlobalCutout: Display.getCutout → NO_CUTOUT for $pkg")
        }.onFailure { log("GlobalCutout: Display.getCutout failed", it) }
    }

    private fun hookWindowInsetsGetCutout(pkg: String) {
        runCatching {
            val method = android.view.WindowInsets::class.java.getDeclaredMethod("getDisplayCutout")
            method.isAccessible = true
            hook(method, replaceResult(null))
            log("GlobalCutout: WindowInsets.getDisplayCutout → null for $pkg")
        }.onFailure { log("GlobalCutout: WindowInsets.getDisplayCutout failed", it) }
    }

    private fun getZeroCutout(): DisplayCutout? {
        runCatching {
            val dcClass = DisplayCutout::class.java
            val constructor = dcClass.declaredConstructors.minByOrNull { it.parameterCount }
                ?: return null
            constructor.isAccessible = true
            val args = constructor.parameterTypes.map { type ->
                when (type) {
                    android.graphics.Insets::class.java -> android.graphics.Insets.of(0, 0, 0, 0)
                    android.graphics.Rect::class.java -> android.graphics.Rect(0, 0, 0, 0)
                    android.graphics.Path::class.java -> android.graphics.Path()
                    Int::class.javaPrimitiveType, Integer::class.java -> 0
                    Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
                    java.util.List::class.java -> java.util.Collections.emptyList<Any>()
                    else -> null
                }
            }.toTypedArray()
            return constructor.newInstance(*args) as DisplayCutout
        }.onFailure { log("GlobalCutout: construct zero cutout failed", it) }
        return null
    }
}
