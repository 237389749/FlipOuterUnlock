package com.example.flipunlock.hook

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Prevent fliphome widget overlay from rendering on the outer screen.
 *
 * Rendering chain (from FlipRes):
 *   FlipApplication.onCreate()
 *     → mWatchOverlayWindow = new WatchOverlayWindow(ctx)  ← inflates layout, creates manager
 *       → WatchOverlayGroupView (FrameLayout window)
 *         └── WidgetPagerView (R.id.pager)
 *           └── FlipWidgetManager → FlipMaMlHostView / widget pages
 *   Foreground app change → refreshWindow(ADD) → wm.addView(groupView) → appears
 *
 * Multi-layer defense:
 *   Layer 1: CheckAppConfigRunnable → force hide decision
 *   Layer 2: WatchOverlayGroupView → GONE, NOT_TOUCHABLE, no touch events
 *   Layer 3: WatchOverlayWindow → ADD→REMOVE, InputMonitor→false
 *   Layer 4: WindowManager.addView → intercept and block entirely
 *
 * Note: NOT hooking getWatchOverlayWindow() -> null.
 * Returns null causes internal state inconsistency — WatchOverlayWindow
 * is already constructed with inflated layout and registered observers,
 * but null return makes callers skip lifecycle management. This leaves
 * the widget in an unmanaged state, consuming layout space without
 * proper cleanup, and breaks gesture callback chains.
 */
object WatchOverlayHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.uiWidget) { log("WatchOverlayHook: DISABLED by persist.flipunlock.ui.widget"); return }
        log("WatchOverlayHook: loading for ${param.packageName}")
        hookCheckAppConfigRunnable(param)       // Layer 1: controller
        hookWatchOverlayGroupView(param)        // Layer 2: view
        hookWatchOverlayWindow(param)           // Layer 3: window
        hookWindowManagerAddView()             // Layer 4: ultimate
    }

    // ========== Layer 1: Controller — force hide window ==========
    private fun hookCheckAppConfigRunnable(param: PackageReadyParam) {
        runCatching {
            val runnableClass = param.classLoader.loadClass(
                "com.miui.fliphome.widget.WatchOverlayWindow\$CheckAppConfigRunnable"
            )
            val checkMethod = runnableClass.method(
                "checkShouldHideWidget", PackageManager::class.java, ComponentName::class.java
            )
            hook(checkMethod, after { chain, result ->
                runCatching {
                    val runnable = chain.thisObject
                    val window = runnable.getField("this$0") ?: return@runCatching
                    window.setField("mIsHideAppForeground", true)
                    window.callMethod("refreshWindow", 2, true)
                    log("WatchOverlay: forced hidden via CheckAppConfigRunnable")
                }.onFailure { log("WatchOverlay: checkShouldHideWidget error", it) }
                result
            })
            log("WatchOverlay: hooked CheckAppConfigRunnable")
        }.onFailure { log("WatchOverlay: CheckAppConfigRunnable failed", it) }
    }

    // ========== Layer 2: View — WatchOverlayGroupView fully disabled ==========
    // Try multiple class name variants for HyperOS 1/2/3 compatibility.
    private fun findWatchOverlayGroupViewClass(cl: ClassLoader): Class<*> {
        val variants = listOf(
            "com.miui.fliphome.widget.p006ui.WatchOverlayGroupView",  // HyperOS 1
            "com.miui.fliphome.widget.p014ui.WatchOverlayGroupView",  // HyperOS 2/3
            "com.miui.fliphome.widget.ui.WatchOverlayGroupView",      // fallback
        )
        for (name in variants) {
            runCatching { return cl.loadClass(name) }
        }
        throw ClassNotFoundException("WatchOverlayGroupView not found")
    }

    private fun hookWatchOverlayGroupView(param: PackageReadyParam) {
        runCatching {
            val clazz = findWatchOverlayGroupViewClass(param.classLoader)

            // Constructor
            val constructor = clazz.declaredConstructors.firstOrNull()
            if (constructor != null) {
                constructor.isAccessible = true
                hook(constructor, after { chain, result ->
                    val view = chain.thisObject as? View ?: return@after result
                    disableGroupView(view)
                    log("WatchOverlay: constructor → GONE & NOT_TOUCHABLE")
                    result
                })
            }

            // init method
            runCatching {
                val initMethod = clazz.method("init")
                hook(initMethod, after { chain, result ->
                    val view = chain.thisObject as? View ?: return@after result
                    disableGroupView(view)
                    runCatching {
                        val pager = view.getField("mPagerView") as? View
                        pager?.alpha = 0.0f
                        pager?.visibility = View.GONE
                    }
                    log("WatchOverlay: init → GONE & NOT_TOUCHABLE")
                    result
                })
            }

            // dispatchTouchEvent
            runCatching {
                val dispatchMethod = clazz.method("dispatchTouchEvent", MotionEvent::class.java)
                hook(dispatchMethod, replaceResult(false))
                log("WatchOverlay: dispatchTouchEvent → false")
            }

            // isHide
            runCatching {
                val isHideMethod = clazz.method("isHide")
                hook(isHideMethod, replaceResult(true))
            }

            // setVisibility: convert VISIBLE → GONE
            runCatching {
                val setVisMethod = clazz.method("setVisibility", Int::class.javaPrimitiveType!!)
                hook(setVisMethod, Hooker { chain ->
                    val visibility = chain.args[0] as Int
                    if (visibility == View.VISIBLE) {
                        chain.args[0] = View.GONE
                        log("WatchOverlay: setVisibility(VISIBLE) → GONE")
                    }
                    chain.proceed()
                })
            }

            // updateLayoutByOrientation: prevent re-show
            runCatching {
                val updateMethod = clazz.method("updateLayoutByOrientation", Int::class.javaPrimitiveType!!)
                hook(updateMethod, Hooker { chain ->
                    disableGroupView(chain.thisObject as? View ?: return@Hooker chain.proceed())
                    null
                })
            }

            // onAttachedToWindow: force removal immediately
            runCatching {
                val attachMethod = clazz.method("onAttachedToWindow")
                hook(attachMethod, after { chain, result ->
                    val view = chain.thisObject as? View ?: return@after result
                    disableGroupView(view)
                    runCatching {
                        val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        wm.removeViewImmediate(view)
                    }
                    result
                })
            }

            log("WatchOverlay: WatchOverlayGroupView hooks installed")
        }.onFailure { log("WatchOverlay: WatchOverlayGroupView failed", it) }
    }

    // ========== Layer 3: Window — WatchOverlayWindow ==========
    private fun hookWatchOverlayWindow(param: PackageReadyParam) {
        runCatching {
            val watchWindowClass = param.classLoader.loadClass(
                "com.miui.fliphome.widget.WatchOverlayWindow")

            // refreshWindow: ADD → REMOVE
            runCatching {
                val refreshMethod = watchWindowClass.method(
                    "refreshWindow", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!
                )
                hook(refreshMethod, Hooker { chain ->
                    val action = chain.args[0] as Int
                    if (action == 1) {
                        chain.args[0] = 2
                        chain.args[1] = false
                        log("WatchOverlay: refreshWindow ADD → REMOVE")
                    }
                    chain.proceed()
                })
            }

            // onInputMonitorEvent
            runCatching {
                val inputMethod = watchWindowClass.method("onInputMonitorEvent", MotionEvent::class.java)
                hook(inputMethod, replaceResult(false))
                log("WatchOverlay: onInputMonitorEvent → false")
            }

            log("WatchOverlay: WatchOverlayWindow hooked")
        }.onFailure { log("WatchOverlay: WatchOverlayWindow failed", it) }
    }

    // ========== Layer 4: Ultimate — block WindowManager.addView ==========
    private fun hookWindowManagerAddView() {
        runCatching {
            val addViewMethod = WindowManager::class.java.method(
                "addView", View::class.java, android.view.ViewGroup.LayoutParams::class.java
            )
            hook(addViewMethod, Hooker { chain ->
                val view = chain.args[0] as? View
                if (view != null && view.javaClass.name.contains("WatchOverlayGroupView")) {
                    log("WatchOverlay: blocked WM.addView for WatchOverlayGroupView")
                    null
                } else {
                    chain.proceed()
                }
            })
            log("WatchOverlay: hooked WindowManager.addView")
        }.onFailure { log("WatchOverlay: WindowManager.addView failed", it) }
    }

    // ========== Helper ==========
    private fun disableGroupView(view: View) {
        // Set visibility first (always works regardless of attach state)
        view.visibility = View.GONE
        // Modify layout params: when the view IS later added to WM, it will be
        // NOT_TOUCHABLE and 1×1 (no layout space consumed). Using setLayoutParams
        // directly works pre-attach; updateViewLayout only works post-attach.
        runCatching {
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
            lp.width = 1
            lp.height = 1
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Also zero out the per-rotation params used by WatchOverlayGroupView
            runCatching {
                val field = WindowManager.LayoutParams::class.java.getDeclaredField("paramsForRotation")
                field.isAccessible = true
                val rotationParams = field.get(lp) as? Array<WindowManager.LayoutParams>
                if (rotationParams != null) {
                    for (rp in rotationParams) {
                        rp.width = 1
                        rp.height = 1
                    }
                }
            }
        }
    }
}
