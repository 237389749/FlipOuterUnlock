package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Lower the density for com.miui.home (inner screen launcher) when it runs
 * on the outer screen, so the launcher renders its full grid layout instead
 * of the compact one.
 *
 * Outer screen: 1208x1392 @ 520dpi → ~371dp × ~428dp
 * With density lowered to 320dpi: ~604dp — crosses the 600dp threshold
 * that triggers the full (non-tiny) launcher grid.
 *
 * Only modifies Resources directly — no Dependency on DensityUtil pipeline.
 */
object LauncherDensityHook : BaseHook() {
    override val targetPackages = listOf("com.miui.home")

    // Target ~520→320: gives ~604dp on 1208px width
    private const val TARGET_DENSITY_DPI = 320

    private var densityApplied = false

    override fun setupHooks(param: PackageReadyParam) {
        safeHook("LauncherDensityHook") {
            hookDensity(param)
        }
    }

    private fun hookDensity(param: PackageReadyParam) {
        runCatching {
            // Hook DeviceConfig.loadDensity — this is the earliest point
            // where the launcher reads and caches display density.
            // We modify Resources directly (no DensityUtil pipeline dependency).
            val deviceConfigClass = param.classLoader.loadClass(
                "com.miui.home.launcher.DeviceConfig"
            )
            val loadDensityMethod = deviceConfigClass.method(
                "loadDensity", android.content.Context::class.java
            )

            hook(loadDensityMethod, before { chain ->
                if (densityApplied) return@before
                val context = chain.args[0] as? android.content.Context
                    ?: return@before
                val resources = context.resources
                val metrics = resources.displayMetrics

                // Skip inner screen (2912px height)
                if (metrics.heightPixels > 1500) return@before
                if (metrics.densityDpi <= TARGET_DENSITY_DPI) return@before

                val oldDpi = metrics.densityDpi
                val targetDensity = TARGET_DENSITY_DPI / 160.0f
                val fontScale = resources.configuration.fontScale

                metrics.density = targetDensity
                metrics.densityDpi = TARGET_DENSITY_DPI
                metrics.scaledDensity = targetDensity * fontScale
                resources.configuration.densityDpi = TARGET_DENSITY_DPI

                densityApplied = true
                log("LauncherDensity: densityDpi $oldDpi→$TARGET_DENSITY_DPI (before loadDensity)")
            })
            log("LauncherDensity: hooked DeviceConfig.loadDensity (before)")
        }.onFailure { log("LauncherDensity: failed to hook", it) }
    }
}
