package com.example.flipunlock.hook.util

import android.util.Log
import com.example.flipunlock.module
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable

private const val LOG_TAG = "FlipOuterUnlock"

internal fun safeHook(name: String, block: () -> Unit) {
    runCatching(block).onFailure { log("[$name] failed", it) }
}

internal fun log(msg: String, e: Throwable? = null) {
    module?.log(Log.ERROR, LOG_TAG, msg, e)
}

internal fun hook(origin: Executable, hooker: Hooker): HookHandle =
    module!!.hook(origin).intercept(hooker)

internal fun hook(origin: Executable, priority: Int, hooker: Hooker): HookHandle =
    module!!.hook(origin).setPriority(priority).intercept(hooker)

internal fun replaceResult(value: Any?): Hooker = Hooker { value }

internal fun after(block: (Chain, Any?) -> Any?): Hooker = Hooker { chain ->
    val result = chain.proceed()
    block(chain, result)
}

internal fun before(block: (Chain) -> Unit): Hooker = Hooker { chain ->
    block(chain)
    chain.proceed()
}
