package me.acardia.amalor.xposed.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import me.acardia.amalor.NativeFeatureOptions
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Independent-icon subset of ONextBox DataSimSignalHooker (GPL-3.0). */
internal class DataSimSignalHook(private val r: NativeFeatures) {
    private val key = NativeFeatureOptions.HIDE_NON_DATA_SIM
    private val main = Handler(Looper.getMainLooper())
    private data class Entry(val source: List<Int>, val rendered: List<Int>)
    private val controllers = WeakHashMap<Any, Entry>()
    private var update: Method? = null
    private var receiverContext: Context? = null
    private var reported = false
    private val refresh = Runnable {
        val snapshots = synchronized(controllers) { controllers.entries.map { it.key to it.value } }
        snapshots.forEach { (target, entry) ->
            val selected = DataSimSignalRules.select(entry.source, r.enabled(key), dataSubId())
            if (selected != entry.rendered) runCatching {
                update?.invoke(target, ArrayList(entry.source))
            }.onFailure { report("Data-SIM icon refresh failed", it) }
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DATA_CHANGED) {
                main.removeCallbacks(refresh)
                main.post(refresh)
            }
        }
    }

    fun install() {
        val controller = r.type("com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl")
        val method = controller.getDeclaredMethod("setNewMobileIconSubIds", List::class.java)
        val context = r.field(controller, "mContext")
        update = method
        r.hook("data-sim:independent-icons", method) { chain ->
            val target = requireNotNull(chain.thisObject)
            val source = (chain.getArg(0) as? List<*>)?.map {
                (it as? Number)?.toInt() ?: return@hook chain.proceed()
            } ?: return@hook chain.proceed()
            val selected = DataSimSignalRules.select(source, r.enabled(key), dataSubId())
            val previous = synchronized(controllers) {
                controllers.put(target, Entry(source.toList(), selected.toList()))
            }
            if (source != selected && previous?.rendered != selected)
                r.debug("Independent data-SIM icons: ${selected.size} of ${source.size} subscriptions")
            // Native code reverses its argument in place; source snapshots must stay intact.
            chain.proceed(arrayOf(selected)).also { attach((context.get(target) as Context).applicationContext) }
        }
        r.module.deoptimize(method)
    }

    private fun dataSubId(): Int = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)

    private fun attach(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { attach(context) }; return }
        if (receiverContext != null) return
        runCatching {
            context.registerReceiver(receiver, IntentFilter(DATA_CHANGED), Context.RECEIVER_EXPORTED)
            receiverContext = context
        }.onFailure { report("Data-SIM change listener unavailable", it) }
    }

    private fun report(message: String, error: Throwable) {
        if (!reported) { reported = true; r.error(message, error.cause ?: error) }
    }

    private companion object {
        const val DATA_CHANGED = "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED"
    }
}

internal object DataSimSignalRules {
    /** The native stacked pipeline supplies an empty list; never introduce an independent holder there. */
    fun select(ids: List<Int>, enabled: Boolean, dataSubId: Int): ArrayList<Int> = ArrayList(
        if (enabled && ids.size > 1 && dataSubId >= 0 && dataSubId in ids) listOf(dataSubId) else ids,
    )
}
