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

internal inline fun <T> runWithCleanup(cleanup: () -> Unit, block: () -> T): T {
    return runCatching(block).also { cleanup() }.getOrThrow()
}

internal fun hookScope(origin: Executable, activeHooker: (Chain) -> Any?): HookScope {
    val active = ThreadLocal<Boolean>()
    hook(origin) { chain ->
        if (active.get() == true) activeHooker(chain) else chain.proceed()
    }
    return HookScope(active)
}

internal class HookScope(private val active: ThreadLocal<Boolean>) {
    fun <T> run(block: () -> T): T {
        active.set(true)
        return runWithCleanup({ active.remove() }, block)
    }
}
