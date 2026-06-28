package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge

/**
 * Port of MixFlipMod's SogouHook — fixes Sogou IME toolbar and clipboard
 * visibility on the external/flip screen.
 *
 * Uses DexKit to locate obfuscated methods in the Sogou IME APK
 * (com.sohu.inputmethod.sogou.xiaomi). The isFlipScreen() method inside
 * Sogou detects flip mode and collapses the keyboard to a single toolbar
 * row. We hook it to return false within scoped contexts.
 *
 * Hooks applied:
 * 1. Toolbar fix: fake isFlipScreen->false while building function list,
 *    then remove toolbar items 6 and 1052 (voice input, split keyboard)
 * 2. Clipboard fix: fake isFlipScreen->false during clipboard candidate
 *    and function/clipboard view transitions
 */
object SogouInputHook : BaseHook() {
    override val targetPackages = listOf("com.sohu.inputmethod.sogou.xiaomi")

    override fun setupHooks(param: PackageReadyParam) {
        safeHook("SogouInputHook") {
            DexKitBridge.create(param.classLoader, false).use { bridge ->
                hookToolbarFix(param, bridge)
                hookClipboardFix(param, bridge)
            }
        }
    }

    // ── Toolbar fix ────────────────────────────────────────────────────
    private fun hookToolbarFix(param: PackageReadyParam, bridge: DexKitBridge) {
        runCatching {
            // Find manager class with string "flip_old_outer_keyboard"
            val managerClass = bridge.findClass {
                matcher { usingStrings("flip_old_outer_keyboard") }
            }.singleOrNull()?.getInstance(param.classLoader) ?: return

            // Find isFlipScreen method: boolean, 0 params, calls itself
            val isFlipScreen = bridge.findMethod {
                matcher {
                    declaredClass(managerClass.name)
                    invokeMethods { add { name = "isFlipScreen" } }
                    returnType = "boolean"
                    paramCount = 0
                }
            }.firstNotNullOfOrNull { runCatching { it.getMethodInstance(param.classLoader) }.getOrNull() }
                ?: return

            // Create scoped fake: isFlipScreen -> false
            val fakeFlipScreen = hookScope(isFlipScreen) { false }

            // Find buildFunctionList: calls getFlipOrderList + isFlipScreen
            val buildFunctionList = bridge.findMethod {
                matcher {
                    invokeMethods {
                        add { name = "getFlipOrderList" }
                        add { name = "isFlipScreen" }
                    }
                }
            }.firstNotNullOfOrNull { runCatching { it.getMethodInstance(param.classLoader) }.getOrNull() }
                ?: return

            // Find getSingleton: static, returns manager class, 0 params
            val getSingleton = managerClass.declaredMethods.firstOrNull { m ->
                java.lang.reflect.Modifier.isStatic(m.modifiers)
                    && m.returnType == managerClass
                    && m.parameterCount == 0
            }?.also { it.isAccessible = true }

            // Hook buildFunctionList: run in fake scope, then remove items 6, 1052
            hook(buildFunctionList, Hooker { chain ->
                val result = fakeFlipScreen.run { chain.proceed() }
                val singleton = getSingleton?.invoke(null)
                if (singleton != null) {
                    val isFlip = isFlipScreen.invoke(singleton) as? Boolean ?: false
                    if (isFlip && result is MutableList<*>) {
                        runCatching {
                            val idField = (result.firstOrNull() ?: return@runCatching)
                                .javaClass.getDeclaredField("f").also { it.isAccessible = true }
                            result.removeIf { idField.get(it) as? Int in listOf(6, 1052) }
                        }
                    }
                }
                result
            })
            log("SogouFix: hooked toolbar fix")

            // Find refreshFunctionList: calls isUpdateFlipImeFunction
            val refreshFunctionList = bridge.findMethod {
                matcher {
                    declaredClass(buildFunctionList.declaringClass.name)
                    invokeMethods { add { name = "isUpdateFlipImeFunction" } }
                }
            }.firstNotNullOfOrNull { runCatching { it.getMethodInstance(param.classLoader) }.getOrNull() }

            if (refreshFunctionList != null) {
                hook(refreshFunctionList) { chain ->
                    fakeFlipScreen.run { chain.proceed() }
                }
                log("SogouFix: hooked refreshFunctionList")
            }
        }.onFailure { log("SogouFix: toolbar fix failed", it) }
    }

