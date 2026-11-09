package me.acardia.amalor.xposed.systemui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import me.acardia.amalor.NativeFeatureOptions
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/** ONextBox visual routing port (GPL-3.0). OEM logic still decides whether back commits. */
internal class AospBackPanelHook(private val runtime: NativeFeatures) {
    private val detector = runtime.type("com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector")
    private val managerType = runtime.type("com.oplus.systemui.navigationbar.gesture.sidegesture.view.SideGestureViewManager")
    private val point = runtime.type("com.android.systemui.navigationbar.GesturePointContainerEx")
    private val panel = runtime.type("com.android.systemui.navigationbar.gestural.BackPanelController")
    private val handlerType = runtime.type("com.android.systemui.navigationbar.gestural.DisplayBackGestureHandlerImpl")
    private val plugin = runtime.field(handlerType, "edgeBackPlugin")
    private val allowed = runtime.field(detector, "mAllowGesture")
    private val manager = runtime.field(detector, "mSideGestureViewManager")
    private val detectorContext = runtime.field(detector, "mContext")
    private val callback = runtime.field(panel, "backCallback")
    private val panelHandler = runtime.field(panel, "mainHandler")
    private val state = runtime.field(panel, "currentState")
    private val gone = checkNotNull(state.type.enumConstants).single { (it as Enum<*>).name == "GONE" }
    private val motion = panel.getDeclaredMethod("onMotionEvent", MotionEvent::class.java).apply { isAccessible = true }
    private val updateState = panel.getDeclaredMethod("updateArrowState", state.type, Boolean::class.javaPrimitiveType!!).apply { isAccessible = true }
    private val leftPanel = panel.getDeclaredMethod("setIsLeftPanel", Boolean::class.javaPrimitiveType!!).apply { isAccessible = true }
    private val panelView = panel.declaredMethods.single { it.name.startsWith("getBackPanelView\$") && it.parameterCount == 0 }.apply { isAccessible = true }
    private val getContext = panel.getMethod("getContext")
    private val eventDisplay = MotionEvent::class.java.getMethod("getDisplayId")
    private val contextDisplay = android.content.Context::class.java.getMethod("getDisplayId")
    private val pointState = point.getDeclaredMethod("getState").apply { isAccessible = true }
    private val downVisual = managerType.getDeclaredMethod("onDownEvent", Int::class.javaPrimitiveType!!, MotionEvent::class.java, Int::class.javaPrimitiveType!!).apply { isAccessible = true }
    private val updateVisual = managerType.getDeclaredMethod("onUpdateGestureView", point).apply { isAccessible = true }
    private val gestures = WeakHashMap<Any, Gesture>()
    private val panels = WeakHashMap<Any, Binding>()
    private val lock = Any()
    private val frame = ThreadLocal<Frame?>()
    private var broken = false

    private class Gesture(val panel: Any, val manager: Any, val down: MotionEvent) {
        var native = false
        var accepted = false
        var fedOnStart = false
        var side = 0
        var rotation = 0
        var point: Any? = null
    }
    private class Frame(val gesture: Gesture?, val event: MotionEvent)
    private class Binding(val stock: Any, val visual: Any, var lastEvent: Long, var destroyed: Boolean = false)

    fun install() {
        runtime.hook("back:destroy", panel.getDeclaredMethod("onDestroy")) { chain ->
            try { chain.proceed() } finally {
                val owner = chain.thisObject
                synchronized(lock) {
                    panels[owner]?.let { it.destroyed = true; it.lastEvent = SystemClock.uptimeMillis() }
                    val active = gestures.entries.filter { it.value.panel === owner }.map { it.key }
                    active.forEach { gestures.remove(it)?.let { g -> g.native = false; g.down.recycle() } }
                }
                runCatching { updateState.invoke(owner, gone, true) }
            }
        }
        val visuals = listOf(downVisual, updateVisual,
            managerType.getDeclaredMethod("onGestureFinished", point).apply { isAccessible = true },
            managerType.getDeclaredMethod("onUpEvent", Int::class.javaPrimitiveType!!).apply { isAccessible = true })
        visuals.forEachIndexed { index, method ->
            runtime.hook("back:visual:$index", method) { chain ->
                if (interceptVisual(index, chain.thisObject, chain.args)) null else chain.proceed()
            }
            runtime.module.deoptimize(method)
        }
        runtime.hook("back:motion", detector.getDeclaredMethod("onMotionEventImpl", MotionEvent::class.java, Map::class.java)) { chain ->
            val owner = chain.thisObject
            val event = chain.getArg(0) as MotionEvent
            val handlers = chain.getArg(1) as Map<*, *>
            if (owner == null) chain.proceed() else dispatch(owner, event, handlers) { chain.proceed() }
        }
        runtime.module.deoptimize(motion)
    }

    private fun own(target: Any) = synchronized(lock) {
        panels[target]?.let { check(callback.get(target) === it.visual); return@synchronized }
        val original = checkNotNull(callback.get(target))
        check(!Proxy.isProxyClass(original.javaClass))
        val visual = Proxy.newProxyInstance(callback.type.classLoader, arrayOf(callback.type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Amalor Native Back Visual"
                else -> null // No native triggerBack: the OEM gesture retains commit ownership.
            }
        }
        callback.set(target, visual)
        panels[target] = Binding(original, visual, SystemClock.uptimeMillis())
    }

