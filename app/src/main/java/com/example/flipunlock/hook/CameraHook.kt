package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Remap front camera role to a back camera on outer screen.
 *
 * Camera2CompatAdapterRole (F3.e) builds f2678h (role→cameraId mapping) during init().
 * Three approaches:
 *
 * A) Hook s(boolean) after → modify f2678h directly (role 1/41 → main back)
 * B) Hook individual query methods k(), F(), n() — intercept on every call
 * C) Hook CameraCharacteristics.get() → spoof LENS_FACING BACK→FRONT
 *
 * Approach B catches timing issues. Approach C ensures the remapped camera
 * reports as FRONT-facing at the Camera2 API level.
 */
object CameraHook : BaseHook() {
    override val targetPackages = listOf("com.android.camera")

    @Volatile private var mainBackCameraId = -1
    // Track which CameraCharacteristics objects belong to our remapped back camera
    private val remappedCharacteristics = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<Any, java.lang.Boolean>())

    override fun setupHooks(param: PackageReadyParam) {
        val adapterClass = runCatching {
            param.classLoader.loadClass("F3.e")
        }.onFailure {
            log("CameraHook: F3.e not found", it)
            return
        }.getOrThrow()

        approachA_hookInit(adapterClass)
        approachB_hookQueries(adapterClass)
        approachC_hookLensFacing()
    }

    // ── A: Modify f2678h after init() completes ──────────────────────────
    private fun approachA_hookInit(adapterClass: Class<*>) {
        runCatching {
            val initMethod = adapterClass.method("s", Boolean::class.javaPrimitiveType!!)
            hook(initMethod, after { chain, result ->
                val mapping = chain.thisObject.getField("f2678h") as? android.util.SparseIntArray
                if (mapping == null || mapping.size() == 0) return@after result

                val backId = mapping.get(0, -1)
                if (backId == -1) {
                    log("CameraHook/A: no main back camera (role 0)")
                    return@after result
                }

                mainBackCameraId = backId
                val oldFront = mapping.get(1, -1)
                mapping.put(1, backId)
                mapping.put(41, backId)

                log("CameraHook/A: role 1→$backId (was $oldFront), role 41→$backId")
                result
            })
            log("CameraHook/A: hooked F3.e.s(boolean)")
        }.onFailure { log("CameraHook/A: failed", it) }
    }

    // ── B: Hook individual query methods — catch every call ──────────────
    private fun approachB_hookQueries(adapterClass: Class<*>) {
        // k() = getFrontCameraId() → role 1
        runCatching {
            val method = adapterClass.method("k")
            hook(method, after { chain, result ->
                val r = result as? Int ?: return@after result
                if (r == -1 && mainBackCameraId != -1) {
                    log("CameraHook/B: k()=-1 → $mainBackCameraId")
                    mainBackCameraId
                } else result
            })
            log("CameraHook/B: hooked F3.e.k()")
        }.onFailure { log("CameraHook/B: k() failed", it) }

        // F() = hasFrontCoverCamera() → role 41
        runCatching {
            val method = adapterClass.method("F")
            hook(method, after { chain, result ->
                val r = result as? Boolean ?: return@after result
                if (!r) {
                    log("CameraHook/B: F()=false → true")
                    true
                } else result
            })
            log("CameraHook/B: hooked F3.e.F()")
        }.onFailure { log("CameraHook/B: F() failed", it) }

        // n() = getAuxFrontCameraId() → role 40
        runCatching {
            val method = adapterClass.method("n")
            hook(method, after { chain, result ->
                val r = result as? Int ?: return@after result
                if (r == -1 && mainBackCameraId != -1) {
                    log("CameraHook/B: n()=-1 → $mainBackCameraId")
                    mainBackCameraId
                } else result
            })
            log("CameraHook/B: hooked F3.e.n()")
        }.onFailure { log("CameraHook/B: n() failed", it) }
    }

    // ── C: Spoof LENS_FACING for remapped camera ─────────────────────────
    // Camera app queries LENS_FACING via CameraCharacteristics.get().
    // The back camera reports LENS_FACING_BACK (1), but since we remapped
    // it as front, we change it to LENS_FACING_FRONT (0).
    // Only affects the cameraId we remapped — tracked via IdentityHashMap.
    private fun approachC_hookLensFacing() {
        // Step 1: Track which Characteristics objects are for our remapped camera
        runCatching {
            val cmClass = android.hardware.camera2.CameraManager::class.java
            val getCC = cmClass.getDeclaredMethod(
                "getCameraCharacteristics", String::class.java)
            hook(getCC, after { chain, result ->
                val cameraId = chain.args[0] as? String ?: return@after result
                if (mainBackCameraId != -1 && cameraId == mainBackCameraId.toString()) {
                    (result as? Any)?.let { remappedCharacteristics.add(it) }
                }
                result
            })
            log("CameraHook/C: hooked CameraManager.getCameraCharacteristics()")
        }.onFailure { log("CameraHook/C: CameraManager hook failed", it) }

        // Step 2: Spoof LENS_FACING for tracked characteristics
        runCatching {
            val ccClass = android.hardware.camera2.CameraCharacteristics::class.java
            val getMethod = ccClass.getDeclaredMethod(
                "get", android.hardware.camera2.CameraCharacteristics.Key::class.java)
            hook(getMethod, after { chain, result ->
                if (chain.thisObject !in remappedCharacteristics) return@after result
                val key = chain.args[0] as? android.hardware.camera2.CameraCharacteristics.Key<*>
                    ?: return@after result
                if (key.name == "android.lens.facing") {
                    val value = result as? Int ?: return@after result
                    if (value == 1) { // LENS_FACING_BACK
                        log("CameraHook/C: LENS_FACING BACK→FRONT for cameraId $mainBackCameraId")
                        return@after 0 // LENS_FACING_FRONT
                    }
                }
                result
            })
            log("CameraHook/C: hooked CameraCharacteristics.get()")
        }.onFailure { log("CameraHook/C: CameraCharacteristics hook failed", it) }
    }
}
