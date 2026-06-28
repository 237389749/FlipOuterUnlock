package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

/**
 * Sogou IME fix for outer screen — restore toolbar and clipboard visibility.
 * Ported from MixFlipMod's SogouHook.
 *
 * Uses DexKit to find isFlipScreen() (boolean, 0 params) across all Sogou classes.
 * Does NOT depend on specific string constants — searches by method signature only,
 * so it works across different Sogou versions.
 */
object SogouInputHook : BaseHook() {
    override val targetPackages = listOf("com.sohu.inputmethod.sogou.xiaomi")

    override fun setupHooks(param: PackageReadyParam) {
        safeHook("SogouInputHook") {
            DexKitBridge.create(param.classLoader, false).use { bridge ->
                // Find isFlipScreen: boolean, 0 params, anywhere in Sogou
                val candidates = bridge.findMethod {
                    matcher {
                        returnType = "boolean"
                        paramCount = 0
                    }
                }.mapNotNull { runCatching { it.getMethodInstance(param.classLoader) }.getOrNull() }
                    .filter { m -> m.name == "isFlipScreen" && !Modifier.isStatic(m.modifiers) }
                    .distinctBy { it.declaringClass }

                val isFlipScreen = candidates.firstOrNull()
                if (isFlipScreen == null) {
                    log("SogouFix: isFlipScreen not found, skipping")
                    return@safeHook
                }
                log("SogouFix: found isFlipScreen in ${isFlipScreen.declaringClass.name}")

                val fakeFlipScreen = hookScope(isFlipScreen) { false }

                // ── Toolbar fix ────────────────────────────────────────
                val toolbarMethods = bridge.findMethod {
                    matcher {
                        invokeMethods { add { name = "isFlipScreen" } }
                    }
                }.mapNotNull { runCatching { it.getMethodInstance(param.classLoader) }.getOrNull() }

                // Hook all methods that call isFlipScreen for toolbar build
                for (m in toolbarMethods) {
                    if (m.parameterCount == 0 && m.returnType == Void.TYPE) {
                        // Likely buildFunctionList or refreshFunctionList
                        hook(m) { chain -> fakeFlipScreen.run { chain.proceed() } }
                        log("SogouFix: toolbar hook on ${m.declaringClass.simpleName}.${m.name}")
                    }
                }

                // Find buildFunctionList-like method (returns List or similar)
                val listBuildingMethods = toolbarMethods.filter { m ->
                    m.parameterCount in 0..2 && m.returnType != Void.TYPE
                }
                if (listBuildingMethods.isNotEmpty()) {
                    for (m in listBuildingMethods) {
                        hook(m, Hooker { chain ->
                            val result = fakeFlipScreen.run { chain.proceed() }
                            // Try to remove flip-only toolbar items (IDs 6, 1052)
                            if (result is MutableList<*>) {
                                runCatching {
                                    val first = result.firstOrNull() ?: return@runCatching
                                    val idField = first.javaClass.declaredFields
                                        .firstOrNull { it.type == Int::class.javaPrimitiveType!! }
                                        ?.also { it.isAccessible = true }
                                    if (idField != null) {
                                        result.removeIf { idField.get(it) as? Int in listOf(6, 1052) }
                                    }
                                }
                            }
                            result
                        })
                        log("SogouFix: list-building hook on ${m.declaringClass.simpleName}.${m.name}")
                    }
                }

                // ── Clipboard fix ──────────────────────────────────────
                // Find methods called during clipboard candidate transitions
                // that also check isFlipScreen — hook them to run in fake scope
                val clipboardMethods = bridge.findMethod {
                    matcher {
                        usingStrings("ClipboardToCandsController")
                        invokeMethods { add { name = "isFlipScreen" } }
                    }
                }.mapNotNull { runCatching { it.getMethodInstance(param.classLoader) }.getOrNull() }

                for (m in clipboardMethods) {
                    hook(m) { chain -> fakeFlipScreen.run { chain.proceed() } }
                    log("SogouFix: clipboard hook on ${m.declaringClass.simpleName}.${m.name}")
                }

                log("SogouFix: ${candidates.size} isFlipScreen methods, ${toolbarMethods.size} toolbar methods, ${clipboardMethods.size} clipboard methods hooked")
            }
        }
    }
}
