package com.example.flipunlock.hook

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Redirect front camera to main back camera at the Camera2 HAL level.
 *
 * CameraManager.openCamera(String, StateCallback, Handler) is the single
 * choke point that ALL camera paths go through:
 *   - FUAbstractCamera / FUCamera2Impl
 *   - Camera2CompatAdapterRole (F3.e)
 *   - Any direct Camera2 API usage
 *
 * On MIX Flip, cameraId "1" is the inner screen selfie camera
 * (physically blocked when folded). Redirect to "0" (main back).
 */
object CameraHook : BaseHook() {
    override val targetPackages = listOf("com.android.camera")

    override fun setupHooks(param: PackageReadyParam) {
        runCatching {
            val cmClass = CameraManager::class.java
            val openMethod = cmClass.getDeclaredMethod(
                "openCamera",
                String::class.java,
                CameraDevice.StateCallback::class.java,
                Handler::class.java
            )
            hook(openMethod, before { chain ->
                val cameraId = chain.args[0] as? String ?: return@before
                if (cameraId == "1") {
                    chain.args[0] = "0"
                    log("CameraHook: openCamera 1 → 0")
                }
            })
            log("CameraHook: hooked CameraManager.openCamera()")
        }.onFailure { log("CameraHook: CameraManager.openCamera failed", it) }
    }
}
