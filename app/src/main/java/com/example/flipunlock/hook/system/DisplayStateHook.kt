package com.example.flipunlock.hook.system

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Force the device to always report OPENED (unfolded) state.
 *
 * Must be paired with a modified display_layout_configuration.xml:
 *   /odm/etc/displayconfig/display_layout_configuration.xml
 *   → OPENED state (id=3) should use outer screen address
 *     4630947108695800452 instead of inner 4630947108695800451
 *
 * With both in place:
 * - All flip-specific restrictions disappear (isFlipDevice→false cascade)
 * - Outer screen stays on (XML override prevents blackout)
 * - Existing hooks (Cutout, IME, Sogou, etc.) can be phased out gradually
 *
 * WARNING: This is the nuclear option. Test carefully.
 */
object DisplayStateHook {

    fun hook(param: SystemServerStartingParam) {
        safeHook("DisplayStateHook") {
            hookFoldStateToUnfolded(param)
        }
    }

    private fun hookFoldStateToUnfolded(param: SystemServerStartingParam) {
        runCatching {
            val cpsClass = param.classLoader.loadClass(
                "com.android.server.wm.ContinuityPolicyService"
            )
            // onDeviceStateChanged(boolean folded) — registered as
            // FoldStateListener callback in onBootPhase(phase=500).
            // Force false = unfolded regardless of actual sensor value.
            val method = cpsClass.method(
                "onDeviceStateChanged", Boolean::class.javaPrimitiveType!!
            )
            hook(method) { chain ->
                chain.args[0] = false
                chain.proceed()
            }
            log("DisplayState: forced onDeviceStateChanged -> unfolded")
        }.onFailure { log("DisplayState: failed", it) }
    }
}
