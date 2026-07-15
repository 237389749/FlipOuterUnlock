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

    fun hook(param: SystemServerStartingParam) {
        safeHook("DisplayStateHook") {
            hookDisplayToClosed(param)
            hookAppLayerToUnfolded(param)
            hookDisplayInfoForStateToClosed(param)
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
                chain.args[0] = closedState
                chain.proceed()
            }
            log("DisplayState: forced LogicalDisplayMapper -> always CLOSED (outer screen)")
        }.onFailure { log("DisplayState: failed hook LogicalDisplayMapper", it) }
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
                chain.args[0] = false
                chain.proceed()
            }
            log("DisplayState: forced ContinuityPolicyService.onDeviceStateChanged -> unfolded")
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
                chain.args[0] = 0  // force deviceState=0 (CLOSED)
                chain.proceed()
            }
            log("DisplayState: forced getDisplayInfoForStateLocked -> always state=0")
        }.onFailure { log("DisplayState: failed hook getDisplayInfoForStateLocked", it) }
    }

    // ── 4. AOD on outer screen: prevent sleep + enable rear doze ──────────
    //
    // Outer screen AOD uses DIFFERENT settings than inner screen:
    //   Inner: "doze_always_on" + "full_screen_aod_on"
    //   Outer: "rear_doze_always_on" ← separate setting!
    //
    // Even with correct Settings, handleRearSandman() checks
    // mRearAlwaysOnEnabled — if false, outer screen goes to deep sleep.
    // updateRearDozeSettings() must be called with alwaysOn+isFullAod=true.
    private fun hookAodOuterScreen(param: SystemServerStartingParam) {
        // a) MiuiFlipPolicy.shouldDeviceBeSleep() → false
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.server.display.MiuiFlipPolicy")
            hook(cls.method("shouldDeviceBeSleep"), replaceResult(false))
            log("DisplayState/AOD: MiuiFlipPolicy.shouldDeviceBeSleep → false")
        }.onFailure { log("DisplayState/AOD: MiuiFlipPolicy failed", it) }

        // b) DisplayManagerServiceImpl.shouldDeviceBeSleep() → false
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.server.display.DisplayManagerServiceImpl")
            hook(cls.method("shouldDeviceBeSleep",
                android.util.SparseBooleanArray::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            ), replaceResult(false))
            log("DisplayState/AOD: DisplayManagerServiceImpl.shouldDeviceBeSleep → false")
        }.onFailure { log("DisplayState/AOD: DisplayManagerServiceImpl failed", it) }

        // c) PowerManagerService.updateRearDozeSettings() → force alwaysOn
        runCatching {
            val pmsClass = param.classLoader.loadClass(
                "com.android.server.power.PowerManagerService")
            val method = pmsClass.getDeclaredMethod(
                "updateRearDozeSettings",
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            )
            method.isAccessible = true
            hook(method, before { chain ->
                val groupId = chain.args[0] as? Int ?: return@before
                if (groupId == 1) {
                    chain.args[1] = true  // alwaysOn
                    chain.args[2] = true  // isFullAod
                }
            })
            log("DisplayState/AOD: updateRearDozeSettings → alwaysOn for groupId 1")
        }.onFailure { log("DisplayState/AOD: updateRearDozeSettings failed", it) }

        // d) DozeBrightnessStrategyImpl.updateAodMode() → force mIsFullAod
        runCatching {
            val dozeClass = param.classLoader.loadClass(
                "com.android.server.display.brightness.strategy.DozeBrightnessStrategyImpl")
            val method = dozeClass.getDeclaredMethod(
                "updateAodMode", Int::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method, after { chain, _ ->
                val thisObj = chain.thisObject
                val z = thisObj.getField("mIsFullAod") as? Boolean
                if (z != true) {
                    thisObj.setField("mIsFullAod", true)
                    thisObj.setField("mIsFullAodForBrightness", true)
                    log("DisplayState/AOD: forced mIsFullAod=true")
                }
            })
            log("DisplayState/AOD: hooked DozeBrightnessStrategyImpl.updateAodMode")
        }.onFailure { log("DisplayState/AOD: DozeBrightnessStrategyImpl failed", it) }
    }
}
