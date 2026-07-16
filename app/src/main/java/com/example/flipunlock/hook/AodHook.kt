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
 * AOD classes (com.miui.aod.*) live in miuisystem.apk, NOT MiuiSystemUI.apk.
 * miuisystem.apk is loaded by a parent classloader — we walk up the hierarchy
 * with findClassUp() to locate classes regardless of which classloader owns them.
 */
object AodHook : BaseHook() {
    override val targetPackages = listOf("com.android.systemui", "com.miui.aod")

    override fun setupHooks(param: PackageReadyParam) {
        val pkg = param.packageName
        val cl = param.classLoader
        log("AodHook: setupHooks pkg=$pkg cl=${cl.javaClass.name}")

        // ── Framework-level hooks — ALWAYS install, no AOD classes needed ──
        // AOD code ultimately calls DreamService.setDozeScreenState() through
        // DreamServiceUtils. Hooking the framework DreamService bypasses the
        // classloader problem entirely.
        hookFrameworkDreamService(cl, pkg)

        // Quick check: can we find any AOD class at all?
        if (cl.findClassUp("com.miui.aod.doze.DozeMachine") == null) {
            log("AodHook: no AOD classes reachable from $pkg classloader — skipping")
            log("AodHook: cl chain: ${classLoaderChain(cl)}")
            return
        }
        log("AodHook: AOD classes found! Proceeding...")

        // ── §1. DozeMachine.requestState(State) — THE state machine ──────
        runCatching {
            val machineClass = cl.findClassUp("com.miui.aod.doze.DozeMachine")!!
            val stateClass = cl.findClassUp("com.miui.aod.doze.DozeMachine\$State")!!
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
        }.onFailure { log("AodHook: DozeMachine.requestState failed", it) }

        // ── §2. DozeService.dreamingStarted(ViewGroup) — entry point ─────
        runCatching {
            val svcClass = cl.findClassUp("com.miui.aod.doze.DozeService")!!
            val stateClass = cl.findClassUp("com.miui.aod.doze.DozeMachine\$State")!!
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
        }.onFailure { log("AodHook: DozeService.dreamingStarted failed", it) }

        // ── §3. DozeService.setDozeScreenState(int) — block OFF/SUSPEND ──
        runCatching {
            val svcClass = cl.findClassUp("com.miui.aod.doze.DozeService")!!
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
        }.onFailure { log("AodHook: DozeService.setDozeScreenState failed", it) }

        // ── §4. Utils hooks — app-side gates ────────────────────────────
        runCatching {
            val utilsClass = cl.findClassUp("com.miui.aod.Utils")!!

            runCatching {
                val method = utilsClass.method("isFlipDevice")
                hook(method, replaceResult(true))
                log("AodHook: ✓ isFlipDevice → true")
            }.onFailure { log("AodHook: isFlipDevice failed", it) }

            runCatching {
                val method = utilsClass.method("isAodEnable", android.content.Context::class.java)
                hook(method) { chain ->
                    val ctx = chain.args[0] as? android.content.Context
                    val metrics = ctx?.resources?.displayMetrics
                    if (metrics != null && metrics.heightPixels in 1000..1500) {
                        log("AodHook: isAodEnable → true (outer ${metrics.heightPixels}px)")
                        true
                    } else {
                        chain.proceed()
                    }
                }
                log("AodHook: ✓ isAodEnable hooked")
            }.onFailure { log("AodHook: isAodEnable failed", it) }

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

        }.onFailure { log("AodHook: Utils not found", it) }

        // ── §5. DozeHost.isFullAod() → false ────────────────────────────
        runCatching {
            val dozeHostClass = cl.findClassUp("com.miui.aod.DozeHost")!!
            val method = dozeHostClass.method("isFullAod")
            hook(method, replaceResult(false))
            log("AodHook: ✓ DozeHost.isFullAod → false")
        }.onFailure { log("AodHook: DozeHost.isFullAod failed", it) }

        // ── §6. AODSettings.needKeepScreenOnAtFirst() → false ────────────
        runCatching {
            val cls = cl.findClassUp("com.miui.aod.widget.AODSettings")!!
            val method = cls.method("needKeepScreenOnAtFirst")
            hook(method, replaceResult(false))
            log("AodHook: ✓ needKeepScreenOnAtFirst → false")
        }.onFailure { log("AodHook: AODSettings.needKeepScreenOnAtFirst failed", it) }

        log("AodHook: setupHooks done for $pkg")
    }

    // ── Framework DreamService hooks — reachable from any classloader ────
    // AOD's DozeService → DreamServiceUtils → DreamService.setDozeScreenState()
    // This is the final common path for all doze screen state changes.
    // Hook it directly to block OFF(1)/DOZE_SUSPEND(4) → force DOZE(2).
    private fun hookFrameworkDreamService(cl: ClassLoader, pkg: String) {
        runCatching {
            val dsClass = cl.loadClass("android.service.dreams.DreamService")
            val method = dsClass.getDeclaredMethod("setDozeScreenState",
                Int::class.javaPrimitiveType!!)
            method.isAccessible = true

            hook(method) { chain ->
                val state = chain.args[0] as? Int ?: return@hook chain.proceed()
                log("AodHook/DreamService: setDozeScreenState($state) caller=${pkg}")
                if (state == 1 || state == 4) {
                    log("AodHook/DreamService: BLOCKED state $state → forcing 2 (DOZE)")
                    chain.args[0] = 2  // DOZE = screen dim but ON
                }
                chain.proceed()
            }
            log("AodHook: ✓ DreamService.setDozeScreenState hooked (framework)")
        }.onFailure { log("AodHook: DreamService.setDozeScreenState failed", it) }
    }

    /** Debug: dump classloader chain + DexPathList entries */
    private fun classLoaderChain(loader: ClassLoader?): String {
        val sb = StringBuilder()
        var cl = loader
        var depth = 0
        while (cl != null && depth < 10) {
            if (sb.isNotEmpty()) sb.append(" → ")
            sb.append("[$depth] ${cl.javaClass.simpleName}")
            // Dump DexPathList for BaseDexClassLoader subclasses
            try {
                val pathListField = cl.javaClass.superclass?.getDeclaredField("pathList")
                if (pathListField != null) {
                    pathListField.isAccessible = true
                    val pathList = pathListField.get(cl)
                    val dexElementsField = pathList?.javaClass?.getDeclaredField("dexElements")
                    dexElementsField?.isAccessible = true
                    val elements = dexElementsField?.get(pathList) as? Array<*>
                    if (elements != null) {
                        sb.append("(dex=${elements.size}")
                        for (e in elements.take(3)) {
                            try {
                                val f = e?.javaClass?.getDeclaredField("path")
                                f?.isAccessible = true
                                val path = f?.get(e)?.toString()?.substringAfterLast("/")
                                if (path != null) sb.append(" $path")
                            } catch (_: Exception) {}
                        }
                        if (elements.size > 3) sb.append("...")
                        sb.append(")")
                    }
                }
            } catch (_: Exception) {}
            cl = cl.parent
            depth++
        }
        return sb.toString()
    }
}
