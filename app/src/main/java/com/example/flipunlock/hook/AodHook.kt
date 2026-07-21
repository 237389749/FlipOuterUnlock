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
        if (!Config.displayAod) { log("AodHook: DISABLED by persist.flipunlock.display.aod"); return }
        val pkg = param.packageName
        val cl = param.classLoader
        log("AodHook: setupHooks pkg=$pkg")

        // Layer 1: framework hooks — installed immediately
        hookFrameworkDreamService(cl, pkg)
    }

    // ── Layer 1: Framework DreamService hooks ────────────────────────────

    private fun hookFrameworkDreamService(cl: ClassLoader, pkg: String) {
        // 1a. setDozeScreenState(int) — force screen ON, prevent OFF.
        // DozeScreenState has a 6s mResetScreenTask timeout that calls
        // setDozeScreenState(1) after INITIALIZED→DOZE_AOD. Block ALL
        // OFF-related states (0,1,3) and force to 2 (ON). State 4 (AOD ON)
        // is also redirected to 2 to avoid the reset timeout oscillation.
        //
        // Values: 0=FINISH, 1=DOZE, 2=ON/PULSING, 3=DOZE_SUSPEND, 4=DOZE_AOD
        runCatching {
            val method = android.service.dreams.DreamService::class.java
                .getDeclaredMethod("setDozeScreenState", Int::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method) { chain ->
                val state = chain.args[0] as? Int ?: return@hook chain.proceed()
                when (state) {
                    0, 1, 3, 4 -> {
                        log("AodHook/L1: BLOCKED setDozeScreenState($state) → 2 (ON)")
                        chain.args[0] = 2
                    }
                    // 2 (ON) passes through
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
            val machineCl = machine.javaClass.classLoader ?: run { log("AodHook/L2: classLoader is null"); return }
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
                        "DOZE", "DOZE_SUSPEND", "FINISH" -> {
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
                        when (s) {
                            0, 1, 3, 4 -> {
                                log("AodHook/L2: BLOCKED DozeService.setDozeScreenState($s) → 2 (ON)")
                                chain.args[0] = 2
                            }
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

            // Hook FlipLinkageStyleController to prevent AOD kill switch.
            //
            // DozeMachine.resolveIntermediateState() (lines 396-401):
            //   if (flip.isUsingFlip(mContext) || !flip.isFlipped()) return;  // AOD survives
            //   transitionTo(State.FINISH);  // AOD killed!
            //
            // With DeviceIdentityHook making isFlipDevice()→false, isUsingFlip() always
            // returns false. We need isFlipped()→false so !isFlipped()=true keeps AOD alive.
            // We also force isUsingFlip()→true so flip-specific AOD clock style works.
            runCatching {
                val flipCtrlClass = machineCl.loadClass("com.miui.aod.flip.FlipLinkageStyleController")
                val instanceField = flipCtrlClass.getDeclaredField("INSTANCE")
                instanceField.isAccessible = true
                val flipCtrl = instanceField.get(null)
                if (flipCtrl != null) {
                    // Hook isFlipped() → always false (prevents kill switch)
                    runCatching {
                        val isFlippedMethod = flipCtrlClass.getDeclaredMethod("isFlipped")
                        isFlippedMethod.isAccessible = true
                        hook(isFlippedMethod, replaceResult(false))
                        log("AodHook: ✓ L2 FlipLinkageStyleController.isFlipped → false (runtime)")
                    }.onFailure { log("AodHook: L2 isFlipped hook failed", it) }

                    // Hook isUsingFlip() → always true (enables flip AOD clock style)
                    runCatching {
                        val isUsingFlipMethod = flipCtrlClass.getDeclaredMethod("isUsingFlip",
                            android.content.Context::class.java)
                        isUsingFlipMethod.isAccessible = true
                        hook(isUsingFlipMethod, replaceResult(true))
                        log("AodHook: ✓ L2 FlipLinkageStyleController.isUsingFlip → true (runtime)")
                    }.onFailure { log("AodHook: L2 isUsingFlip hook failed", it) }
                }
            }.onFailure { log("AodHook: L2 FlipLinkageStyleController not found", it) }

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
