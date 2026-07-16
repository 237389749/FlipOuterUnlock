package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Enable Always-On Display on the outer screen when folded.
 *
 * Strategy:
 *   1. Hook DozeMachine.requestState(State) — intercept ALL transitions, log + redirect
 *   2. Hook DozeService.dreamingStarted() — force DOZE_AOD right after init
 *   3. Hook DozeService.setDozeScreenState(int) — block screen OFF/SUSPEND
 *   4. App-side gates (Utils, DozeHost, AODSettings) — override flip/AOD checks
 *
 * Target "*" — AOD classes (com.miui.aod.*) live in miuisystemui.apk,
 * loaded by com.android.systemui process. Targeting "*"" ensures hooks fire.
 */
object AodHook : BaseHook() {
    override val targetPackages = listOf("com.android.systemui", "com.miui.aod")

    override fun setupHooks(param: PackageReadyParam) {
        val pkg = param.packageName
        log("AodHook: setupHooks pkg=$pkg")

        // ── §1. DozeMachine.requestState(State) — THE state machine ──────
        // Intercept every state transition. If anything tries DOZE or
        // DOZE_SUSPEND, redirect to DOZE_AOD so the screen stays on.
        runCatching {
            val machineClass = param.classLoader.loadClass("com.miui.aod.doze.DozeMachine")
            val stateClass = param.classLoader.loadClass("com.miui.aod.doze.DozeMachine\$State")
            val method = machineClass.getDeclaredMethod("requestState", stateClass)
            method.isAccessible = true

            hook(method) { chain ->
                val state = chain.args[0]
                val stateName = state?.toString() ?: "null"
                log("AodHook/DozeMachine: requestState($stateName)")

                when (stateName) {
                    "DOZE", "DOZE_SUSPEND" -> {
                        val values = stateClass.getMethod("values").invoke(null) as Array<*>
                        val dozeAod = values.first { it.toString() == "DOZE_AOD" }
                        log("AodHook/DozeMachine: REDIRECT $stateName → DOZE_AOD")
                        chain.args[0] = dozeAod
                    }
                }
                chain.proceed()
            }
            log("AodHook: ✓ DozeMachine.requestState hooked")
        }.onFailure { log("AodHook: DozeMachine.requestState not found in $pkg", it) }

        // ── §2. DozeService.dreamingStarted(ViewGroup) — entry point ─────
        // After the dream starts and calls requestState(INITIALIZED),
        // immediately force requestState(DOZE_AOD) to jump-start AOD.
        runCatching {
            val svcClass = param.classLoader.loadClass("com.miui.aod.doze.DozeService")
            val stateClass = param.classLoader.loadClass("com.miui.aod.doze.DozeMachine\$State")
            val method = svcClass.getDeclaredMethod("dreamingStarted",
                android.view.ViewGroup::class.java)
            method.isAccessible = true

            hook(method, after { chain, _ ->
                log("AodHook/DozeService: dreamingStarted called, forcing DOZE_AOD")
                try {
                    val dozeMachine = chain.thisObject.getField("mDozeMachine")
                    if (dozeMachine != null) {
                        val values = stateClass.getMethod("values").invoke(null) as Array<*>
                        val dozeAod = values.first { it.toString() == "DOZE_AOD" }
                        dozeMachine.callMethod("requestState", dozeAod)
                        log("AodHook/DozeService: forced requestState(DOZE_AOD)")
                    } else {
                        log("AodHook/DozeService: mDozeMachine is null, cannot force")
                    }
                } catch (e: Exception) {
                    log("AodHook/DozeService: force DOZE_AOD failed", e)
                }
            })
            log("AodHook: ✓ DozeService.dreamingStarted hooked")
        }.onFailure { log("AodHook: DozeService.dreamingStarted not found in $pkg", it) }

        // ── §3. DozeService.setDozeScreenState(int) — block OFF/SUSPEND ──
        // Screen state values: 1=OFF, 2=DOZE(dim but on), 3/4=SUSPEND
        // Block 1 (OFF) and 4 (SUSPEND). Force state 2 (DOZE) instead.
        runCatching {
            val svcClass = param.classLoader.loadClass("com.miui.aod.doze.DozeService")
            val method = svcClass.getDeclaredMethod("setDozeScreenState",
                Int::class.javaPrimitiveType!!)
            method.isAccessible = true

            hook(method) { chain ->
                val state = chain.args[0] as? Int ?: return@hook chain.proceed()
                log("AodHook/DozeService: setDozeScreenState($state)")
                if (state == 1 || state == 4) {
                    log("AodHook/DozeService: BLOCKED screen state $state → forcing 2 (DOZE)")
                    chain.args[0] = 2  // DOZE = dim but screen ON
                }
                chain.proceed()
            }
            log("AodHook: ✓ DozeService.setDozeScreenState hooked")
        }.onFailure { log("AodHook: DozeService.setDozeScreenState not found in $pkg", it) }

        // ── §4. Utils hooks — app-side gates ────────────────────────────
        runCatching {
            val utilsClass = param.classLoader.loadClass("com.miui.aod.Utils")
            log("AodHook: Utils class found in $pkg")

            // 4a. isFlipDevice() → true (reverses DeviceIdentityHook for AOD)
            runCatching {
                val method = utilsClass.method("isFlipDevice")
                hook(method, replaceResult(true))
                log("AodHook: ✓ isFlipDevice → true")
            }.onFailure { log("AodHook: isFlipDevice failed", it) }

            // 4b. isAodEnable(Context) → force true on outer screen
            runCatching {
                val method = utilsClass.method("isAodEnable", android.content.Context::class.java)
                hook(method) { chain ->
                    val ctx = chain.args[0] as? android.content.Context
                    val metrics = ctx?.resources?.displayMetrics
                    val result = if (metrics != null && metrics.heightPixels in 1000..1500) {
                        log("AodHook: isAodEnable → true (outer ${metrics.heightPixels}px)")
                        true
                    } else {
                        chain.proceed()
                    }
                    result
                }
                log("AodHook: ✓ isAodEnable hooked")
            }.onFailure { log("AodHook: isAodEnable failed", it) }

            // 4c. isFolded(Context) → force true on outer screen
            runCatching {
                val method = utilsClass.method("isFolded", android.content.Context::class.java)
                hook(method) { chain ->
                    val ctx = chain.args[0] as? android.content.Context
                    val metrics = ctx?.resources?.displayMetrics
                    if (metrics != null && metrics.heightPixels in 1000..1500) {
                        log("AodHook: isFolded → true (outer screen)")
                        true
                    } else {
                        chain.proceed()
                    }
                }
                log("AodHook: ✓ isFolded hooked")
            }.onFailure { log("AodHook: isFolded failed", it) }

            // 4d. getShowStyle(Context) → force Always-on (2) on outer screen
            runCatching {
                val method = utilsClass.method("getShowStyle", android.content.Context::class.java)
                hook(method) { chain ->
                    val result = (chain.proceed() as? Int) ?: 0
                    val ctx = chain.args[0] as? android.content.Context
                    val metrics = ctx?.resources?.displayMetrics
                    if (metrics != null && metrics.heightPixels in 1000..1500 && result != 2) {
                        log("AodHook: getShowStyle $result → 2 (outer screen)")
                        2
                    } else {
                        result
                    }
                }
                log("AodHook: ✓ getShowStyle hooked")
            }.onFailure { log("AodHook: getShowStyle failed", it) }

        }.onFailure { log("AodHook: Utils class not found in $pkg", it) }

        // ── §5. DozeHost.isFullAod() → false ────────────────────────────
        // When true, prepareAodViewAndShow() removes clock container → black screen
        runCatching {
            val dozeHostClass = param.classLoader.loadClass("com.miui.aod.DozeHost")
            val method = dozeHostClass.method("isFullAod")
            hook(method, replaceResult(false))
            log("AodHook: ✓ DozeHost.isFullAod → false")
        }.onFailure { log("AodHook: DozeHost.isFullAod not found in $pkg", it) }

        // ── §6. AODSettings.needKeepScreenOnAtFirst() → false ────────────
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.aod.widget.AODSettings")
            val method = cls.method("needKeepScreenOnAtFirst")
            hook(method, replaceResult(false))
            log("AodHook: ✓ needKeepScreenOnAtFirst → false")
        }.onFailure { log("AodHook: AODSettings.needKeepScreenOnAtFirst not found in $pkg", it) }

        log("AodHook: setupHooks done for $pkg")
    }
}
