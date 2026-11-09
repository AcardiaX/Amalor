package me.acardia.amalor.xposed.systemui

import android.os.Handler
import android.os.Looper
import me.acardia.amalor.NativeFeatureOptions
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/** Ported from ONextBox AospVolumeHooker and callback bridge (GPL-3.0). */
internal class AospVolumeHook(private val runtime: NativeFeatures) {
    private val bypass = ThreadLocal.withInitial { false }
    private val loader = runtime.loader

    fun install() {
        val type = runtime.type("com.oplus.systemui.dagger.DaggerOplusReferenceGlobalRootComponent\$OplusReferenceSysUIComponentImpl")
        val method = type.getDeclaredMethod("volumeDialog").apply { isAccessible = true }
        installCallbacks()
        runtime.hook("volume:factory", method) { chain ->
            if (bypass.get() == true || !runtime.enabled(NativeFeatureOptions.VOLUME_PANEL)) chain.proceed()
            else {
                val host = requireNotNull(chain.thisObject)
                val replacement = runCatching {
                    Session(AospVolumeGraph.create(host)) {
                        bypass.set(true)
                        try { requireNotNull(method.invoke(host)) } finally { bypass.remove() }
                    }.proxy()
                }.getOrElse { runtime.info("Native volume unavailable; retaining stock: $it"); null }
                replacement ?: chain.proceed()
            }
        }
    }

    private fun installCallbacks() {
        val callbacks = runtime.type("com.android.systemui.plugins.VolumeDialogController\$Callbacks")
        val state = runtime.type("com.android.systemui.plugins.VolumeDialogController\$State")
        val producer = runtime.type("com.android.systemui.volume.dialog.domain.interactor.VolumeDialogCallbacksInteractor\$VolumeDialogEventModelProducer")
        val update = producer.getMethod("onStateChanged", state)
        if (producer.getMethod("onStateChanged", state, Int::class.javaPrimitiveType!!).declaringClass != callbacks) return
        val dispatcher = runtime.type("com.android.systemui.volume.VolumeDialogControllerImpl\$C")
        val callbackMap = dispatcher.getDeclaredField("mCallbackMap").apply { isAccessible = true }
        val copy = state.getMethod("copy")
        val failed = AtomicBoolean()
        runtime.hook("volume:state", dispatcher.getDeclaredMethod("onStateChanged", state, Int::class.javaPrimitiveType!!)) { chain ->
            val result = chain.proceed()
            runCatching {
                val listeners = callbackMap.get(chain.thisObject) as Map<*, *>
                val source = chain.getArg(0)
                if (source != null) {
                    val targets = listeners.entries.filter { producer.isInstance(it.key) }
                    if (targets.isNotEmpty()) {
                        val snapshot = copy.invoke(source)
                        targets.forEach { entry ->
                            val target = entry.key
                            val handler = entry.value as Handler
                            handler.post {
                                if (listeners[target] === handler) runCatching { update.invoke(target, snapshot) }
                                    .onFailure { if (failed.compareAndSet(false, true)) runtime.info("Native volume state delivery failed: $it") }
                            }
                        }
                    }
                }
            }.onFailure { if (failed.compareAndSet(false, true)) runtime.info("Native volume state delivery failed: $it") }
            result
        }
    }

    private inner class Session(private val native: Any, private val stockFactory: () -> Any) {
        private val dialogType = runtime.type("com.android.systemui.plugins.VolumeDialog")
        private val main = Handler(Looper.getMainLooper())
        private var stock: Any? = null
        private var failed = false
        private var destroyed = false
        private var initialized = false
        private var initArgs: Array<out Any?>? = null
        private var ownedScope: Any? = null

        fun proxy(): Any {
            installIsolatedScope()
            return Proxy.newProxyInstance(loader, arrayOf(dialogType)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "Amalor Native Volume"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "init" -> {
                        if (!destroyed && !initialized) {
                            initialized = true
                            initArgs = args ?: emptyArray()
                            try { method.invoke(stock ?: native, *initArgs!!) }
                            catch (error: Exception) { fallback(error) }
                        }
                        null
                    }
                    "destroy" -> {
                        destroyed = true
                        runCatching { dialogType.getMethod("destroy").invoke(stock ?: native) }
                        cancelScope()
                        null
                    }
                    else -> method.invoke(stock ?: native, *(args ?: emptyArray()))
                }
            }
        }

        private fun fallback(error: Throwable) {
            if (Looper.myLooper() != Looper.getMainLooper()) { main.post { fallback(error) }; return }
            if (failed || destroyed) return
            failed = true
            runtime.info("Native volume failed; restoring stock: $error")
            runCatching { dialogType.getMethod("destroy").invoke(native) }
            cancelScope()
            runCatching {
                stock = stockFactory()
                initArgs?.let { dialogType.methods.single { it.name == "init" }.invoke(stock, *it) }
            }.onFailure { runtime.info("Stock volume restoration failed: $it") }
        }

        private fun installIsolatedScope() {
            val scopeType = runtime.type("kotlinx.coroutines.CoroutineScope")
            val contextType = runtime.type("kotlin.coroutines.CoroutineContext")
            val functionType = runtime.type("kotlin.jvm.functions.Function2")
            val original = AospVolumeGraph.field(native, "applicationCoroutineScope")
            val originalContext = scopeType.getMethod("getCoroutineContext").invoke(original)
            val callback = Proxy.newProxyInstance(loader, arrayOf(functionType)) { proxy, method, args ->
                when (method.name) {
                    "invoke" -> {
                        (args?.getOrNull(1) as? Throwable)?.let { main.post { fallback(it) } }
                        runtime.type("kotlin.Unit").getField("INSTANCE").get(null)
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> "Amalor Volume Exception Handler"
                }
            }
            val handler = runtime.type("kotlinx.coroutines.CoroutineExceptionHandlerKt")
                .getMethod("CoroutineExceptionHandler", functionType).invoke(null, callback)
            val job = runtime.type("kotlinx.coroutines.SupervisorKt")
                .getMethod("SupervisorJob", runtime.type("kotlinx.coroutines.Job")).invoke(null, null)
            val plus = contextType.getMethod("plus", contextType)
            val context = plus.invoke(plus.invoke(originalContext, job), handler)
            ownedScope = runtime.type("kotlinx.coroutines.CoroutineScopeKt").getMethod("CoroutineScope", contextType).invoke(null, context)
            native.javaClass.getDeclaredField("applicationCoroutineScope").apply { isAccessible = true }.set(native, ownedScope)
        }

        private fun cancelScope() {
            ownedScope?.let {
                runCatching {
                    runtime.type("kotlinx.coroutines.CoroutineScopeKt")
                        .getMethod("cancel", runtime.type("kotlinx.coroutines.CoroutineScope"), java.util.concurrent.CancellationException::class.java)
                        .invoke(null, it, null)
                }
            }
            ownedScope = null
        }
    }
}
