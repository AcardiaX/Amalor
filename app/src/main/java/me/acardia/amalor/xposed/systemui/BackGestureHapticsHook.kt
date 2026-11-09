package me.acardia.amalor.xposed.systemui

import android.content.Context
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import me.acardia.amalor.NativeFeatureOptions
import java.util.WeakHashMap

/** ONextBox BackGestureHooker haptic/session port (GPL-3.0); scheme follows Native style. */
internal class BackGestureHapticsHook(private val r: NativeFeatures) {
    private class Session(val haptics: Int)
    private data class Feedback(val view: View, val scheme: Int, val nativeAosp: Boolean)
    private val gestures = WeakHashMap<Any, Session>()
    private val frame = ThreadLocal<Session?>()
    private val feedback = ThreadLocal<Feedback?>()
    private val emitting = ThreadLocal<Boolean?>()
    private val helper = r.type("com.oplus.systemui.navigationbar.gesture.VibrationHelper")
    private val getHelper = helper.getDeclaredMethod("getInstance", Context::class.java).apply { isAccessible = true }
    private val nativeFeedback = helper.getDeclaredMethod("onPerformHapticFeedback").apply { isAccessible = true }

    fun install() {
        val detector = r.type("com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector")
        val input = detector.getDeclaredMethod("onMotionEventImpl", MotionEvent::class.java, Map::class.java)
        val side = r.type("com.oplus.systemui.navigationbar.gesture.sidegesture.view.SideGestureNavView")
        val update = side.getDeclaredMethod("onUpdateGestureView", r.type("com.android.systemui.navigationbar.GesturePointContainerEx"))
        val panel = r.type("com.android.systemui.navigationbar.gestural.BackPanelController")
        val panelView = panel.declaredMethods.single {
            it.name.startsWith("getBackPanelView\$") && it.parameterCount == 0 && View::class.java.isAssignableFrom(it.returnType)
        }.apply { isAccessible = true }
        val state = r.field(panel, "currentState")
        val updatePanel = panel.getDeclaredMethod("updateArrowState", state.type, Boolean::class.javaPrimitiveType)
        val perform = View::class.java.getDeclaredMethod("performHapticFeedback", Int::class.javaPrimitiveType)
        val methods = listOf(helper.getDeclaredMethod("doVibrateCustomized", Context::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType), helper.getDeclaredMethod("performKeyboardTouchFeedback"))

        r.hook("back:haptic-input", input) { chain ->
            val owner = chain.thisObject ?: return@hook chain.proceed()
            val event = chain.getArg(0) as MotionEvent
            if (event.actionMasked == MotionEvent.ACTION_DOWN) synchronized(gestures) {
                gestures.remove(owner)
                if (event.pointerCount == 1 && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER)
                    gestures[owner] = Session(if (r.enabled(NativeFeatureOptions.BACK_PANEL)) 0 else 1)
            }
            // Match the donor: an untracked/synthetic event must not force the selected scheme off.
            val session = synchronized(gestures) { gestures[owner] } ?: return@hook chain.proceed()
            val previous = frame.get()
            frame.set(session)
            try { chain.proceed() } finally {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL || event.pointerCount != 1)
                    synchronized(gestures) { gestures.remove(owner) }
                if (previous == null) frame.remove() else frame.set(previous)
            }
        }
        r.hook("back:haptic-scope", update) { chain ->
            withFeedback(chain.thisObject as View, false) { chain.proceed() }
        }
        r.hook("back:haptic-panel", updatePanel) { chain ->
            withFeedback(panelView.invoke(chain.thisObject) as View, true) { chain.proceed() }
        }
        r.hook("back:haptic-view", perform) { chain ->
            val current = feedback.get() ?: return@hook chain.proceed()
            val effect = chain.getArg(0) as Int
            if (emitting.get() == true || current.view !== chain.thisObject || !current.nativeAosp ||
                current.scheme != 1 || effect !in 23..24) return@hook chain.proceed()
            // ColorOS has a single activation pulse; the AOSP deactivation pulse is not added.
            if (effect == HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE || emit(current.view, false)) true else chain.proceed()
        }
        methods.forEach { method ->
            r.hook("back:haptic:$method", method) { chain ->
                val current = feedback.get()
                if (current == null || emitting.get() == true || current.nativeAosp ||
                    current.scheme != 0)
                    chain.proceed()
                else if (emit(current.view, true)) null else chain.proceed()
            }
        }
        (methods + listOf(input, update, updatePanel, perform)).forEach { runCatching { r.module.deoptimize(it) } }
        r.info("Native back haptic scheme follows ${NativeFeatureOptions.BACK_PANEL}")
    }

    private fun withFeedback(view: View, nativeAosp: Boolean, action: () -> Any?): Any? {
        val previous = feedback.get()
        val configured = frame.get()?.haptics ?: if (r.enabled(NativeFeatureOptions.BACK_PANEL)) 0 else 1
        feedback.set(Feedback(view, BackGestureRules.haptics(configured, nativeAosp), nativeAosp))
        return try { action() } finally { if (previous == null) feedback.remove() else feedback.set(previous) }
    }

    private fun emit(view: View, aosp: Boolean): Boolean {
        if (!view.isAttachedToWindow || !view.isHapticFeedbackEnabled ||
            Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return false
        val previous = emitting.get()
        emitting.set(true)
        return try {
            if (aosp) view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
            else { nativeFeedback.invoke(getHelper.invoke(null, view.context)); true }
        } catch (error: Exception) {
            r.error("Native back haptic feedback unavailable", error)
            false
        } finally { if (previous == null) emitting.remove() else emitting.set(previous) }
    }
}
