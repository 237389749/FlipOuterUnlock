package com.example.flipunlock.hook.system

import android.content.ComponentName
import com.example.flipunlock.hook.util.*
import com.example.flipunlock.module
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import org.luckypray.dexkit.DexKitBridge

object CompatConfigHook {

    // Default flip IME package (Sogou Xiaomi edition)
    private const val DEFAULT_FLIP_IME_PKG = "com.sohu.inputmethod.sogou.xiaomi"

    fun hook(param: SystemServerStartingParam) {
        safeHook("CompatConfig") { hookCompatConfig(param) }
        safeHook("FlipContinuity") { hookFlipContinuity(param) }
        safeHook("FlipIme") { hookFlipIme(param) }
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

    // ── Flip IME: lock Sogou as preferred IME when folded ──────────────
    // From MixFlipMod hookFlipInputMethod. Hardcodes Sogou Xiaomi edition
    // as the flip IME. Uses DexKit to deoptimize callers so hooks take effect.
    private var inputMethodRepoClass: Class<*>? = null

    private fun hookFlipIme(param: SystemServerStartingParam) {
        runCatching {
            val switcher = param.classLoader.loadClass(
                "com.android.server.inputmethod.SogouInputMethodSwitcher"
            )
            val immsClass = param.classLoader.loadClass(
                "com.android.server.inputmethod.InputMethodManagerService"
            )
            val serviceImplClass = param.classLoader.loadClass(
                "com.android.server.inputmethod.InputMethodManagerServiceImpl"
            )
            inputMethodRepoClass = param.classLoader.loadClass(
                "com.android.server.inputmethod.InputMethodSettingsRepository"
            )

            val switcherIsSogou = switcher.method("isSogouMethodLocked", String::class.java)
            val serviceImplIsSogou = serviceImplClass.method(
                "isSogouMethodLocked", immsClass, String::class.java
            )

            // Deoptimize all callers via DexKit
            runCatching {
                DexKitBridge.create(param.classLoader, false).use { bridge ->
                    listOf(switcherIsSogou, serviceImplIsSogou).forEach { target ->
                        bridge.findMethod {
                            matcher {
                                invokeMethods {
                                    add {
                                        declaredClass(target.declaringClass.name)
                                        name = target.name
                                    }
                                }
                            }
                        }.forEach {
                            runCatching { it.getMethodInstance(param.classLoader) }
                                .getOrNull()?.let { module?.deoptimize(it) }
                        }
                    }
                }
            }

            // Hook: return true if current IME matches the flip IME package
            hook(switcherIsSogou) { chain ->
                val methodId = chain.args[0] as? String ?: return@hook chain.proceed()
                val userId = chain.thisObject?.getField("mService")?.getField("mCurrentImeUserId") as? Int
                    ?: return@hook chain.proceed()
                imePackage(methodId, userId)?.let { it == DEFAULT_FLIP_IME_PKG }
                    ?: chain.proceed()
            }

            hook(serviceImplIsSogou) { chain ->
                val service = chain.args[0] ?: return@hook chain.proceed()
                val methodId = chain.args[1] as? String ?: return@hook chain.proceed()
                val userId = service.getField("mCurrentImeUserId") as? Int
                    ?: return@hook chain.proceed()
                imePackage(methodId, userId)?.let { it == DEFAULT_FLIP_IME_PKG }
                    ?: chain.proceed()
            }
        }.onFailure { log("CompatConfig: hookFlipIme failed", it) }
    }

    private fun imePackage(methodId: String, userId: Int): String? {
        val repo = inputMethodRepoClass ?: return null
        val settings = repo.method("get", Int::class.java).invoke(null, userId) ?: return null
        val imi = settings.callMethod("getMethodMap")?.callMethod("get", methodId) ?: return null
        return imi.callMethod("getPackageName") as? String
    }
}
