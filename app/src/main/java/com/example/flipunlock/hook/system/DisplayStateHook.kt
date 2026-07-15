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
}
