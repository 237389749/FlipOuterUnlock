package com.example.flipunlock.hook

import android.content.ComponentName
import android.content.Context
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedInterface.PRIORITY_LOWEST
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * SystemUI-side hooks for the external display.
 *
 * Currently hooks:
 * - DecorWindowManagerImpl.shouldHideDecorWindow() to hide widget overlay
 * - MiuiNotificationMenuRow.createMenuViews() with isTinyScreen -> false scope
 * - MiuiCollapsedStatusBarFragment clock visibility (hide on flip outer screen)
 * - NotificationIconContainer / MiuiStatusIconContainer icon expansion
 *
 * DeviceIdentityHook is excluded from SystemUI process (lock screen crash),
 * so this hook applies fixes unconditionally without device state checks.
 */
object SystemUIHook : BaseHook() {
    override val targetPackages = listOf("com.android.systemui")

    // Default max icons for status bar. 8 ensures the always-on display
    // shows enough notification icons.
    private const val STATUS_BAR_ICON_MAX = 8

    override fun setupHooks(param: PackageReadyParam) {
        log("SystemUIHook: loading for ${param.packageName}")
        hookDecorWindowManager(param)
        hookNotificationMenu(param)
        hookStatusBarClock(param)
        hookStatusBarIcons(param)
        hookLockScreenTouchFix(param)
    }

