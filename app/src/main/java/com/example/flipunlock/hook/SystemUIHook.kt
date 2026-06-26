package com.example.flipunlock.hook

import android.content.ComponentName
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * SystemUI-side hooks for widget overlay suppression and other
 * SystemUI-specific behavior on the external display.
 *
 * DecorWindowManagerImpl.shouldHideDecorWindow() checks the
 * miui.supportFlipWatchOverlayGroupView property from the SystemUI side.
 * If our fliphome-side WatchOverlay hooks ever let the widget through
 * (e.g. animation cancellation edge case from ref docs), this provides
 * a second layer of defense.
 */
object SystemUIHook : BaseHook() {
    override val targetPackages = listOf("com.android.systemui")

    override fun setupHooks(param: PackageReadyParam) {
        hookDecorWindowManager(param)
    }

    // ── DecorWindowManagerImpl.shouldHideDecorWindow ────────────────────
    // Returns true = hide widget, false = show widget.
    // We force true to always hide from SystemUI side.
    private fun hookDecorWindowManager(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.findClass(
                "com.android.notification.decor.DecorWindowManagerImpl"
            )
            val method = cls.method(
                "shouldHideDecorWindow", ComponentName::class.java
            )
            hook(method, replaceResult(true))
            log("SystemUI: forced DecorWindowManagerImpl.shouldHideDecorWindow -> true")
        }.onFailure { log("SystemUI: failed hook DecorWindowManagerImpl", it) }
    }
}
