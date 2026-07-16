package com.example.flipunlock.hook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Sogou IME fix for outer screen — restore toolbar and clipboard visibility.
 * Exact port of MixFlipMod's SogouHook.kt.
 *
 * Uses DexKit to locate the FlipScreenManager class by the string constant
 * "flip_old_outer_keyboard", then finds isFlipScreen() within it. This
 * ensures we hook the correct isFlipScreen method, not any other method
 * with the same name.
 */
object SogouInputHook : BaseHook() {
    override val targetPackages = listOf("com.sohu.inputmethod.sogou.xiaomi")

    override fun setupHooks(param: PackageReadyParam) {
        log("SogouInputHook: loading for ${param.packageName}")
        safeHook("SogouInputHook") {
            hookToolbarFix(param)
            hookClipboardFix(param)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun findManagerClass(bridge: DexKitBridge, classLoader: ClassLoader): Class<*> {
        val found = bridge.findClass {
            matcher { usingStrings("flip_old_outer_keyboard") }
        }.singleOrNull()?.getInstance(classLoader)
        if (found == null) log("SogouFix: FlipScreenManager class not found — wrong Sogou version?")
        return found ?: error("FlipScreenManager not found")
    }

    private fun findIsFlipScreen(bridge: DexKitBridge, classLoader: ClassLoader, managerClass: Class<*>): Method {
        val found = bridge.findMethod {
            matcher {
                declaredClass(managerClass.name)
                invokeMethods { add { name = "isFlipScreen" } }
                returnType = "boolean"
                paramCount = 0
            }
        }.firstNotNullOfOrNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
        if (found == null) log("SogouFix: isFlipScreen not found in ${managerClass.name}")
        return found ?: error("isFlipScreen not found")
    }

    private fun hookWithFakeFlipScreen(
        target: Method,
        fakeFlipScreen: HookScope,
        after: ((Any?) -> Any?)? = null,
    ) {
        hook(target) { chain ->
            val result = fakeFlipScreen.run { chain.proceed() }
            after?.invoke(result) ?: result
        }
    }

    // ── Toolbar fix ────────────────────────────────────────────────────

    private fun hookToolbarFix(param: PackageReadyParam) {
        createDexKitBridge(param.classLoader).use { bridge ->
            val managerClass = findManagerClass(bridge, param.classLoader)
            val isFlipScreen = findIsFlipScreen(bridge, param.classLoader, managerClass)
            val fakeFlipScreen = hookScope(isFlipScreen) { false }

            // buildFunctionList: calls getFlipOrderList + isFlipScreen
            val buildFunctionList = bridge.findMethod {
                matcher {
                    invokeMethods {
                        add { name = "getFlipOrderList" }
                        add { name = "isFlipScreen" }
                    }
                }
            }.singleOrNull()?.getMethodInstance(param.classLoader)
                ?: error("buildFunctionList not found")

            // getSingleton: static, returns managerClass, 0 params
            val getSingleton = managerClass.declaredMethods.firstOrNull { m ->
                Modifier.isStatic(m.modifiers) && m.returnType == managerClass && m.parameterCount == 0
            } ?: error("getSingleton not found")

            hookWithFakeFlipScreen(buildFunctionList, fakeFlipScreen) { result ->
                runCatching {
                    if (isFlipScreen.invoke(getSingleton.invoke(null)) as Boolean) {
                        @Suppress("UNCHECKED_CAST")
                        val list = result as? ArrayList<Any>
                        if (!list.isNullOrEmpty()) {
                            val idField = runCatching {
                                list[0].javaClass.getDeclaredField("f").also { it.isAccessible = true }
                            }.onFailure { log("SogouFix: toolbar idField lookup failed", it) }.getOrNull()
                            if (idField != null) {
                                list.removeIf { item -> idField.get(item) as? Int in listOf(6, 1052) }
                            }
                        }
                    }
                }.onFailure { log("SogouFix: hookToolbarFix after failed", it) }
                result
            }

            // refreshFunctionList: calls isUpdateFlipImeFunction, same declaring class as buildFunctionList
            val refreshFunctionList = bridge.findMethod {
                matcher {
                    declaredClass(buildFunctionList.declaringClass.name)
                    invokeMethods { add { name = "isUpdateFlipImeFunction" } }
                }
            }.singleOrNull()?.getMethodInstance(param.classLoader)
                ?: error("refreshFunctionList not found")

            hookWithFakeFlipScreen(refreshFunctionList, fakeFlipScreen)
            log("SogouFix: toolbar fix hooked")
        }
    }

    // ── Clipboard fix ──────────────────────────────────────────────────

    private fun hookClipboardFix(param: PackageReadyParam) {
        createDexKitBridge(param.classLoader).use { bridge ->
            val managerClass = findManagerClass(bridge, param.classLoader)
            val isFlipScreen = findIsFlipScreen(bridge, param.classLoader, managerClass)

            // onCandidateChange: string "ClipboardToCandsController onCandidateChange" + calls isFlipScreen
            val onCandidateChange = bridge.findMethod {
                matcher {
                    usingStrings("ClipboardToCandsController onCandidateChange")
                    invokeMethods { add { name = "isFlipScreen" } }
                }
            }.singleOrNull()?.getMethodInstance(param.classLoader)
                ?: error("onCandidateChange not found")

            val containerClass = param.classLoader.loadClass(
                "com.sohu.inputmethod.main.view.IMEInputCandidateViewContainer"
            )

            val fakeClipboard = hookScope(isFlipScreen) { false }
                .stopOn(containerClass.method("showClipboardFirstCandidate"))
            hook(onCandidateChange) { chain ->
                fakeClipboard.run { chain.proceed() }
            }

            // showFunctionOrClipboard: void, 0 params, calls both show methods
            val showFunctionOrClipboard = bridge.findMethod {
                matcher {
                    returnType = "void"
                    paramCount = 0
                    invokeMethods {
                        add { name = "showIMEFunctionOrFirstClipboardView" }
                        add { name = "showIMEFunctionCandidateView" }
                    }
                }
            }.mapNotNull { runCatching { it.getMethodInstance(param.classLoader) }.getOrNull() }
                .firstOrNull {
                    it.declaringClass.name.startsWith("com.sohu.inputmethod.main.manager.") &&
                        Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers)
                } ?: error("showFunctionOrClipboard not found")

            val fakeFunction = hookScope(isFlipScreen) { false }
                .stopOn(containerClass.method("showIMEFunctionOrFirstClipboardView"))
            hook(showFunctionOrClipboard) { chain ->
                fakeFunction.run { chain.proceed() }
            }

            log("SogouFix: clipboard fix hooked")
        }
    }
}
