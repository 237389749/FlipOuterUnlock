package com.example.flipunlock.hook.gesture

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Hook fliphome: replace the FlipLauncher desktop with the inner screen launcher
 * (com.miui.home) while keeping the gesture engine alive.
 *
 * Approach:
 * 1. Let FlipLauncher create normally (gesture callbacks fire, InputMonitor registers)
 * 2. In onResume, clear its content and make the window minimal
 * 3. Launch the inner screen launcher on top
 * 4. Force gesture engine to stay enabled
 */
object GestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    override fun setupHooks(param: PackageReadyParam) {
        replaceFlipLauncherWithInnerLauncher(param)
        keepTouchInteractionServiceAlive(param)
        forceGestureEnabled(param)
    }

    // ── 1. Replace FlipLauncher content with inner launcher ───────────────
    private fun replaceFlipLauncherWithInnerLauncher(param: PackageReadyParam) {
        runCatching {
            val flipLauncherClass = param.classLoader.findClass("com.miui.fliphome.FlipLauncher")

            // Hook onResume: replace content and start inner launcher
            runCatching {
                val onResumeMethod = flipLauncherClass.method("onResume")
                hook(onResumeMethod, after { chain, result ->
                    val launcher = chain.thisObject as? android.app.Activity
                        ?: return@after result

                    // Make FlipLauncher window invisible
                    launcher.window?.apply {
                        // Remove all content
                        decorView?.let { decor ->
                            (decor as? ViewGroup)?.removeAllViews()
                            decor.setBackgroundColor(0x00000000)
                            decor.visibility = View.GONE
                        }
                        // Make window minimal — 1x1 pixel in corner
                        val lp = attributes
                        lp.width = 1
                        lp.height = 1
                        lp.x = 0
                        lp.y = 0
                        lp.alpha = 0f
                        lp.flags = lp.flags or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        attributes = lp
                    }

                    // Start inner screen launcher on top
                    runCatching {
                        val intent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            setPackage("com.miui.home")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        launcher.startActivity(intent)
                        log("GestureFix: launched inner launcher")
                    }.onFailure { log("GestureFix: failed to launch inner launcher", it) }

                    result
                })
                log("GestureFix: hooked FlipLauncher.onResume")
            }
        }.onFailure { log("GestureFix: failed hook FlipLauncher", it) }
    }

    // ── 2. Keep TouchInteractionService alive ───────────────────────────
    private fun keepTouchInteractionServiceAlive(param: PackageReadyParam) {
        runCatching {
            val serviceClass = param.classLoader.findClass(
                "com.miui.fliphome.gesture.service.TouchInteractionService"
            )
            val onDestroyMethod = serviceClass.method("onDestroy")
            hook(onDestroyMethod, replaceResult(null))
            log("GestureFix: blocked TouchInteractionService.onDestroy")
        }.onFailure { log("GestureFix: failed hook TouchInteractionService", it) }
    }

    // ── 3. Force gesture input enabled ───────────────────────────────────
    private fun forceGestureEnabled(param: PackageReadyParam) {
        runCatching {
            val baseGestureImplClass = param.classLoader.findClass(
                "com.miui.fliphome.gesture.BaseGestureImpl"
            )

            // After enableGestureInput, force setEnable(true)
            runCatching {
                val enableMethod = baseGestureImplClass.method("enableGestureInput")
                hook(enableMethod, after { chain, result ->
                    val helper = chain.thisObject.getField("mGestureInputHelper")
                    if (helper != null) {
                        helper.callMethod("setEnable", true)
                    }
                    result
                })
                log("GestureFix: force-enabled BaseGestureImpl.enableGestureInput")
            }

            // Block disableGestureInput entirely
            runCatching {
                val disableMethod = baseGestureImplClass.method("disableGestureInput")
                hook(disableMethod, replaceResult(null))
                log("GestureFix: blocked BaseGestureImpl.disableGestureInput")
            }

            // Force registerInputConsumer when folded
            runCatching {
                val onFoldChanged = baseGestureImplClass.method(
                    "onDisplayFoldChanged", Boolean::class.javaPrimitiveType!!
                )
                hook(onFoldChanged, after { chain, result ->
                    val isFolded = chain.args[0] as? Boolean ?: false
                    if (isFolded) {
                        val helper = chain.thisObject.getField("mGestureInputHelper")
                        if (helper != null) {
                            val isRegistered = helper.callMethod("hasRegisterInputConsumer") as? Boolean
                            if (isRegistered != true) {
                                helper.callMethod("registerInputConsumer")
                                log("GestureFix: force-registered InputConsumer")
                            }
                        }
                    }
                    result
                })
                log("GestureFix: hooked BaseGestureImpl.onDisplayFoldChanged")
            }
        }.onFailure { log("GestureFix: failed hook BaseGestureImpl", it) }
    }
}
