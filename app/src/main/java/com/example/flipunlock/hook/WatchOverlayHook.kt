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

object WatchOverlayHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    override fun setupHooks(param: PackageReadyParam) {
        log("WatchOverlayHook: loading for ${param.packageName}")
        hookCheckAppConfigRunnable(param)
        hookWatchOverlayGroupView(param)
        hookWatchOverlayWindow(param)
        hookWindowManagerAddView()
    }

    // ========== 1. Controller layer: force hide window ==========
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
                    log("WatchOverlayWindow forced hidden")
                }.onFailure { log("error in checkShouldHideWidget", it) }
                result
            })
            log("hooked CheckAppConfigRunnable.checkShouldHideWidget")
        }.onFailure { log("failed to hook CheckAppConfigRunnable", it) }
    }

    // ========== 2. View layer: WatchOverlayGroupView fully disabled ==========
    // The WatchOverlayGroupView class is in an obfuscated sub-package that
    // differs between HyperOS versions:
    //   HyperOS 1 (Android 14): com.miui.fliphome.widget.p006ui.WatchOverlayGroupView
    //   HyperOS 2/3 (Android 15):com.miui.fliphome.widget.p014ui.WatchOverlayGroupView
    // Try each known variant; the first match wins.
    private fun findWatchOverlayGroupViewClass(cl: ClassLoader): Class<*> {
        val variants = listOf(
            "com.miui.fliphome.widget.p006ui.WatchOverlayGroupView",  // HyperOS 1
            "com.miui.fliphome.widget.p014ui.WatchOverlayGroupView",  // HyperOS 2/3
            "com.miui.fliphome.widget.ui.WatchOverlayGroupView",      // fallback
        )
        for (name in variants) {
            runCatching { return cl.loadClass(name) }
        }
        throw ClassNotFoundException("WatchOverlayGroupView not found in any known package")
    }

    private fun hookWatchOverlayGroupView(param: PackageReadyParam) {
        runCatching {
            val clazz = findWatchOverlayGroupViewClass(param.classLoader)

            // 2.1 Constructor
            val constructor = clazz.declaredConstructors.firstOrNull()
            if (constructor != null) {
                constructor.isAccessible = true
                hook(constructor, after { chain, result ->
                    val view = chain.thisObject as? View ?: return@after result
                    setNotTouchableAndGone(view)
                    log("WatchOverlayGroupView constructor -> GONE & NOT_TOUCHABLE")
                    result
                })
            }

            // 2.2 init method
            runCatching {
                val initMethod = clazz.method("init")
                hook(initMethod, after { chain, result ->
                    val view = chain.thisObject as? View ?: return@after result
                    setNotTouchableAndGone(view)
                    runCatching {
                        val pager = view.getField("mPagerView") as? View
                        pager?.alpha = 0.0f
                        pager?.visibility = View.GONE
                    }
                    log("WatchOverlayGroupView init -> GONE & NOT_TOUCHABLE")
                    result
                })
            }

            // 2.3 dispatchTouchEvent
            runCatching {
                val dispatchMethod = clazz.method("dispatchTouchEvent", MotionEvent::class.java)
                hook(dispatchMethod, replaceResult(false))
                log("WatchOverlayGroupView.dispatchTouchEvent -> false")
            }

            // 2.4 isHide
            runCatching {
                val isHideMethod = clazz.method("isHide")
                hook(isHideMethod, replaceResult(true))
            }

            // 2.5 setVisibility
            runCatching {
                val setVisMethod = clazz.method("setVisibility", Int::class.javaPrimitiveType!!)
                hook(setVisMethod, Hooker { chain ->
                    val visibility = chain.args[0] as Int
                    if (visibility == View.VISIBLE) {
                        chain.args[0] = View.GONE
                        log("WatchOverlayGroupView.setVisibility(VISIBLE) -> GONE")
                    }
                    chain.proceed()
                })
            }

            // 2.6 updateLayoutByOrientation
            runCatching {
                val updateMethod = clazz.method("updateLayoutByOrientation", Int::class.javaPrimitiveType!!)
                hook(updateMethod, Hooker { chain ->
                    val view = chain.thisObject as? View
                    if (view != null) {
                        view.visibility = View.GONE
                        runCatching {
                            if (view.parent != null) {
                                val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                                wm.removeViewImmediate(view)
                                log("updateLayoutByOrientation -> removed window")
                            }
                        }
                    }
                    null
                })
            }

            // 2.7 onAttachedToWindow
            runCatching {
                val attachMethod = clazz.method("onAttachedToWindow")
                hook(attachMethod, after { chain, result ->
                    val view = chain.thisObject as? View ?: return@after result
                    view.visibility = View.GONE
                    setNotTouchableAndGone(view)
                    runCatching {
                        val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        wm.removeViewImmediate(view)
                    }
                    result
                })
            }

            log("WatchOverlayGroupView hooks installed")
        }.onFailure { log("failed to hook WatchOverlayGroupView", it) }
    }

    // ========== 3. Window layer: WatchOverlayWindow itself ==========
    private fun hookWatchOverlayWindow(param: PackageReadyParam) {
        runCatching {
            val watchWindowClass = param.classLoader.loadClass(
                "com.miui.fliphome.widget.WatchOverlayWindow"
            )

            // 3.1 refreshWindow: ADD -> REMOVE
            runCatching {
                val refreshMethod = watchWindowClass.method(
                    "refreshWindow", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!
                )
                hook(refreshMethod, Hooker { chain ->
                    val action = chain.args[0] as Int
                    if (action == 1) {
                        chain.args[0] = 2
                        chain.args[1] = false
                        log("refreshWindow ADD -> REMOVE")
                    }
                    chain.proceed()
                })
            }

            // 3.2 onInputMonitorEvent
            runCatching {
                val inputMethod = watchWindowClass.method("onInputMonitorEvent", MotionEvent::class.java)
                hook(inputMethod, replaceResult(false))
                log("onInputMonitorEvent -> false")
            }

            log("hooked WatchOverlayWindow")
        }.onFailure { log("failed to hook WatchOverlayWindow", it) }
    }

    // ========== Helper: set view not touchable and gone ==========
    private fun setNotTouchableAndGone(view: View) {
        runCatching {
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.updateViewLayout(view, lp)
            view.visibility = View.GONE
        }.onFailure { log("setNotTouchableAndGone error", it) }
    }

    // ========== 4. Ultimate intercept: block WatchOverlayGroupView from WindowManager ==========
    private fun hookWindowManagerAddView() {
        runCatching {
            val addViewMethod = WindowManager::class.java.method(
                "addView", View::class.java, android.view.ViewGroup.LayoutParams::class.java
            )
            hook(addViewMethod, Hooker { chain ->
                val view = chain.args[0] as? View
                if (view != null && view.javaClass.name.contains("WatchOverlayGroupView")) {
                    log("Blocked WindowManager.addView for WatchOverlayGroupView")
                    null
                } else {
                    chain.proceed()
                }
            })
            log("hooked WindowManager.addView -> intercept WatchOverlayGroupView")
        }.onFailure { log("failed hook WindowManager.addView", it) }
    }
}
