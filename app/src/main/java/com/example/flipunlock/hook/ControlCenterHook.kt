package com.example.flipunlock.hook

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Restore normal (non-compact) control center style on the outer screen.
 *
 * On flip devices, the control center plugin (miui.systemui.plugin) applies a
 * COMPACT style when isTinyScreen is true — collapsing tiles, hiding device
 * controls, and showing a minimal layout. This hook intercepts the plugin
 * classloader at creation time and replaces COMPACT with VERTICAL style,
 * restores QS tile long-press, fixes device center card dimensions, and
 * limits the device list to prevent overflow.
 *
 * Ported from MixFlipMod's SystemUIHook.hookControlCenter.
 */
object ControlCenterHook : BaseHook() {
    override val targetPackages = listOf("com.android.systemui")

    private val controlCenterComponents = setOf(
        ComponentName("miui.systemui.plugin", "miui.systemui.controlcenter.MiuiControlCenter"),
        ComponentName("miui.systemui.plugin", "miui.systemui.quicksettings.LocalMiuiQSTilePlugin"),
    )

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.uiControlCenter) {
            log("ControlCenterHook: DISABLED by persist.flipunlock.ui.controlcenter")
            return
        }
        log("ControlCenterHook: loading for ${param.packageName}")
        safeHook("ControlCenterHook") {
            hookPluginFactory(param)
        }
    }

    // ── Plugin factory interception ─────────────────────────────────────
    //
    // PluginInstance.PluginFactory.createPluginContext() creates a ContextWrapper
    // whose ClassLoader has access to the plugin APK classes. We intercept this
    // to hook plugin-internal classes when control center components are loaded.

    private fun hookPluginFactory(param: PackageReadyParam) {
        val factoryClass = param.classLoader.loadClass(
            "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory")

        hook(factoryClass.method("createPluginContext"), object : Hooker {
            private var isHooked = false

            override fun intercept(chain: Chain): Any? {
                val result = chain.proceed()
                val mComponentName = chain.thisObject?.getField("mComponentName") as? ComponentName
                    ?: return result
                if (isHooked) return result
                if (mComponentName !in controlCenterComponents) return result

                val pluginLoader = (result as? ContextWrapper)?.classLoader ?: return result
                isHooked = true
                log("ControlCenterHook: plugin loaded, installing internal hooks")

                runCatching {
                    installAllPluginHooks(pluginLoader)
                }.onFailure { log("ControlCenterHook: plugin init failed", it) }
                return result
            }
        })
    }

    // ── All plugin-internal hooks (shared isTinyScreen tracking) ─────────

    private fun installAllPluginHooks(pluginLoader: ClassLoader) {
        val styleClass = pluginLoader.loadClass(
            "miui.systemui.controlcenter.panel.main.MainPanelController\$Style")
        val compactStyle = styleClass.field("COMPACT").get(null)
        val verticalStyle = styleClass.field("VERTICAL").get(null)

        // Single isTinyScreen flag shared across all sub-hooks
        var isTinyScreen = false
        val panelClass = pluginLoader.loadClass(
            "miui.systemui.controlcenter.panel.main.MainPanelStyleController")

        // Track current style via set_style (only one hook)
        hook(panelClass.method("set_style", styleClass)) { styleChain ->
            isTinyScreen = styleChain.args[0] == compactStyle
            styleChain.proceed()
        }

        // ── 1. Style: COMPACT → VERTICAL ───────────────────────────────

        val fakeGetStyle = hookScope(panelClass.method("getStyle")) { styleChain ->
            if (isTinyScreen) verticalStyle else styleChain.proceed()
        }

        val controllerClasses = listOf(
            "miui.systemui.controlcenter.panel.main.qs.EditButtonController",
            "miui.systemui.controlcenter.panel.main.qs.QSListController",
            "miui.systemui.controlcenter.panel.main.qs.CompactQSListController",
            "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryController",
            "miui.systemui.controlcenter.panel.main.devicecontrol.DeviceControlsEntryController",
        )
        controllerClasses.forEach { clsName ->
            runCatching {
                val cls = pluginLoader.loadClass(clsName)
                hook(cls.method("available", Boolean::class.java)) { chain ->
                    fakeGetStyle.run { chain.proceed() }
                }
            }
        }
        log("ControlCenterHook: style hooks installed (COMPACT→VERTICAL)")

        // ── 2. QS tile long-press restore ──────────────────────────────

        runCatching {
            val tileClass = pluginLoader.loadClass(
                "miui.systemui.controlcenter.qs.tileview.QSTileItemView")
            hook(tileClass.method("onFinishInflate"), after { tileChain, tileResult ->
                (tileChain.thisObject as? FrameLayout)?.setOnLongClickListener { v ->
                    tileChain.thisObject
                        ?.getField("longClickAction")
                        ?.let { it.callMethod("invoke", v) as? Boolean }
                        ?: false
                }
                tileResult
            })
            log("ControlCenterHook: QS tile long-press restored")
        }.onFailure { log("ControlCenterHook: tile long-press failed", it) }

        // ── 3. Device center: dimension fix ────────────────────────────

        runCatching {
            val rdimenClass = pluginLoader.loadClass(
                "miui.systemui.controlcenter.R\$dimen")
            val targetId = rdimenClass.field("device_center_device_item_width").getInt(null)
            hook(Resources::class.java.method("getDimensionPixelSize", Int::class.java),
                after { dimenChain, dimenResult ->
                    if (isTinyScreen && dimenChain.args[0] == targetId) 245
                    else dimenResult
                })
        }

        // ── 4. Device center: ViewHolder width override ────────────────

        runCatching {
            val adapterClass = pluginLoader.loadClass(
                "miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceCenterCardController\$_adapter\$1")
            hook(adapterClass.method("onCreateViewHolder",
                ViewGroup::class.java, Int::class.java),
                after { _, holderResult ->
                    (holderResult?.getField("itemView") as? View)
                        ?.takeIf { isTinyScreen && it.layoutParams.width != -1 }
                        ?.let { it.layoutParams.width = 245 }
                    holderResult
                })
        }

        // ── 5. Device center: getMode() override ───────────────────────

        runCatching {
            val modeClass = pluginLoader.loadClass(
                "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryViewHolder\$Mode")
            val modeCollapsed = modeClass.field("MODE_COLLAPSED").get(null)
            val mode1row = modeClass.field("MODE_1_ROW").get(null)
            val mode2row = modeClass.field("MODE_2_ROWS").get(null)

            val cardCtrlClass = pluginLoader.loadClass(
                "miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceCenterCardController")
            hook(cardCtrlClass.method("getMode"), Hooker { modeChain ->
                if (!isTinyScreen) return@Hooker modeChain.proceed()
                val size = (modeChain.thisObject?.getField("deviceItems") as? ArrayList<*>)?.size
                    ?: return@Hooker modeChain.proceed()
                when {
                    size == 1 -> modeCollapsed
                    size < 4 -> mode1row
                    else -> mode2row
                }
            })
        }

        // ── 6. Device center: limit device list to 5 ───────────────────

        runCatching {
            val deviceCtrlClass = pluginLoader.loadClass(
                "miui.systemui.devicecenter.DeviceCenterController")
            hook(deviceCtrlClass.method("handleDeviceListUpdate", Boolean::class.java),
                Hooker { deviceChain ->
                    if (!isTinyScreen) return@Hooker deviceChain.proceed()
                    val deviceList = deviceChain.thisObject?.getField("deviceList") as? ArrayList<*>
                        ?: return@Hooker deviceChain.proceed()
                    if (deviceList.size <= 5) return@Hooker deviceChain.proceed()
                    deviceChain.thisObject?.setField("deviceList",
                        deviceList.subList(0, 5).toList())
                    runWithCleanup({ deviceChain.thisObject?.setField("deviceList", deviceList) }) {
                        deviceChain.proceed()
                    }
                })
        }

        log("ControlCenterHook: device center hooks installed")
    }
}
