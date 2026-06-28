package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Lower the density for com.miui.home (inner screen launcher) when it runs
 * on the outer screen, so the launcher renders its full tablet-like layout.
 *
 * Mechanism: calls MIUI's own DensityUtil.doChangeDensity() with a
 * hand-built DensityConfig that lowers densityDpi from 520 to 320.
 * This increases dp width from ~371 to ~604, triggering the launcher's
 * full grid layout. Only affects com.miui.home's Resources.
 *
 * Why 320? Target dp = px / (density/160) = 1208 / (320/160) = 604
 * which exceeds the 600dp "large screen" threshold used by MiuiHome.
 */
object LauncherDensityHook : BaseHook() {
    override val targetPackages = listOf("com.miui.home")

    // Target densityDpi: 320 gives ~604dp on the 1208px outer screen
    private const val TARGET_DENSITY_DPI = 320

    private var densityApplied = false

    override fun setupHooks(param: PackageReadyParam) {
        safeHook("LauncherDensityHook") {
            hookDensity(param)
        }
    }

    private fun hookDensity(param: PackageReadyParam) {
        runCatching {
            // Hook DeviceConfig.loadDensity — this is where miui.home reads
            // and caches the display density. Apply our custom density
            // immediately after it runs.
            val deviceConfigClass = param.classLoader.loadClass(
                "com.miui.home.launcher.DeviceConfig"
            )
            val loadDensityMethod = deviceConfigClass.method(
                "loadDensity", android.content.Context::class.java
            )

            hook(loadDensityMethod, after { chain, result ->
                if (densityApplied) return@after result
                runCatching {
                    val context = chain.args[0] as? android.content.Context
                        ?: return@runCatching
                    val resources = context.resources
                    val metrics = resources.displayMetrics

                    // Only apply on outer screen (1392px height), not inner (2912px)
                    if (metrics.heightPixels > 1500) return@runCatching

                    if (metrics.densityDpi <= TARGET_DENSITY_DPI) return@runCatching

                    // Build DensityConfig with target density
                    val densityUtilClass = param.classLoader.loadClass(
                        "miuix.autodensity.DensityUtil"
                    )
                    val densityConfigClass = param.classLoader.loadClass(
                        "miuix.autodensity.DensityConfig"
                    )
                    // DensityConfig(Configuration) constructor
                    val config = densityConfigClass.getConstructor(
                        android.content.res.Configuration::class.java
                    ).newInstance(resources.configuration)

                    // Set target density fields
                    val density = TARGET_DENSITY_DPI / 160.0f
                    config.setField("densityDpi", TARGET_DENSITY_DPI)
                    config.setField("density", density)
                    config.setField("scaledDensity", density * resources.configuration.fontScale)
                    config.setField("defaultBitmapDensity", TARGET_DENSITY_DPI)

                    // Apply via MIUI's own public API
                    val doChangeDensity = densityUtilClass.method(
                        "doChangeDensity",
                        param.classLoader.loadClass("miuix.view.DisplayConfig"),
                        android.content.res.Resources::class.java,
                        Int::class.java
                    )
                    doChangeDensity.invoke(null, config, resources, 0)

                    densityApplied = true
                    log("LauncherDensity: set densityDpi ${metrics.densityDpi}→$TARGET_DENSITY_DPI")
                }.onFailure { log("LauncherDensity: failed to apply", it) }
                result
            })
            log("LauncherDensity: hooked DeviceConfig.loadDensity")
        }.onFailure { log("LauncherDensity: failed to hook", it) }
    }
}
