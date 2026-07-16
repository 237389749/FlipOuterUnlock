package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Enable Always-On Display on the outer screen when folded.
 *
 * AOD classes (com.miui.aod.*) are in MIUIAod.apk, NOT MiuiSystemUI.apk.
 * They're loaded by a separate classloader inaccessible from setupHooks().
 *
 * Strategy: two-layer approach
 *   Layer 1 (at SystemUI load): hook framework DreamService methods.
 *   Layer 2 (at dream start): walk object graph from DreamService →
 *     find DozeMachine instance → use ITS classloader to hook
 *     DozeMachine.requestState() + force DOZE_AOD.
 */
object AodHook : BaseHook() {
    override val targetPackages = listOf("com.android.systemui", "com.miui.aod")

    // Track whether runtime hooks have been installed (once per process)
    @Volatile private var runtimeHooksInstalled = false

    override fun setupHooks(param: PackageReadyParam) {
        val pkg = param.packageName
        val cl = param.classLoader
        log("AodHook: setupHooks pkg=$pkg")

        // Layer 1: framework hooks — installed immediately
        hookFrameworkDreamService(cl, pkg)
    }

    // ── Layer 1: Framework DreamService hooks ────────────────────────────

    private fun hookFrameworkDreamService(cl: ClassLoader, pkg: String) {
        // 1a. setDozeScreenState(int) — block OFF/SUSPEND, force DOZE(2)
        runCatching {
            val method = android.service.dreams.DreamService::class.java
                .getDeclaredMethod("setDozeScreenState", Int::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method) { chain ->
                val state = chain.args[0] as? Int ?: return@hook chain.proceed()
                log("AodHook/L1: setDozeScreenState($state)")
                if (state == 1 || state == 4) {
                    log("AodHook/L1: BLOCKED $state → 2")
                    chain.args[0] = 2
                }
                chain.proceed()
            }
            log("AodHook: ✓ L1 setDozeScreenState hooked")
        }.onFailure { log("AodHook: L1 setDozeScreenState failed", it) }

        // 1b. onDreamingStarted() — entry point for Layer 2
        runCatching {
            val method = android.service.dreams.DreamService::class.java
                .getDeclaredMethod("onDreamingStarted")
            method.isAccessible = true
            hook(method, after { chain, result ->
                if (runtimeHooksInstalled) return@after result
                log("AodHook/L1: onDreamingStarted — triggering Layer 2...")
                installRuntimeHooks(chain.thisObject)
                result
            })
            log("AodHook: ✓ L1 onDreamingStarted hooked")
        }.onFailure { log("AodHook: L1 onDreamingStarted failed", it) }
    }

    // ── Layer 2: Runtime hooks — installed when dream starts ────────────

    private fun installRuntimeHooks(dreamService: Any?) {
        if (dreamService == null || runtimeHooksInstalled) return
        runtimeHooksInstalled = true

        try {
            // Walk object graph to find DozeMachine instance
            val machine = findObjectByClassName(dreamService, "com.miui.aod.doze.DozeMachine")
            if (machine == null) {
                log("AodHook/L2: DozeMachine not found in dreamService fields")
                return
            }
            log("AodHook/L2: found DozeMachine! cl=${machine.javaClass.classLoader.javaClass.simpleName}")

            // Use the machine's classloader — it HAS the AOD classes!
            val machineCl = machine.javaClass.classLoader
            val stateClass = machineCl.loadClass("com.miui.aod.doze.DozeMachine\$State")
            val values = stateClass.getMethod("values").invoke(null) as Array<*>
            val dozeAod = values.first { it.toString() == "DOZE_AOD" }

            // Immediately force DOZE_AOD
            machine.callMethod("requestState", dozeAod)
            log("AodHook/L2: forced requestState(DOZE_AOD)")

            // Hook DozeMachine.requestState() for future transitions
            runCatching {
                val reqMethod = machine.javaClass.getDeclaredMethod("requestState", stateClass)
                reqMethod.isAccessible = true
                hook(reqMethod) { chain ->
                    val state = chain.args[0]
                    val stateName = state?.toString() ?: "null"
                    log("AodHook/L2: DozeMachine.requestState($stateName)")
                    when (stateName) {
                        "DOZE", "DOZE_SUSPEND" -> {
                            log("AodHook/L2: REDIRECT $stateName → DOZE_AOD")
                            chain.args[0] = dozeAod
                        }
                    }
                    chain.proceed()
                }
                log("AodHook: ✓ L2 DozeMachine.requestState hooked (runtime)")
            }.onFailure { log("AodHook: L2 DozeMachine.requestState hook failed", it) }

            // Also find DozeService and hook setDozeScreenState
            val dozeService = findObjectByClassName(dreamService, "com.miui.aod.doze.DozeService")
            if (dozeService != null) {
                runCatching {
                    val svcClass = dozeService.javaClass
                    val method = svcClass.getDeclaredMethod("setDozeScreenState",
                        Int::class.javaPrimitiveType!!)
                    method.isAccessible = true
                    hook(method) { chain ->
                        val s = chain.args[0] as? Int ?: return@hook chain.proceed()
                        log("AodHook/L2: DozeService.setDozeScreenState($s)")
                        if (s == 1 || s == 4) {
                            log("AodHook/L2: BLOCKED $s → 2")
                            chain.args[0] = 2
                        }
                        chain.proceed()
                    }
                    log("AodHook: ✓ L2 DozeService.setDozeScreenState hooked (runtime)")
                }.onFailure { log("AodHook: L2 DozeService.setDozeScreenState failed", it) }
            }

            // Find DozeHost and hook isFullAod
            val dozeHost = findObjectByClassName(dreamService, "com.miui.aod.DozeHost")
            if (dozeHost != null) {
                runCatching {
                    val method = dozeHost.javaClass.getDeclaredMethod("isFullAod")
                    method.isAccessible = true
                    hook(method, replaceResult(false))
                    log("AodHook: ✓ L2 DozeHost.isFullAod → false (runtime)")
                }.onFailure { log("AodHook: L2 DozeHost.isFullAod failed", it) }
            }

        } catch (e: Exception) {
            log("AodHook/L2: installRuntimeHooks failed", e)
        }
    }

    // ── Object graph traversal ──────────────────────────────────────────

    /**
     * Walk the object graph from [root] looking for an instance whose class
     * name matches [className]. Uses visited set to avoid cycles. Max depth 5.
     */
    private fun findObjectByClassName(root: Any?, className: String): Any? {
        return findRecursive(root, className, mutableSetOf(), 0)
    }

    private fun findRecursive(
        obj: Any?, target: String, visited: MutableSet<Int>, depth: Int
    ): Any? {
        if (obj == null || depth > 5) return null
        val id = System.identityHashCode(obj)
        if (!visited.add(id)) return null  // already visited

        if (obj.javaClass.name == target) return obj

        for (field in obj.javaClass.declaredFields) {
            runCatching {
                field.isAccessible = true
                val value = field.get(obj) ?: return@runCatching
                // Skip primitives, strings, common JDK classes
                val fc = value.javaClass
                if (fc.isPrimitive || fc.name.startsWith("java.") ||
                    fc.name.startsWith("android.") && !fc.name.contains("aod") &&
                    !fc.name.contains("doze")) return@runCatching
                findRecursive(value, target, visited, depth + 1)?.let { return it }
            }
        }
        return null
    }
}
