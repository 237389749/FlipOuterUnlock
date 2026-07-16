package com.example.flipunlock.hook.system

import android.content.Context
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

object InputMethodHook {

    fun hook(param: SystemServerStartingParam) {
        log("InputMethodHook: setting up")
        safeHook("InputMethodHook") {
            hookShouldShowCurrentInput(param)
            hookMakeRotateToast(param)
            hookIsFlipTinyScreen(param)
        }
        log("InputMethodHook: done")
    }

    private fun hookShouldShowCurrentInput(param: SystemServerStartingParam) {
        runCatching {
            val immServiceClass = param.classLoader.loadClass(
                "com.android.server.inputmethod.InputMethodManagerServiceImpl"
            )
            val method = immServiceClass.method("shouldShowCurrentInput", Context::class.java)
            hook(method, replaceResult(true))
            log("forced shouldShowCurrentInput -> true")
        }.onFailure { log("failed to hook shouldShowCurrentInput", it) }
    }

    private fun hookMakeRotateToast(param: SystemServerStartingParam) {
        runCatching {
            val immServiceClass = param.classLoader.loadClass(
                "com.android.server.inputmethod.InputMethodManagerServiceImpl"
            )
            val method = immServiceClass.method("makeRotateToast")
            hook(method, replaceResult(null))
            log("suppressed makeRotateToast")
        }.onFailure { log("failed to hook makeRotateToast", it) }
    }

    // ── Unlock IME: prevent forced Sogou switch on outer screen ─────────
    // SogouInputMethodSwitcher.mayChangeInputMethodLocked() checks
    // InputMethodManagerServiceImpl.getInstance().isFlipTinyScreen()
    // to decide whether to auto-switch to Sogou. This method may not
    // go through MiuiConfigs — it could be independently implemented.
    // Force false to allow any IME on the outer screen.
    private fun hookIsFlipTinyScreen(param: SystemServerStartingParam) {
        runCatching {
            val immServiceClass = param.classLoader.loadClass(
                "com.android.server.inputmethod.InputMethodManagerServiceImpl"
            )
            val method = immServiceClass.method("isFlipTinyScreen")
            hook(method, replaceResult(false))
            log("forced InputMethodManagerServiceImpl.isFlipTinyScreen -> false")
        }.onFailure { log("failed to hook isFlipTinyScreen", it) }
    }
}
