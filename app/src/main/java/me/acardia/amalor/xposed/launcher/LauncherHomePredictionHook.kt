package me.acardia.amalor.xposed.launcher

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import io.github.libxposed.api.XposedModule
import me.acardia.amalor.NativeFeatureOptions
import me.acardia.amalor.xposed.systemui.BackGestureRules
import me.acardia.amalor.xposed.systemui.NativeFeatures
import me.acardia.amalor.xposed.systemui.StandardSurfaceCorners
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** ONextBox launcher predictive preview port (GPL-3.0); native progress/cancel/icon handoff retained. */
class LauncherHomePredictionHook(module: XposedModule, loader: ClassLoader) {
    private val r = NativeFeatures(module, loader)
    private data class Frame(val owner: Any, val progress: Float, val edge: Int, val touchY: Float)
    private val gestures = WeakHashMap<Any, Boolean>()
    private val frame = ThreadLocal<Frame?>()
    private val corners = StandardSurfaceCorners(r)

    fun install() {
        if (!r.enabled(NativeFeatureOptions.BACK_PANEL)) return
        r.install(NativeFeatureOptions.BACK_PREDICTIVE_ANIMATION, true, ::installPreview)
    }

    private fun installPreview() {
        // The manager's field survives R8 even when the controller class itself is renamed.
        val type = LauncherCompatibilityRules.backController(r.type("com.android.launcher3.QuickstepTransitionManager"))
        val motion = r.type("android.window.BackMotionEvent")
        val event = r.type("android.window.BackEvent")
        val layout = LauncherCompatibilityRules.backLayout(type, Rect::class.java, RectF::class.java, PointF::class.java)
        val begin = LauncherCompatibilityRules.backBegin(type, motion)
        val animator = begin.declaringClass
        val callback = begin.parameterTypes[1]
        val callbackOwner = callback.declaredFields.single { it.type == type }.apply { isAccessible = true }
        val bounds = r.field(type, layout.bounds)
        val initialTouch = r.field(type, layout.touch)
        val current = r.field(type, layout.current)
        val crop = r.field(type, layout.crop)
        val session = r.field(type, layout.session)
        val sessionProgress = r.field(session.type, "d")
        val currentRadius = r.field(type, layout.currentRadius)
        val launcherReference = r.field(type, layout.launcher)
        val callbackUpdate = LauncherCompatibilityRules.backUpdate(callback, event)
        val update = callbackUpdate ?: LauncherCompatibilityRules.backDispatch(animator)
        val animatorCallback = if (callbackUpdate == null) animator.declaredFields.single { it.type == callback }.apply { isAccessible = true } else null
        val animatorMotion = if (callbackUpdate == null) animator.declaredFields.single { it.type == motion }.apply { isAccessible = true } else null
        val transform = type.declaredMethods.single { it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(
            RectF::class.java, Float::class.javaPrimitiveType, Rect::class.java,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)) }
        val finish = type.getDeclaredMethod(layout.finish)
        val edge = event.getDeclaredMethod("getSwipeEdge")
        val startEdge = motion.getDeclaredMethod("getSwipeEdge")
        val motionTouchY = motion.getDeclaredMethod("getTouchY")
        val progress = event.getDeclaredMethod("getProgress")
        val touchY = event.getDeclaredMethod("getTouchY")
        require(callbackOwner.type == type && bounds.type == Rect::class.java &&
            initialTouch.type == PointF::class.java && current.type == RectF::class.java && crop.type == Rect::class.java)
        require(listOf(sessionProgress, currentRadius).all { it.type == Float::class.javaPrimitiveType })
        require(WeakReference::class.java.isAssignableFrom(launcherReference.type))
        corners.install()
        var logged = false

        r.hook("back:home-finish", finish) { chain ->
            try { chain.proceed() } finally { synchronized(gestures) { gestures.remove(chain.thisObject) } }
        }
        r.hook("back:home-transform", transform) { chain ->
            val active = frame.get()
            if (active == null || active.owner !== chain.thisObject) return@hook chain.proceed()
            val owner = active.owner
            val screen = bounds.get(owner) as Rect
            val initial = initialTouch.get(owner) as PointF
            val context = (launcherReference.get(owner) as? WeakReference<*>)?.get() as? Context
                ?: return@hook chain.proceed()
            val resources = context.resources
            val id = resources.getIdentifier("swipe_back_window_scale_x_margin", "dimen", context.packageName)
            val margin = if (id != 0) resources.getDimension(id) else 8f * resources.displayMetrics.density
            val projected = BackGestureRules.homePreview(screen.width().toFloat(), screen.height().toFloat(),
                active.progress, active.edge, active.touchY - initial.y, margin) ?: return@hook chain.proceed()
            val rect = RectF(screen.left + projected.left, screen.top + projected.top,
                screen.left + projected.left + projected.width, screen.top + projected.top + projected.height)
            val radius = corners.windowRadius(context) ?: return@hook chain.proceed()
            // Cancellation and icon handoff keep using the native controller's geometry and crop.
            (current.get(owner) as RectF).set(rect)
            (chain.getArg(0) as RectF).set(rect)
            sessionProgress.setFloat(session.get(owner), projected.progress)
            currentRadius.setFloat(owner, radius)
            val args = chain.args.toTypedArray()
            args[1] = radius
            args[2] = crop.get(owner)
            args[3] = 1f
            args[4] = projected.progress
            corners.withPreviewRadius(radius) {
                chain.proceed(args).also {
                    if (!logged) { logged = true; r.info("AOSP launcher predictive frame applied") }
                }
            }
        }
        r.hook("back:home-update", update) { chain ->
            val listener = if (callbackUpdate != null) chain.thisObject else animatorCallback!!.get(chain.thisObject)
            val owner = listener?.let(callbackOwner::get) ?: return@hook chain.proceed()
            if (synchronized(gestures) { gestures[owner] } != true) return@hook chain.proceed()
            val value = if (callbackUpdate != null) {
                val input = chain.getArg(0) ?: return@hook chain.proceed()
                Frame(owner, progress.invoke(input) as Float, edge.invoke(input) as Int, touchY.invoke(input) as Float)
            } else {
                // 17.5.4 inlines the BackEvent callback into the animator's float dispatcher.
                // That dispatcher constructs BackEvent with animatedValue / 100, before OEM easing.
                val input = animatorMotion!!.get(chain.thisObject) ?: return@hook chain.proceed()
                Frame(owner, LauncherCompatibilityRules.backProgress(chain.getArg(0) as Float),
                    startEdge.invoke(input) as Int, motionTouchY.invoke(input) as Float)
            }
            val previous = frame.get()
            frame.set(value)
            try { chain.proceed() } finally { if (previous == null) frame.remove() else frame.set(previous) }
        }
        r.hook("back:home-begin", begin) { chain ->
            val listener = chain.getArg(1) ?: return@hook chain.proceed()
            val owner = callbackOwner.get(listener) ?: return@hook chain.proceed()
            synchronized(gestures) {
                gestures[owner] = r.enabled(NativeFeatureOptions.BACK_PANEL) && r.enabled(NativeFeatureOptions.BACK_PREDICTIVE_ANIMATION) &&
                    startEdge.invoke(chain.getArg(0)) in listOf(0, 1)
            }
            chain.proceed()
        }
        listOf(type, callback, animator).flatMap { it.declaredMethods.toList() }.forEach {
            runCatching { r.module.deoptimize(it) }
        }
        r.info("Native launcher predictive preview adapter installed: ${type.name}, " +
            if (callbackUpdate == null) "inlined progress dispatcher" else "BackEvent callback")
    }
}