    // ── DecorWindowManagerImpl.shouldHideDecorWindow ────────────────────
    // Returns true = hide widget, false = show widget.
    // We force true to always hide from SystemUI side.
    private fun hookDecorWindowManager(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.notification.decor.DecorWindowManagerImpl"
            )
            val method = cls.method(
                "shouldHideDecorWindow", ComponentName::class.java
            )
            hook(method, replaceResult(true))
            log("SystemUI: forced DecorWindowManagerImpl.shouldHideDecorWindow -> true")
        }.onFailure { log("SystemUI: failed hook DecorWindowManagerImpl", it) }
    }

    // ── Notification menu fix ───────────────────────────────────────────
    // MiuiNotificationMenuRow.createMenuViews runs within a scope where
    // MiuiConfigs.isTinyScreen(Context) returns false.
    // Belt-and-suspenders with DeviceIdentityHook's global override.
    private fun hookNotificationMenu(param: PackageReadyParam) {
        runCatching {
            val miuiConfigs = param.classLoader.loadClass(
                "com.miui.utils.configs.MiuiConfigs"
            )
            val fakeTinyScreen = hookScope(
                miuiConfigs.method("isTinyScreen", Context::class.java)
            ) { false }

            val rowClass = param.classLoader.loadClass(
                "com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow"
            )
            hook(rowClass.method("createMenuViews", Boolean::class.java)) { chain ->
                fakeTinyScreen.run { chain.proceed() }
            }
            log("SystemUI: hooked MiuiNotificationMenuRow.createMenuViews")
        }.onFailure { log("SystemUI: failed hook notification menu", it) }
    }

    // ── Status bar clock hiding ──────────────────────────────────────────
    // Always hide the status bar clock on the external display since
    // the always-on/outer screen has its own clock layout.
    private fun hookStatusBarClock(param: PackageReadyParam) {
        runCatching {
            val fragmentClass = param.classLoader.loadClass(
                "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment"
            )

            // clockHiddenMode -> return 8 (GONE) to hide clock
            hook(fragmentClass.method("clockHiddenMode")) { 8 }

            // updateStatusBarVisibilities -> after proceed, force hideClock
            hook(fragmentClass.method(
                "updateStatusBarVisibilities", Boolean::class.java
            )) { chain ->
                val result = chain.proceed()
                chain.thisObject?.callMethod("hideClock", false)
                result
            }

            // showClock -> if arg is true, hide clock instead of showing it
            hook(fragmentClass.method("showClock", Boolean::class.java)) { chain ->
                if (chain.args[0] == true) {
                    chain.thisObject?.callMethod("hideClock", false)
                } else {
                    chain.proceed()
                }
            }

            log("SystemUI: hooked MiuiCollapsedStatusBarFragment clock")
        }.onFailure { log("SystemUI: failed hook status bar clock", it) }
    }

    // ── Status bar icon expansion ────────────────────────────────────────
    // Expand max notification icons shown on the external display and
    // fake isFlipTinyScreen -> false during measure/layout.
    private fun hookStatusBarIcons(param: PackageReadyParam) {
        runCatching {
            val miuiConfigs = param.classLoader.loadClass(
                "com.miui.utils.configs.MiuiConfigs"
            )
            val fakeFlipTinyScreen = hookScope(
                miuiConfigs.method("isFlipTinyScreen", Context::class.java)
            ) { false }

            // ── NotificationIconContainer ────────────────────────────────
            val containerClass = param.classLoader.loadClass(
                "com.android.systemui.statusbar.phone.NotificationIconContainer"
            )

            val iconHooker = Hooker { chain ->
                val savedMaxIcons = chain.thisObject?.getField("mMaxIcons") as? Int
                chain.thisObject?.setField("mMaxIcons", STATUS_BAR_ICON_MAX)
                runWithCleanup({
                    savedMaxIcons?.let { chain.thisObject?.setField("mMaxIcons", it) }
                }) {
                    fakeFlipTinyScreen.run { chain.proceed() }
                }
            }

            hook(
                containerClass.method("calculateIconXTranslations"),
                PRIORITY_LOWEST,
                iconHooker
            )
            hook(
                containerClass.method("onMeasure", Int::class.java, Int::class.java),
                PRIORITY_LOWEST,
                iconHooker
            )

            // ── MiuiStatusIconContainer ──────────────────────────────────
            val statusIconClass = param.classLoader.loadClass(
                "com.android.systemui.statusbar.views.MiuiStatusIconContainer"
            )
            hook(
                statusIconClass.method("onMeasure", Int::class.java, Int::class.java)
            ) { chain ->
                fakeFlipTinyScreen.run { chain.proceed() }
            }

            log("SystemUI: hooked status bar icon expansion")
        }.onFailure { log("SystemUI: failed hook status bar icons", it) }
    }

    // ── Lock screen touch fix: unlock swipe + shortcut clicks ──────────
    //
    // The tiny lock screen panel (TinyKeyguardPanelViewControllerImpl) renders
    // on top of the shortcut container in KeyguardRootView. Its TouchHandler
    // intercepts ALL touches — only the camera icon has explicit hit-test logic.
    // Other shortcuts (flashlight etc.) receive no touch events because the
    // panel consumes them.
    //
    // Additionally, the fling() unlock gate checks mBarState != 0. If mBarState
    // is SHADE (0) instead of KEYGUARD (1), swipe-up won't unlock — it just
    // closes the panel. Pulling down notification shade resets the state, which
    // is why that workaround temporarily fixes the issue.
    //
    // Fix 1: Hook fling() to bypass mBarState == 0 check, allowing unlock.
    // Fix 2: Hook onInterceptTouchEvent to pass through non-camera touches,
    //        letting shortcuts underneath receive click events.
    private fun hookLockScreenTouchFix(param: PackageReadyParam) {
        runCatching {
            val implClass = param.classLoader.loadClass(
                "com.android.keyguard.tinyPanel.TinyKeyguardPanelViewControllerImpl"
            )

            // Fix 1: fling(float, boolean, boolean) — bypass mBarState==0 gate.
            // Original: if (z || mBarState == 0 || !mCanDismissLockScreen) → close only
            // Fixed: force mBarState check to pass by checking field and overriding
            runCatching {
                val flingMethod = implClass.getDeclaredMethod("fling",
                    Float::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!)
                flingMethod.isAccessible = true
                hook(flingMethod) { chain ->
                    // Force mBarState != 0 so unlock path is taken
                    // mBarState field: 0=SHADE, 1=KEYGUARD
                    val barStateField = chain.thisObject.javaClass.getDeclaredField("mBarState")
                    barStateField.isAccessible = true
                    val saved = barStateField.getInt(chain.thisObject)
                    if (saved == 0) {
                        barStateField.setInt(chain.thisObject, 1)
                        log("SystemUI/LockScreen: forced mBarState 0→1 for unlock swipe")
                    }
                    chain.proceed()
                    if (saved == 0) {
                        barStateField.setInt(chain.thisObject, saved)
                    }
                }
                log("SystemUI: hooked TinyKeyguardPanelViewControllerImpl.fling()")
            }.onFailure { log("SystemUI: fling hook failed", it) }

            // Fix 2: TouchHandler.onInterceptTouchEvent — pass through shortcut touches.
            // The TouchHandler only has hit-test for the camera icon. All other touches
            // are consumed by the panel. We let ACTION_DOWN pass through when the touch
            // is NOT on the camera icon, so shortcuts underneath can receive clicks.
            runCatching {
                val touchHandlerClass = implClass.classLoader.loadClass(
                    "com.android.keyguard.tinyPanel.TinyKeyguardPanelViewControllerImpl\$TouchHandler"
                )
                val interceptMethod = touchHandlerClass.getDeclaredMethod("onInterceptTouchEvent",
                    android.view.MotionEvent::class.java)
                interceptMethod.isAccessible = true
                hook(interceptMethod) { chain ->
                    val result = chain.proceed() as? Boolean ?: false
                    // If the original handler intercepted (returned true) and the
                    // touch is NOT on the camera icon, let it pass through to the
                    // shortcut container underneath.
                    if (result && chain.args[0] is android.view.MotionEvent) {
                        val ev = chain.args[0] as android.view.MotionEvent
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                            // Camera icon is near the top of screen; shortcuts are
                            // at the bottom. If touch is in the lower half, let it through.
                            val screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels
                            if (ev.y > screenHeight * 0.6f) {
                                log("SystemUI/LockScreen: passing through shortcut touch at y=${ev.y}")
                                return@hook false
                            }
                        }
                    }
                    result
                }
                log("SystemUI: hooked TouchHandler.onInterceptTouchEvent")
            }.onFailure { log("SystemUI: TouchHandler hook failed", it) }

        }.onFailure { log("SystemUI: lock screen touch fix failed", it) }
    }
}
