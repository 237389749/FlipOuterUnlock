package com.example.flipunlock.hook.system

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Split device state: display layer sees CLOSED (outer screen on),
 * app layer sees OPENED (all flip restrictions gone).
 *
 * From decompiled LogicalDisplayMapper (services.jar):
 *   DeviceStateManager callback → setDeviceStateLocked(state)
 *     → applyLayoutLocked()
 *       → DeviceStateToLayoutMap.get(state)  ← reads display_layout_config.xml mapping
 *         → setEnabledLocked(display, enabled)
 *
 * From decompiled ContinuityPolicyService:
 *   FoldStateListener → onDeviceStateChanged(boolean folded)
 *     → controls app continuity/intercept restrictions
 *
 * Three independent hooks — no XML modification needed.
 */
object DisplayStateHook {

    /**
     * Detect whether we're on the outer (folded) screen by checking display height.
     * Outer screen: ~1392px max. Inner screen: ~2912px max. Threshold: 2000px.
     * Only apply display state forcing when on the outer screen — when unfolded
     * with the inner screen intact, the native display topology works correctly.
     */
    private fun isOuterScreen(): Boolean {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        return Math.max(dm.widthPixels, dm.heightPixels) < 2000
    }

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayDual) { log("DisplayStateHook: DISABLED by persist.flipunlock.display.dual"); return }
        log("DisplayStateHook: setting up")
        safeHook("DisplayStateHook") {
            hookDisplayToClosed(param)
            hookDisplayLayoutGet(param)
            hookAppLayerToUnfolded(param)
            hookDisplayInfoForStateToClosed(param)
            hookDisplayEnabledLocked(param)
            hookExternalDisplayDisable(param)
            hookAodOuterScreen(param)
        }
    }

    // ── 1. Display layer: always CLOSED → outer screen active ───────────
    // LogicalDisplayMapper.setDeviceStateLocked(DeviceState) reads
    // state.getIdentifier() to decide which display layout to apply.
    // DeviceState has a public constructor DeviceState(int).
    // We force state=0 (CLOSED) so the outer screen remains active.
    private fun hookDisplayToClosed(param: SystemServerStartingParam) {
        runCatching {
            val mapperClass = param.classLoader.loadClass(
                "com.android.server.display.LogicalDisplayMapper"
            )
            val deviceStateClass = param.classLoader.loadClass(
                "android.hardware.devicestate.DeviceState"
            )
            val method = mapperClass.method("setDeviceStateLocked", deviceStateClass)

            // DeviceState has public constructor DeviceState(int identifier)
            val closedStateConstructor = deviceStateClass.getDeclaredConstructor(
                java.lang.Integer.TYPE
            )
            val closedState = closedStateConstructor.newInstance(0)

            hook(method) { chain ->
                if (isOuterScreen()) {
                    chain.args[0] = closedState
                }
                chain.proceed()
            }
            log("DisplayState: forced LogicalDisplayMapper -> always CLOSED (outer screen)")
        }.onFailure { log("DisplayState: failed hook LogicalDisplayMapper", it) }
    }

    // ── 1b. DeviceStateToLayoutMap.get(int) — the choke point ─────────────
    // Always return state=6 (DUAL) layout — both screens ON, outer leads.
    // State 6: port=132 (outer) default, port=131 (inner) follows.
    // Any state → both screens active → no screen ever gets disabled.
    private fun hookDisplayLayoutGet(param: SystemServerStartingParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.server.display.DeviceStateToLayoutMap")
            val method = cls.getDeclaredMethod("get", Int::class.javaPrimitiveType!!)
            method.isAccessible = true

            hook(method) { chain ->
                if (!isOuterScreen()) return@hook chain.proceed()
                val state = chain.args[0] as? Int ?: return@hook chain.proceed()
                val layoutMap = chain.thisObject.getField("mLayoutMap")
                val dualLayout = (layoutMap as android.util.SparseArray<*>).get(6)
                if (state != 6) {
                    log("DisplayState/Layout: get($state) → forcing layout for state=6 (DUAL, outer leads)")
                }
                dualLayout ?: chain.proceed()
            }
            log("DisplayState: ✓ DeviceStateToLayoutMap.get hooked → state=6 when folded")
        }.onFailure { log("DisplayState: failed hook DeviceStateToLayoutMap.get", it) }
    }

    // ── 2. App layer: always unfolded → flip restrictions disabled ──────
    // ContinuityPolicyService.onDeviceStateChanged(boolean folded)
    // Force false = unfolded regardless of actual sensor.
    private fun hookAppLayerToUnfolded(param: SystemServerStartingParam) {
        runCatching {
            val cpsClass = param.classLoader.loadClass(
                "com.android.server.wm.ContinuityPolicyService"
            )
            val method = cpsClass.method(
                "onDeviceStateChanged", Boolean::class.javaPrimitiveType!!
            )
            hook(method) { chain ->
                if (isOuterScreen()) {
                    chain.args[0] = false  // force unfolded
                }
                chain.proceed()
            }
            log("DisplayState: forced ContinuityPolicyService.onDeviceStateChanged -> unfolded when folded")
        }.onFailure { log("DisplayState: failed hook ContinuityPolicyService", it) }
    }

    // ── 3. DisplayInfo query: always return CLOSED state info ─────────────
    // getDisplayInfoForStateLocked(int deviceState, int displayId)
    // Queries display info for a hypothetical state. SystemUI uses this
    // to pre-compute layouts before fold/unfold. Force state=0 so all
    // callers see outer screen layout regardless of queried state.
    private fun hookDisplayInfoForStateToClosed(param: SystemServerStartingParam) {
        runCatching {
            val mapperClass = param.classLoader.loadClass(
                "com.android.server.display.LogicalDisplayMapper"
            )
            val method = mapperClass.method(
                "getDisplayInfoForStateLocked",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            )
            hook(method) { chain ->
                if (isOuterScreen()) {
                    chain.args[0] = 0  // force deviceState=0 (CLOSED)
                }
                chain.proceed()
            }
            log("DisplayState: forced getDisplayInfoForStateLocked -> state=0 when folded")
        }.onFailure { log("DisplayState: failed hook getDisplayInfoForStateLocked", it) }
    }

    // ── 4. Defense-in-depth: force all displays enabled ──────────────────
    //    DeviceStateToLayoutMap.get() → state=6 already enables both displays
    //    via the layout. These hooks prevent any OTHER code path from disabling
    //    a display at a lower level.

    // 4a. LogicalDisplay.isEnabledLocked() → always true
    //     Prevents any caller from seeing a display as "disabled".
    //     Called by: ExternalDisplayPolicy, DisplayManagerService,
    //     LogicalDisplayMapper.setEnabledLocked, etc.
    private fun hookDisplayEnabledLocked(param: SystemServerStartingParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.server.display.LogicalDisplay")
            val method = cls.getDeclaredMethod("isEnabledLocked")
            method.isAccessible = true
            hook(method) { chain ->
                if (isOuterScreen()) {
                    true
                } else {
                    chain.proceed()
                }
            }
            log("DisplayState: isEnabledLocked → true when outer screen")
        }.onFailure { log("DisplayState: isEnabledLocked failed", it) }
    }

    // 4b. ExternalDisplayPolicy.disableExternalDisplayLocked() → no-op
    //     Explicitly blocks the external display disable policy.
    //     Original code checks isEnabledLocked + display type, then disables.
    private fun hookExternalDisplayDisable(param: SystemServerStartingParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.server.display.ExternalDisplayPolicy")
            val method = cls.getDeclaredMethod("disableExternalDisplayLocked",
                Int::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method) { chain ->
                if (isOuterScreen()) {
                    log("DisplayState: BLOCKED disableExternalDisplayLocked(${chain.args[0]})")
                    null
                } else {
                    chain.proceed()
                }
            }
            log("DisplayState: disableExternalDisplayLocked → blocked when outer screen")
        }.onFailure { log("DisplayState: disableExternalDisplayLocked failed", it) }
    }

    // ── 5. AOD on outer screen: prevent sleep + block dream timeouts ───
    //
    // a) MiuiFlipPolicy.shouldDeviceBeSleep() → COMMENTED OUT
    //    Blocking this prevents the device from entering dreaming state,
    //    which means handleRearSandman never fires → dream never starts.
    // b) DisplayManagerServiceImpl.shouldDeviceBeSleep() → COMMENTED OUT
    //    Same reasoning — let the sleep process initiate so dream can start.
    // c) PowerManagerService.updateRearDozeSettings() → force alwaysOn+isFullAod
    // e) DreamController.stopDream → block "slow to connect/finish" for groupId 1
    private fun hookAodOuterScreen(param: SystemServerStartingParam) {
        // a) MiuiFlipPolicy.shouldDeviceBeSleep() → COMMENTED OUT
        //    Let device enter sleep state so handleRearSandman can start the dream.
        //    The dream's screen state is controlled by DozeMachine hooks instead.
        /*
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.server.display.MiuiFlipPolicy")
            hook(cls.method("shouldDeviceBeSleep")) { chain ->
                log("DisplayState/AOD: MiuiFlipPolicy.shouldDeviceBeSleep → false")
                false
            }
        }.onFailure { log("DisplayState/AOD: MiuiFlipPolicy failed", it) }
        */

        // b) DisplayManagerServiceImpl.shouldDeviceBeSleep() → COMMENTED OUT
        /*
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.server.display.DisplayManagerServiceImpl")
            val method = cls.method("shouldDeviceBeSleep",
                android.util.SparseBooleanArray::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            )
            hook(method) { chain ->
                log("DisplayState/AOD: DisplayManagerServiceImpl.shouldDeviceBeSleep → false")
                false
            }
        }.onFailure { log("DisplayState/AOD: DisplayManagerServiceImpl failed", it) }
        */

        // c) PowerManagerService.updateRearDozeSettings() → force alwaysOn
        runCatching {
            val pmsClass = param.classLoader.loadClass(
                "com.android.server.power.PowerManagerService")
            val method = pmsClass.method(
                "updateRearDozeSettings",
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            )
            hook(method, before { chain ->
                val groupId = chain.args[0] as? Int ?: return@before
                val origAlwaysOn = chain.args[1]
                val origFullAod = chain.args[2]
                log("DisplayState/AOD: updateRearDozeSettings(groupId=$groupId, alwaysOn=$origAlwaysOn, fullAod=$origFullAod)")
                if (groupId == 1) {
                    chain.args[1] = true  // alwaysOn
                    chain.args[2] = true  // isFullAod
                    log("DisplayState/AOD: forced alwaysOn+fullAod for groupId=1")
                }
            })
        }.onFailure { log("DisplayState/AOD: updateRearDozeSettings failed", it) }

        // e) DreamController.stopDream → block timeout kills for groupId 1
        runCatching {
            val dcClass = param.classLoader.loadClass(
                "com.android.server.dreams.DreamController")
            val method = dcClass.getDeclaredMethod("stopDream",
                Boolean::class.javaPrimitiveType!!,
                String::class.java)
            method.isAccessible = true
            hook(method) { chain ->
                val reason = chain.args[1] as? String ?: return@hook chain.proceed()
                val groupId = chain.thisObject.getField("mGroupId") as? Int
                log("DisplayState/AOD: DreamController.stopDream(reason=$reason, groupId=$groupId)")
                if (reason == "slow to connect" || reason == "slow to finish") {
                    if (groupId == 1) {
                        log("DisplayState/AOD: BLOCKED stopDream '$reason' for groupId 1")
                        return@hook null
                    }
                }
                chain.proceed()
            }
            log("DisplayState/AOD: hooked DreamController.stopDream")
        }.onFailure { log("DisplayState/AOD: DreamController.stopDream failed", it) }

    }
}