    private fun dispatch(owner: Any, event: MotionEvent, handlers: Map<*, *>, action: () -> Any?): Any? {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            removeGesture(owner)
            val enabled = runtime.enabled(NativeFeatureOptions.BACK_PANEL)
            synchronized(lock) {
                val now = SystemClock.uptimeMillis()
                panels.entries.filter { (target, binding) ->
                    (!enabled || binding.destroyed || broken) && state.get(target) === gone && now - binding.lastEvent >= 700L
                }.map { it.key }.forEach { target ->
                    panels.remove(target)?.let { if (callback.get(target) === it.visual) callback.set(target, it.stock) }
                }
            }
            if (enabled && !broken && event.pointerCount == 1 && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                runCatching {
                    val displayId = eventDisplay.invoke(event)
                    val target = handlers[displayId]?.let { plugin.get(it) } ?: return@runCatching
                    if (!panel.isInstance(target) || (panelHandler.get(target) as Handler).looper != Looper.myLooper()) return@runCatching
                    if (!(panelView.invoke(target) as View).isAttachedToWindow) return@runCatching
                    if (contextDisplay.invoke(detectorContext.get(owner)) != displayId || contextDisplay.invoke(getContext.invoke(target)) != displayId) return@runCatching
                    val viewManager = manager.get(owner) ?: return@runCatching
                    synchronized(lock) { gestures[owner] = Gesture(target, viewManager, MotionEvent.obtain(event)) }
                }.onFailure(::unavailable)
            }
        }
        val gesture = synchronized(lock) { gestures[owner] }
        val previous = frame.get()
        frame.set(Frame(gesture, event))
        var success = false
        return try { action().also { success = true } } finally {
            try {
                if (gesture != null) complete(owner, gesture, event, success)
            } finally { if (previous == null) frame.remove() else frame.set(previous) }
        }
    }

    private fun interceptVisual(index: Int, owner: Any?, args: List<Any?>): Boolean {
        val current = frame.get() ?: return false
        val gesture = current.gesture ?: return false
        if (owner !== gesture.manager) return false
        if (index == 0 && !gesture.native && !broken) {
            val event = args.getOrNull(1) as? MotionEvent ?: return false
            if (event !== current.event || event.pointerCount != 1) return false
            gesture.side = args[0] as Int
            gesture.rotation = args[2] as Int
            runCatching {
                own(gesture.panel)
                leftPanel.invoke(gesture.panel, gesture.side == 0)
                send(gesture.panel, gesture.down)
                send(gesture.panel, event)
                gesture.native = true
                gesture.fedOnStart = true
            }.onFailure { abort(gesture); unavailable(it) }
        }
        if (!gesture.native) return false
        if (index == 1) gesture.point = args.getOrNull(0)
        if (index == 2) gesture.accepted = runCatching { pointState.invoke(args[0]) == 2 }.getOrDefault(false)
        return true
    }

    private fun complete(owner: Any, gesture: Gesture, event: MotionEvent, success: Boolean) {
        val terminal = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        val permitted = runCatching { allowed.getBoolean(owner) }.getOrDefault(false)
        try {
            if (gesture.native) runCatching {
                when {
                    !success || (!terminal && (!permitted || event.pointerCount != 1)) -> {
                        sendAction(gesture.panel, event, MotionEvent.ACTION_CANCEL); gesture.native = false
                    }
                    terminal -> {
                        sendAction(gesture.panel, event, if (event.actionMasked == MotionEvent.ACTION_UP && gesture.accepted) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL)
                        gesture.native = false
                    }
                    gesture.fedOnStart -> gesture.fedOnStart = false
                    else -> send(gesture.panel, event)
                }
            }.onFailure {
                abort(gesture); unavailable(it)
                if (success && !terminal && permitted) runCatching {
                    downVisual.invoke(gesture.manager, gesture.side, event, gesture.rotation)
                    gesture.point?.let { p -> updateVisual.invoke(gesture.manager, p) }
                }
            }
        } finally { if (terminal || !permitted || !success || event.pointerCount != 1) removeGesture(owner) }
    }

    private fun send(target: Any, event: MotionEvent) {
        synchronized(lock) {
            val binding = checkNotNull(panels[target])
            check(callback.get(target) === binding.visual)
            binding.lastEvent = SystemClock.uptimeMillis()
        }
        motion.invoke(target, event)
    }

    private fun sendAction(target: Any, event: MotionEvent, action: Int) {
        val copy = MotionEvent.obtain(event)
        try { copy.action = action; send(target, copy) } finally { copy.recycle() }
    }

    private fun abort(gesture: Gesture) {
        gesture.native = false
        runCatching { if (synchronized(lock) { panels[gesture.panel]?.visual === callback.get(gesture.panel) }) updateState.invoke(gesture.panel, gone, true) }
    }

    private fun unavailable(error: Throwable) {
        if (!broken) runtime.info("Native back visual failed; OEM handling retained: $error")
        broken = true
    }

    private fun removeGesture(owner: Any) {
        val removed = synchronized(lock) { gestures.remove(owner) } ?: return
        try { if (removed.native) runCatching { sendAction(removed.panel, removed.down, MotionEvent.ACTION_CANCEL) }.onFailure { abort(removed); unavailable(it) } }
        finally { removed.down.recycle() }
    }
}
