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
    // The tiny lock screen panel (TinyKeyguardPanelView) is a full-screen
    // FrameLayout that overlays the shortcut container in KeyguardRootView.
    // Its TouchHandler intercepts and consumes ALL touches — only the camera
    // icon has explicit hit-test logic. Other shortcuts (flashlight etc.)
    // receive no touch events because the panel eats them.
    //
    // The fling() unlock gate checks mBarState != 0. If mBarState is SHADE(0)
    // instead of KEYGUARD(1), swipe-up only closes the panel instead of unlocking.
    //
    // Approach: hook TinyKeyguardPanelView.dispatchTouchEvent directly.
    // For touches in the lower screen area (where shortcuts are), don't
    // consume the event — let it fall through to the shortcut container below.
    // For swipe-up unlock, also set the result to false when mBarState is wrong.
    private fun hookLockScreenTouchFix(param: PackageReadyParam) {
        // Fix 1: Shortcut clicks — hook ViewGroup.dispatchTouchEvent, filter for
        // TinyKeyguardPanelView, pass through touches in the lower screen area.
        runCatching {
            val dispatchMethod = android.view.ViewGroup::class.java
                .getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent::class.java)
            dispatchMethod.isAccessible = true
            hook(dispatchMethod) { chain ->
                val view = chain.thisObject as? android.view.View
                if (view?.javaClass?.name?.contains("TinyKeyguardPanelView") != true) {
                    return@hook chain.proceed()
                }
                val ev = chain.args[0] as? android.view.MotionEvent ?: return@hook chain.proceed()
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    val screenH = android.content.res.Resources.getSystem().displayMetrics.heightPixels
                    if (ev.y > screenH * 0.55f) {
                        log("SystemUI/LockScreen: pass-through shortcut touch at y=${ev.y}")
                        return@hook false
                    }
                }
                chain.proceed()
            }
            log("SystemUI: hooked ViewGroup.dispatchTouchEvent → TinyKeyguardPanelView filter")
        }.onFailure { log("SystemUI: dispatchTouchEvent hook failed", it) }

        // Also fix the unlock swipe gate. Instead of hooking the complex
        // fling() method, set mCanDismissLockScreen=true on the keyguard
        // state controller so swipe-up always attempts unlock.
        runCatching {
            val ksClass = param.classLoader.loadClass(
                "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"
            )
            val canDismissField = ksClass.getDeclaredField("mCanDismissLockScreen")
            canDismissField.isAccessible = true

            // Hook onStartedWakingUp to force mCanDismissLockScreen = true
            val wakeMethod = ksClass.getDeclaredMethod("onStartedWakingUp")
            wakeMethod.isAccessible = true
            hook(wakeMethod, after { chain, result ->
                val ctrl = chain.thisObject
                try {
                    canDismissField.setBoolean(ctrl, true)
                    log("SystemUI/LockScreen: forced mCanDismissLockScreen=true")
                } catch (_: Exception) {}
                result
            })
            log("SystemUI: hooked KeyguardStateControllerImpl.onStartedWakingUp")
        }.onFailure { log("SystemUI: KeyguardStateControllerImpl hook failed", it) }
    }
}
