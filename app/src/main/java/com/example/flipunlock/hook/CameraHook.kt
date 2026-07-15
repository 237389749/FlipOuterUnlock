package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Redirect front camera to main back camera on MIX Flip outer screen.
 *
 * Camera app uses FUAbstractCamera (FaceUnity) for camera management:
 *   - mFrontCameraId defaults to 1 (inner screen selfie camera)
 *   - mBackCameraId defaults to 0 or is set by initCameraInfo()
 *
 * On MIX Flip folded, camera 1 is physically blocked. We redirect
 * getMFrontCameraId() to return a back camera instead.
 *
 * Hooks:
 *   1. setMBackCameraId → save the first back camera ID as redirect target
 *   2. getMFrontCameraId → redirect default(1) → saved back camera ID
 */
object CameraHook : BaseHook() {
    override val targetPackages = listOf("com.android.camera")

    @Volatile private var redirectTarget = -1

    override fun setupHooks(param: PackageReadyParam) {
        val fuClass = runCatching {
            param.classLoader.loadClass(
                "com.faceunity.core.camera.base.FUAbstractCamera")
        }.onFailure {
            log("CameraHook: FUAbstractCamera not found", it)
            return
        }.getOrThrow()

        hookSetBackCameraId(fuClass)
        hookGetFrontCameraId(fuClass)
    }

    // ── save back camera ID → use as redirect target ──────────────────
    private fun hookSetBackCameraId(fuClass: Class<*>) {
        runCatching {
            val method = fuClass.method(
                "setMBackCameraId", Int::class.javaPrimitiveType!!)
            hook(method, after { chain, result ->
                val id = chain.args[0] as? Int ?: return@after result
                if (id > 0 && redirectTarget == -1) {
                    redirectTarget = id
                    log("CameraHook: redirect target = camera $id")
                }
                result
            })
            log("CameraHook: hooked setMBackCameraId")
        }.onFailure { log("CameraHook: setMBackCameraId failed", it) }
    }

    // ── redirect front camera ID → back camera ────────────────────────
    private fun hookGetFrontCameraId(fuClass: Class<*>) {
        runCatching {
            val method = fuClass.method("getMFrontCameraId")
            hook(method, after { chain, result ->
                val id = result as? Int ?: return@after result
                if (redirectTarget != -1 && id != redirectTarget) {
                    log("CameraHook: front $id → $redirectTarget")
                    return@after redirectTarget
                }
                result
            })
            log("CameraHook: hooked getMFrontCameraId")
        }.onFailure { log("CameraHook: getMFrontCameraId failed", it) }
    }
}
