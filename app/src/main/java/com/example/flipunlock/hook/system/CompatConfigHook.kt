package com.example.flipunlock.hook.system

import android.content.ComponentName
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

object CompatConfigHook {

    fun hook(param: SystemServerStartingParam) {
        safeHook("CompatConfig") { hookCompatConfig(param) }
        safeHook("FlipContinuity") { hookFlipContinuity(param) }
    }

    private fun hookCompatConfig(param: SystemServerStartingParam) {
        val mgr = param.classLoader.loadClass("com.android.server.wm.ApplicationCompatManager")
        val props = setOf(
            "miui.continuity.policy",
            "android.window.PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN"
        )

        val propertyIntHook = Hooker { chain ->
            when (chain.args[0]) {
                "miui.continuity.policy" -> 5
                "android.window.PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN" -> 1
                else -> chain.proceed()
            }
        }
        hook(
            mgr.method("getPropertyIntByApplication", String::class.java, String::class.java),
            propertyIntHook
        )
        hook(
            mgr.method("getPropertyIntByActivity", String::class.java, ComponentName::class.java),
            propertyIntHook
        )

        val hasPropertyHook = Hooker { chain ->
            if (chain.args[0] in props) true else chain.proceed()
        }
        hook(
            mgr.method("hasPropertyByApplication", String::class.java, String::class.java),
            hasPropertyHook
        )
        hook(
            mgr.method("hasPropertyByActivity", String::class.java, ComponentName::class.java),
            hasPropertyHook
        )
    }

    private fun hookFlipContinuity(param: SystemServerStartingParam) {
        val c = param.classLoader.loadClass("com.android.server.wm.InterceptActivityController")
        hook(
            c.method("isFlipContinuityEnabledFromSetting", String::class.java, Int::class.java, String::class.java)
        ) { true }
    }
}
