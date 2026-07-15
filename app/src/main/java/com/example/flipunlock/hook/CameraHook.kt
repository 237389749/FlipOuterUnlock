package com.example.flipunlock.hook

import android.util.SparseIntArray
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Remap front camera role to a back camera on outer screen.
 *
 * Camera2CompatAdapterRole (F3.e) builds f2678h (SparseIntArray role→cameraId)
 * during init (method s()). After init, we inject:
 *   role 1  (FRONT)       → main back cameraId
 *   role 41 (FRONT_COVER) → main back cameraId
 *
 * The same physical cameraId can serve multiple roles — HAL supports this.
 */
object CameraHook : BaseHook() {
    override val targetPackages = listOf("com.android.camera")

    override fun setupHooks(param: PackageReadyParam) {
        remapFrontCameraAfterInit(param)
    }

    private fun remapFrontCameraAfterInit(param: PackageReadyParam) {
        runCatching {
            val adapterClass = param.classLoader.loadClass("F3.e")
            // s(boolean) = init()
            val initMethod = adapterClass.method("s", Boolean::class.javaPrimitiveType!!)
            hook(initMethod, after { chain, result ->
                val instance = chain.thisObject

                // f2678h : SparseIntArray (role → cameraId)
                val mapping = instance.getField("f2678h") as? SparseIntArray ?: return@after result
                if (mapping.size() == 0) return@after result

                val mainBackId = mapping.get(0, -1)
                if (mainBackId == -1) {
                    log("CameraHook: no main back camera (role 0), skipping remap")
                    return@after result
                }

                val oldFrontId = mapping.get(1, -1)
                mapping.put(1, mainBackId)
                mapping.put(41, mainBackId)

                log("CameraHook: remapped front camera role 1 → cameraId $mainBackId (was $oldFrontId)")
                log("CameraHook: remapped front_cover role 41 → cameraId $mainBackId")

                result
            })
            log("CameraHook: hooked Camera2CompatAdapterRole.s(boolean)")
        }.onFailure { log("CameraHook: failed to hook camera init", it) }
    }
}
