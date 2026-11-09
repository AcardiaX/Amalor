package me.acardia.amalor.xposed.systemui

import android.content.Context
import io.github.libxposed.api.XposedModule
import me.acardia.amalor.NativeFeatureOptions

/** Native SystemUI adapters ported from ONextBox (GPL-3.0). */
class NativeFeatureHooks(module: XposedModule, loader: ClassLoader) {
    private val runtime = NativeFeatures(module, loader)
    private val backPanel by lazy { AospBackPanelHook(runtime) }
    private val backHaptics by lazy { BackGestureHapticsHook(runtime) }
    private val backPrediction by lazy { BackGesturePredictionHook(runtime) }
    private val dataSimSignal by lazy { DataSimSignalHook(runtime) }
    private var powerProvider: Any? = null
    private var powerDialog: Any? = null
    private val clipboardCall = ThreadLocal.withInitial { 0 }

    fun install() {
        runtime.install(NativeFeatureOptions.POWER_MENU, ::installPowerMenu)
        runtime.install(NativeFeatureOptions.CLIPBOARD, ::installClipboard)
        val nativeBack = runtime.enabled(NativeFeatureOptions.BACK_PANEL)
        runtime.install(NativeFeatureOptions.BACK_PANEL) { backPanel.install() }
        runtime.install("native_back_haptics", nativeBack) { backHaptics.install() }
        runtime.install(NativeFeatureOptions.BACK_PREDICTIVE_ANIMATION,
            nativeBack || runtime.enabled(NativeFeatureOptions.BACK_PREDICTIVE_ANIMATION)) { backPrediction.install() }
        runtime.install(NativeFeatureOptions.VOLUME_PANEL) { AospVolumeHook(runtime).install() }
        runtime.install(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE) { QsTileAppearanceHook(runtime).install() }
        runtime.install(NativeFeatureOptions.HIDE_NON_DATA_SIM) { dataSimSignal.install() }
    }


    private fun installPowerMenu() {
        val dialog = runtime.type("com.android.systemui.globalactions.GlobalActionsDialogLite")
        val show = dialog.declaredMethods.single { it.name == "showOrHideDialog" && it.parameterCount == 2 }
            .apply { isAccessible = true }
        val hostType = runtime.type("com.android.systemui.globalactions.GlobalActionsImpl")
        val context = runtime.field(hostType, "mContext")
        dialog.declaredConstructors.forEach { constructor ->
            runtime.hook("power:dialog:$constructor", constructor) { chain ->
                chain.proceed().also { powerDialog = chain.thisObject }
            }
        }
        listOf(
            "com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel\$Factory",
            "com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel",
        ).forEach { name ->
            val holder = runCatching { runtime.type(name) }.getOrNull() ?: return@forEach
            val provider = runtime.field(holder, "globalActionsDialogLiteProvider")
            holder.declaredConstructors.forEach { constructor ->
                runtime.hook("power:provider:$constructor", constructor) { chain ->
                    chain.proceed().also { powerProvider = provider.get(chain.thisObject) }
                }
            }
        }
        val routes = hostType.declaredMethods.filter { it.name == "showGlobalActions" || it.name == "showOrHideGlobalActions" }
        check(routes.isNotEmpty())
        routes.forEach { method ->
            runtime.hook("power:route:$method", method) { chain ->
                if (!runtime.enabled(NativeFeatureOptions.POWER_MENU)) chain.proceed()
                else {
                    val shown = runCatching {
                        val target = powerDialog ?: powerProvider?.let {
                            it.javaClass.getMethod("get").apply { isAccessible = true }.invoke(it)
                        }?.also { powerDialog = it }
                        if (target == null || !dialog.isInstance(target)) false else {
                            val ctx = context.get(chain.thisObject) as Context
                            val displayId = Context::class.java.getMethod("getDisplayId").invoke(ctx)
                            show.invoke(target, null, displayId)
                            true
                        }
                    }.getOrElse { powerDialog = null; runtime.info("Native power menu fallback: $it"); false }
                    if (shown) null else chain.proceed()
                }
            }
        }
    }

    private fun installClipboard() {
        val listener = runtime.type("com.android.systemui.clipboardoverlay.ClipboardListener")
        val option = runtime.type("com.oplusos.systemui.common.feature.FeatureOption")
        runtime.hook("clipboard:scope", listener.getDeclaredMethod("onPrimaryClipChanged")) { chain ->
            val previous = clipboardCall.get() ?: 0
            clipboardCall.set(previous + 1)
            try { chain.proceed() } finally {
                if (previous == 0) clipboardCall.remove() else clipboardCall.set(previous)
            }
        }
        runtime.hook("clipboard:region", option.getDeclaredMethod("isExpRegion")) { chain ->
            val original = chain.proceed()
            if ((clipboardCall.get() ?: 0) > 0 && runtime.enabled(NativeFeatureOptions.CLIPBOARD)) true else original
        }
        runtime.module.deoptimize(listener.getDeclaredMethod("onPrimaryClipChanged"))
    }
}
