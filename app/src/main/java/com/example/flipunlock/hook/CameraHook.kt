package com.example.flipunlock.hook

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.os.Handler
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Redirect front camera to a back camera at the Camera2 HAL choke point.
 *
 * Miui Camera app uses a role-based system (C1039e) to map logical roles
 * (0=main back, 1=front, 40=aux front, etc.) to physical camera IDs. The
 * mapping comes from Xiaomi vendor tags in CameraCharacteristics, and the
 * actual camera IDs vary per device.
 *
 * Strategy (v2): Dynamic LENS_FACING enumeration.
 * On first openCamera() call, we enumerate all cameras, check each one's
 * LENS_FACING characteristic, and build a redirect map. Any camera that
 * reports LENS_FACING_FRONT is redirected to a LENS_FACING_BACK camera.
 *
 * This works regardless of the physical camera ID numbering, and regardless
 * of whether the camera app uses role IDs, hardcoded IDs, or CameraManager
 * directly. openCamera() is the single choke point that ALL camera paths
 * (FaceUnity FUAbstractCamera, Camera2CompatAdapterRole, MIVI, etc.) go through.
 */
object CameraHook : BaseHook() {
    override val targetPackages = listOf("com.android.camera")

    /** Camera IDs identified as front-facing. Will be redirected. */
    private val frontCameraIds = mutableSetOf<String>()

    /** Camera ID to redirect front cameras TO (first back-facing camera found). */
    private var redirectTarget: String? = null

    /** Ensures we only enumerate cameras once. */
    private var enumerated = false

    /**
     * Detect whether we're on the outer (folded) screen by checking display height.
     * Outer screen: ~1392px max. Inner screen: ~2912px max. Threshold: 2000px.
     * Only redirect front camera when on the outer screen — on inner screen
     * (unfolded), the front camera is physically accessible and should work normally.
     */
    private fun isOuterScreen(): Boolean {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        val maxPx = Math.max(dm.widthPixels, dm.heightPixels)
        log("CameraHook: isOuterScreen? maxPx=$maxPx density=${dm.densityDpi} → ${maxPx < 2000}")
        return maxPx < 2000
    }

    override fun setupHooks(param: PackageReadyParam) {
        log("CameraHook: loading for ${param.packageName}")
        runCatching {
            val cmClass = CameraManager::class.java
            // Hook both openCamera overloads: Handler-based and Executor-based
            for (signature in listOf(
                arrayOf(String::class.java, CameraDevice.StateCallback::class.java, Handler::class.java),
                arrayOf(String::class.java, java.util.concurrent.Executor::class.java, CameraDevice.StateCallback::class.java)
            )) {
                runCatching {
                    val openMethod = cmClass.getDeclaredMethod("openCamera", *signature)
                    hook(openMethod, before { chain ->
                        if (!isOuterScreen()) return@before
                        ensureEnumerated(chain.thisObject as? CameraManager)
                        val cameraId = chain.args[0] as? String ?: return@before
                        if (cameraId in frontCameraIds) {
                            val target = redirectTarget
                            if (target != null && target != cameraId) {
                                chain.args[0] = target
                                log("CameraHook: redirect front camera $cameraId → $target")
                            }
                        }
                    })
                    log("CameraHook: hooked openCamera(${signature.joinToString { it.simpleName }})")
                }.onFailure { log("CameraHook: openCamera variant not found: ${it.message}") }
            }
        }.onFailure { log("CameraHook: setup failed", it) }
    }

    /**
     * Lazy one-shot enumeration of all cameras to identify front vs back.
     *
     * Uses the CameraManager instance from the hook to call getCameraIdList()
     * and getCameraCharacteristics(). The facing map is cached and used for
     * all subsequent openCamera() calls.
     */
    private fun ensureEnumerated(cm: CameraManager?) {
        if (enumerated || cm == null) return
        synchronized(this) {
            if (enumerated) return
            runCatching {
                val ids = cm.cameraIdList
                log("CameraHook: enumerating ${ids.size} cameras...")

                for (id in ids) {
                    val cc = cm.getCameraCharacteristics(id)
                    val facing = cc.get(CameraCharacteristics.LENS_FACING) ?: continue
                    when (facing) {
                        CameraMetadata.LENS_FACING_FRONT -> {
                            frontCameraIds.add(id)
                            log("CameraHook:   id=$id → LENS_FACING_FRONT (will redirect)")
                        }
                        CameraMetadata.LENS_FACING_BACK -> {
                            if (redirectTarget == null) {
                                redirectTarget = id
                            }
                            log("CameraHook:   id=$id → LENS_FACING_BACK (redirect target)")
                        }
                        CameraMetadata.LENS_FACING_EXTERNAL -> {
                            log("CameraHook:   id=$id → LENS_FACING_EXTERNAL")
                        }
                        else -> {
                            log("CameraHook:   id=$id → unknown facing=$facing")
                        }
                    }
                }

                log("CameraHook: enumeration done — frontIds=$frontCameraIds, target=$redirectTarget")

                // Fallback: on Mix Flip, ALL cameras report LENS_FACING_BACK (the
                // inner-screen selfie camera is physically a back camera with mirror
                // optics). The camera app hardcodes cameraId "1" as front (FUAbstractCamera
                // defaults mFrontCameraId=1). If no LENS_FACING_FRONT was found, redirect
                // cameraId "1" → "0" (or the lowest available non-1 back camera).
                if (frontCameraIds.isEmpty() && redirectTarget != null) {
                    // Try "1" first (FUAbstractCamera default), then any non-redirectTarget
                    val fallbackFront = ids.firstOrNull {
                        it == "1" && it != redirectTarget
                    } ?: ids.firstOrNull { it != redirectTarget }
                    if (fallbackFront != null) {
                        frontCameraIds.add(fallbackFront)
                        log("CameraHook: fallback — redirect $fallbackFront → $redirectTarget (no LENS_FACING_FRONT cameras found)")
                    }
                }
            }.onFailure {
                log("CameraHook: enumeration failed", it)
            }
            enumerated = true
        }
    }
}