    // ── Clipboard fix ──────────────────────────────────────────────────
    private fun hookClipboardFix(param: PackageReadyParam, bridge: DexKitBridge) {
        runCatching {
            // Find the same manager class
            val managerClass = bridge.findClass {
                matcher { usingStrings("flip_old_outer_keyboard") }
            }.singleOrNull()?.getInstance(param.classLoader) ?: return

            // Find isFlipScreen (same as above)
            val isFlipScreen = bridge.findMethod {
                matcher {
                    declaredClass(managerClass.name)
                    invokeMethods { add { name = "isFlipScreen" } }
                    returnType = "boolean"
                    paramCount = 0
                }
            }.firstNotNullOfOrNull { runCatching { it.getMethodInstance(param.classLoader) }.getOrNull() }
                ?: return

            // Find onCandidateChange: string "ClipboardToCandsController onCandidateChange" + calls isFlipScreen
            val onCandidateChange = bridge.findMethod {
                matcher {
                    usingStrings("ClipboardToCandsController onCandidateChange")
                    invokeMethods { add { name = "isFlipScreen" } }
                }
            }.firstNotNullOfOrNull { runCatching { it.getMethodInstance(param.classLoader) }.getOrNull() }

            // Get the stop method class
            val stopClass = runCatching {
                param.classLoader.loadClass(
                    "com.sohu.inputmethod.main.view.IMEInputCandidateViewContainer"
                )
            }.getOrNull()

            if (onCandidateChange != null && stopClass != null) {
                val showClipboard = runCatching {
                    stopClass.method("showClipboardFirstCandidate")
                }.getOrNull()
                val fakeUntilClipboard = if (showClipboard != null) {
                    hookScope(isFlipScreen) { false }.stopOn(showClipboard)
                } else {
                    hookScope(isFlipScreen) { false }
                }

                hook(onCandidateChange) { chain ->
                    fakeUntilClipboard.run { chain.proceed() }
                }
                log("SogouFix: hooked clipboard candidate fix")
            }

            // Find showFunctionOrClipboard: void, 0 params, calls showIMEFunctionOrFirstClipboardView + showIMEFunctionCandidateView
            val showFunctionOrClipboard = bridge.findMethod {
                matcher {
                    returnType = "void"
                    paramCount = 0
                    invokeMethods {
                        add { name = "showIMEFunctionOrFirstClipboardView" }
                        add { name = "showIMEFunctionCandidateView" }
                    }
                }
            }.firstOrNull {
                it.declaringClass.name.startsWith("com.sohu.inputmethod.main.manager.")
                    && java.lang.reflect.Modifier.isPublic(it.modifiers)
                    && !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }?.getMethodInstance(param.classLoader)

            if (showFunctionOrClipboard != null && stopClass != null) {
                val showIMEFunction = runCatching {
                    stopClass.method("showIMEFunctionOrFirstClipboardView")
                }.getOrNull()
                val fakeUntilFunction = if (showIMEFunction != null) {
                    hookScope(isFlipScreen) { false }.stopOn(showIMEFunction)
                } else {
                    hookScope(isFlipScreen) { false }
                }

                hook(showFunctionOrClipboard) { chain ->
                    fakeUntilFunction.run { chain.proceed() }
                }
                log("SogouFix: hooked clipboard function fix")
            }
        }.onFailure { log("SogouFix: clipboard fix failed", it) }
    }
}
