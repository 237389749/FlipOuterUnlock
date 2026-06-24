package com.example.flipunlock.hook.system

import android.content.ComponentName
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

object InterceptHook {

    fun hook(param: SystemServerStartingParam) {
        safeHook("InterceptHook") {
            hookIsInterceptListUnCheckFold(param)
            hookIsInterceptListForProperty(param)
        }
    }

    private fun hookIsInterceptListUnCheckFold(param: SystemServerStartingParam) {
        runCatching {
            val interceptClass = param.classLoader.findClass(
                "com.android.server.wm.InterceptActivityController"
            )
            val method = interceptClass.method(
                "isInterceptListUnCheckFold", ComponentName::class.java
            )
            hook(method, replaceResult(false))
            log("forced isInterceptListUnCheckFold -> false")
        }.onFailure { log("failed to hook isInterceptListUnCheckFold", it) }
    }

    private fun hookIsInterceptListForProperty(param: SystemServerStartingParam) {
        runCatching {
            val interceptClass = param.classLoader.findClass(
                "com.android.server.wm.InterceptActivityController"
            )
            val method = interceptClass.method(
                "isInterceptListForProperty", ComponentName::class.java, String::class.java
            )
            val classLoader = param.classLoader
            hook(method, Hooker { chain ->
                runCatching {
                    val pairClass = classLoader.loadClass("android.util.Pair")
                    val pairConstructor = pairClass.getConstructor(Any::class.java, Any::class.java)
                    pairConstructor.newInstance(false, false)
                }.getOrElse { chain.proceed() }
            })
            log("forced isInterceptListForProperty -> Pair(false, false)")
        }.onFailure { log("failed to hook isInterceptListForProperty", it) }
    }
}
