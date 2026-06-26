package com.example.flipunlock.hook.system

import android.content.Context
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

object InputMethodHook {

    fun hook(param: SystemServerStartingParam) {
        safeHook("InputMethodHook") {
            hookShouldShowCurrentInput(param)
            hookMakeRotateToast(param)
        }
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
}
