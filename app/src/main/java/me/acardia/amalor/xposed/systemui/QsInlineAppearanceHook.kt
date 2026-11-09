package me.acardia.amalor.xposed.systemui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import me.acardia.amalor.NativeFeatureOptions
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** QS inline content is drawn by a remote service; its theme travels in the host IPC extras. */
internal class QsInlineAppearanceHook(
    private val r: NativeFeatures,
    private val isDetail: (View) -> Boolean,
    private val detailContext: (View) -> Context,
) {
    private val hosts = WeakHashMap<Any, WeakReference<View>>()
    private var refreshHosts: (() -> Unit)? = null
    private var reported = false

    fun refresh() = refreshHosts?.invoke()

    fun install() {
        val host = r.type("com.oplus.sysuiinlineview.host.view.SysUiInlineHostView")
        val controller = r.type("com.oplus.sysuiinlineview.host.controller.SysUiInlineHostViewController")
        val getController = host.getDeclaredMethod("getController")
        val extras = controller.getDeclaredMethod("getBaseExtras")
        val changed = controller.getDeclaredMethod("notifyHostViewConfigurationChanged", Configuration::class.java)
        fun applyTheme(owner: Any, view: View): Configuration {
            val configuration = detailContext(view).resources.configuration
            val bundle = extras.invoke(owner) as Bundle
            QsAppearanceRules.inlineThemeFlags(configuration.isNightModeActive).forEach { (name, value) ->
                bundle.putBoolean(name, value)
            }
            return configuration
        }
        fun prepare(view: View) {
            if (!r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE) || !isDetail(view)) return
            val owner = getController.invoke(view) ?: return
            synchronized(hosts) { hosts[owner] = WeakReference(view) }
            applyTheme(owner, view)
        }
        for (name in listOf("onAttachedToWindow", "onHostViewCreate", "onHostViewLayout", "updateRunning")) {
            val method = host.getDeclaredMethod(name)
            r.hook("qs-appearance:inline-host:$name", method) { chain ->
                prepare(chain.thisObject as View)
                chain.proceed()
            }
            r.module.deoptimize(method)
        }
        val config = host.getDeclaredMethod("onConfigurationChanged", Configuration::class.java)
        r.hook("qs-appearance:inline-host-config", config) { chain ->
            prepare(chain.thisObject as View)
            chain.proceed()
        }
        r.module.deoptimize(config)
        val names = setOf("notifyHostViewCreated", "notifyHostViewLaidOut", "notifyHostViewConfigurationChanged", "updateRunning")
        for (method in controller.declaredMethods.filter { it.name in names }) {
            r.hook("qs-appearance:inline-theme:${method.name}", method) { chain ->
                val view = synchronized(hosts) { hosts[chain.thisObject]?.get() }
                if (view == null || !isDetail(view) || !r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE))
                    return@hook chain.proceed()
                val configuration = applyTheme(requireNotNull(chain.thisObject), view)
                if (!reported) {
                    reported = true
                    r.info("QS remote detail theme applied: night=${configuration.isNightModeActive}")
                }
                if (method == changed) {
                    val incoming = Configuration(chain.getArg(0) as Configuration)
                    incoming.uiMode = (incoming.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    chain.proceed(arrayOf(incoming))
                } else chain.proceed()
            }
            r.module.deoptimize(method)
        }
        refreshHosts = {
            synchronized(hosts) { hosts.entries.map { it.key to it.value.get() } }.forEach { (owner, view) ->
                if (view != null && view.isAttachedToWindow && isDetail(view))
                    changed.invoke(owner, Configuration(detailContext(view).resources.configuration))
            }
        }
    }
}
