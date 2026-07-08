package com.example.flipunlock.hook.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.InputStream

/**
 * Port of WhiteListHook.java — dynamically whitelists all installed apps
 * for external display continuity via the window service dump command.
 *
 * Mechanism (from ref docs, ContinuityPolicyService shell handler):
 *   adb shell dumpsys window -setForceDisplayCompatMode <pkg1:pkg2:...> allowstart
 *
 * The dump command writes to ContinuityPolicyService.LOCAL_COMMAND_ALLOW_START_SET
 * and LOCAL_POLICY_BY_COMMAND, which InterceptActivityController reads in
 * isInterceptListUnCheckFold() at priority 1 (highest).
 *
 * This hook runs in system_server (onSystemServerStarting) and:
 * 1. Whitelists all currently installed apps on boot
 * 2. Registers a BroadcastReceiver to re-whitelist on package install/remove
 */
object WhitelistHook {

    @Volatile
    private var isUpdating = false

    fun hook(param: SystemServerStartingParam) {
        safeHook("WhitelistHook") {
            runCatching {
                // Get system context via ActivityThread.systemMain()
                val activityThreadClass = param.classLoader.loadClass("android.app.ActivityThread")
                val systemContext = activityThreadClass.callMethod("systemMain") as? Context
                    ?: run { log("WhitelistHook: systemMain returned null"); return@safeHook }

                // Whitelist all installed apps
                updateWhitelist(systemContext)

                // Register receiver for package changes
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
                systemContext.registerReceiver(
                    PackageChangeReceiver(systemContext),
                    filter
                )
                log("WhitelistHook: registered package receiver, initial whitelist done")
            }.onFailure { log("WhitelistHook: init failed", it) }
        }
    }

    private fun updateWhitelist(context: Context) {
        if (isUpdating) return
        isUpdating = true
        Thread {
            try {
                val pm = context.packageManager
                val apps = pm.getInstalledApplications(0)
                val sb = StringBuilder()
                for (info in apps) {
                    sb.append(info.packageName).append(":")
                }
                if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
                val allApps = sb.toString()

                val smClass = Class.forName("android.os.ServiceManager")
                val windowBinder = smClass.callMethod("getService", "window") as IBinder
                val dumpMethod = windowBinder.javaClass.getMethod(
                    "dump", java.io.FileDescriptor::class.java, Array<String>::class.java
                )

                val pipe = ParcelFileDescriptor.createPipe()
                val input: InputStream = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
                val writer = dumpMethod
                try {
                    writer.invoke(
                        windowBinder,
                        pipe[1].fileDescriptor,
                        arrayOf("-setForceDisplayCompatMode", allApps, "allowstart")
                    )
                    pipe[1].close() // signal EOF to reader
                } catch (_: Exception) {
                    runCatching { pipe[1].close() }
                }

                // Drain with timeout
                val buffer = ByteArray(1024)
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline && input.read(buffer) != -1) { /* drain */ }
                input.close()

                log("WhitelistHook: whitelisted ${apps.size} apps")
            } catch (e: Exception) {
                log("WhitelistHook: update failed", e)
            } finally {
                isUpdating = false
            }
        }.start()
    }

    // ── BroadcastReceiver for package changes ─────────────────────────
    // Registered on the system context; re-whitelists when apps change.
    private class PackageChangeReceiver(private val ctx: Context) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Thread {
                try {
                    Thread.sleep(500) // Debounce
                    updateWhitelist(ctx)
                } catch (_: Exception) {}
            }.start()
        }
    }
}
