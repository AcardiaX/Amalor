package me.acardia.amalor.xposed.systemui

import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Choreographer
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import me.acardia.amalor.NativeFeatureOptions
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Native drawable types and controllers, standard round-rect paths, Material spatial springs. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal class QsShapeMotion(private val r: NativeFeatures) {
    private val roundRect = r.type("com.oplusos.systemui.common.outline.RoundRectOutlineProvider")
    private val corner = r.type("com.oplusos.systemui.common.outline.CornerOutlineProvider")
    private val wrapper = r.type("com.oplus.systemui.qs.base.res.drawable.TileDrawableWrapper")
    private val constructor = roundRect.getDeclaredConstructor(Float::class.javaPrimitiveType, Float::class.javaObjectType)
    private val update = roundRect.getDeclaredMethod("update", Float::class.javaPrimitiveType, Float::class.javaObjectType)
    private val nativeRadii = r.field(roundRect, "cornerRadii")
    private val nativeRadius = r.field(roundRect, "cornerRadius")
    private val nativeWeight = r.field(roundRect, "cornerWeight")
    private val getProvider = wrapper.getDeclaredMethod("getPathProvider")
    private val setProvider = wrapper.getDeclaredMethod("setPathProvider", corner)
    private val invalidatePath = wrapper.getDeclaredMethod("invalidatePath")
    private val blockProvider = r.field(r.type("com.oplus.systemui.qs.base.res.model.TileDrawableDelegate"), "blockPathProvider")
    private val byView = WeakHashMap<View, Surface>()
    private val bindings = WeakHashMap<Drawable, Binding>()
    private val metadataSources = WeakHashMap<Drawable, WeakReference<Surface>>()
    private val transitions = WeakHashMap<View, Transition>()
    private var reported = false

    private class Binding(val surface: Surface, var original: Any?)
    private class Transition(provider: Any) {
        val provider = WeakReference(provider)
        val path = Path()
        val bounds = RectF()
    }

    private inner class Surface(view: View, var kind: QsShapeRules.Kind, active: Boolean) {
        val view = WeakReference(view)
        val provider = constructor.newInstance(0f, null) as ViewOutlineProvider
        val drawables = WeakHashMap<Drawable, Unit>()
        val outlines = WeakHashMap<View, WeakReference<ViewOutlineProvider>>()
        val bounds = RectF()
        val spring = QsShapeSpring(if (active) 1f else 0f)
        var radius = -1f
        var posted = false
        val frame = Choreographer.FrameCallback { time ->
            posted = false
            runCatching {
                val target = this.view.get()
                if (!enabled() || target == null || !target.isAttachedToWindow || !target.isShown ||
                    target.windowVisibility != View.VISIBLE || !ValueAnimator.areAnimatorsEnabled()) {
                    snap()
                } else {
                    spring.sample(time)
                    sync()
                    if (spring.isRunning) schedule()
                }
            }.onFailure {
                spring.snapTo(spring.target)
                if (!reported) { reported = true; r.error("QS shape motion fallback", it.cause ?: it) }
            }
        }

        fun select(active: Boolean, available: Boolean) {
            val next = if (active) 1f else 0f
            if (spring.target == next) {
                if (!available && spring.isRunning) snap()
                return
            }
            val target = view.get()
            if (!available || kind == QsShapeRules.Kind.Media ||
                target == null || !target.isAttachedToWindow || !target.isShown || target.windowVisibility != View.VISIBLE ||
                !ValueAnimator.areAnimatorsEnabled()) {
                spring.snapTo(next)
                snap()
                return
            }
            spring.retarget(next, System.nanoTime(), ValueAnimator.getDurationScale())
            schedule()
        }

        fun schedule() {
            if (!posted) {
                posted = true
                Choreographer.getInstance().postFrameCallback(frame)
            }
        }

        fun snap() {
            if (posted) Choreographer.getInstance().removeFrameCallback(frame)
            posted = false
            spring.snapTo(spring.target)
            sync()
        }

        fun link(target: View) {
            if (!outlines.containsKey(target)) outlines[target] = WeakReference(target.outlineProvider)
            synchronized(byView) { byView[target] = this }
            if (target.outlineProvider !== provider) target.outlineProvider = provider
        }

        fun sync() {
            val target = view.get() ?: return
            val next = QsShapeRules.radius(kind, target.width.toFloat(), target.height.toFloat(),
                target.resources.displayMetrics.density, spring.value)
            if (radius == next && nativeRadius.getFloat(provider) == next && nativeWeight.get(provider) == null) return
            radius = next
            // Null is important: a non-null weight, even zero, selects the OEM smooth path.
            update.invoke(provider, next, null)
            drawables.keys.toList().forEach { drawable ->
                invalidatePath.invoke(drawable)
                drawable.invalidateSelf()
            }
            outlines.keys.toList().forEach { it.invalidateOutline(); it.invalidate() }
        }
    }

    private fun enabled() = r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE)

    private fun surfaceOf(drawable: Drawable): Surface? {
        synchronized(bindings) { bindings[drawable]?.surface }?.let { return it }
        synchronized(metadataSources) { metadataSources[drawable]?.get() }?.let { cached ->
            if (cached.view.get()?.let { view -> synchronized(byView) { byView[view] === cached } } == true) return cached
        }
        var callback = drawable.callback
        repeat(16) {
            when (val owner = callback) {
                is View -> return synchronized(byView) { byView[owner] }?.also {
                    // Layer envelopes lose their callback temporarily when the launch controller
                    // moves the background to its ghost. Retain the weak source association.
                    synchronized(metadataSources) { metadataSources[drawable] = WeakReference(it) }
                }
                is Drawable -> callback = owner.callback
                else -> return null
            }
        }
        return null
    }

    fun stateChanged(owner: View, active: Boolean, available: Boolean) {
        if (!enabled()) return
        val surfaces = synchronized(byView) { byView.values.toSet() }
        for (surface in surfaces) {
            var view = surface.view.get()
            while (view != null) {
                if (view === owner) { surface.select(active, available); break }
                view = view.parent as? View
            }
        }
    }

    fun install() {
        r.hook("qs-appearance:standard-path-provider", setProvider) { chain ->
            val binding = synchronized(bindings) { bindings[chain.thisObject as Drawable] }
            if (!enabled() || binding == null) return@hook chain.proceed()
            val requested = chain.getArg(0)
            if (requested !== binding.surface.provider) binding.original = requested
            chain.proceed(arrayOf(binding.surface.provider))
        }
        val setOutline = View::class.java.getDeclaredMethod("setOutlineProvider", ViewOutlineProvider::class.java)
        r.hook("qs-appearance:standard-view-outline", setOutline) { chain ->
            val surface = synchronized(byView) { byView[chain.thisObject as View] }
            if (enabled() && surface != null) chain.proceed(arrayOf(surface.provider)) else chain.proceed()
        }
        val weight = wrapper.getDeclaredMethod("getCornerWeight")
        r.hook("qs-appearance:standard-corner-metadata", weight) { chain ->
            if (enabled() && surfaceOf(chain.thisObject as Drawable) != null) null else chain.proceed()
        }
        for (type in listOf(wrapper, r.type("com.oplus.systemui.qs.base.res.drawable.TileLayerDrawable"))) {
            val delegate = r.field(type, "delegate")
            val radius = type.getDeclaredMethod("getCornerRadius")
            r.hook("qs-appearance:shape-snapshot-radius:${type.name}", radius) { chain ->
                val drawable = chain.thisObject as Drawable
                val surface = if (enabled()) surfaceOf(drawable) else null
                // Native transitions sample the envelope as well as its children. While idle,
                // use the visible surface's radius, not a stale delegate or expanded bounds.
                if (surface != null && blockProvider.get(delegate.get(drawable)) == null &&
                    (surface.view.get()?.width ?: 0) > 0 && (surface.view.get()?.height ?: 0) > 0) {
                    surface.sync()
                    surface.radius
                } else chain.proceed()
            }
            val block = type.getDeclaredMethod("setBlockPathProvider", corner)
            r.hook("qs-appearance:shape-block-release:${type.name}", block) { chain ->
                val drawable = chain.thisObject as Drawable
                val old = blockProvider.get(delegate.get(drawable))
                chain.proceed().also {
                    if (enabled() && chain.getArg(0) == null) surfaceOf(drawable)?.let { surface ->
                        surface.view.get()?.let { endTransition(it, old) }
                        for (child in surface.drawables.keys.toList()) {
                            if (getProvider.invoke(child) !== surface.provider) setProvider.invoke(child, surface.provider)
                        }
                        surface.sync()
                        surface.outlines.keys.toList().forEach { it.invalidateOutline(); it.invalidate() }
                    }
                }
            }
            r.module.deoptimize(radius)
            r.module.deoptimize(block)
        }
        val outline = roundRect.getDeclaredMethod("getOutline", View::class.java, Outline::class.java)
        r.hook("qs-appearance:standard-transition-outline", outline) { chain ->
            val view = chain.getArg(0) as View
            val transition = synchronized(transitions) { transitions[view] }
            val provider = transition?.provider?.get()
            if (!enabled() || transition == null || provider == null || chain.thisObject !== provider ||
                !roundRect.isInstance(provider)) return@hook chain.proceed()
            transition.bounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
            transition.path.reset()
            transition.path.addRoundRect(transition.bounds, nativeRadii.get(provider) as FloatArray, Path.Direction.CW)
            (chain.getArg(1) as Outline).setPath(transition.path)
            null
        }
        listOf(setProvider, setOutline, weight, outline).forEach { r.module.deoptimize(it) }
    }

    fun watchTransition(view: View, provider: Any?) {
        if (provider != null && roundRect.isInstance(provider)) synchronized(transitions) {
            if (transitions[view]?.provider?.get() !== provider) transitions[view] = Transition(provider)
        }
    }

    fun endTransition(view: View, expectedProvider: Any? = null) {
        val removed = synchronized(transitions) {
            val current = transitions[view]
            if (current != null && (expectedProvider == null || current.provider.get() === expectedProvider)) {
                transitions.remove(view)
                true
            } else false
        }
        if (removed) view.invalidateOutline()
    }

    fun standardPath(drawable: Drawable, path: Path, provider: Any?): Boolean {
        if (!roundRect.isInstance(provider)) return false
        val bounds = drawable.bounds
        path.reset()
        path.addRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            nativeRadii.get(provider) as FloatArray, Path.Direction.CW)
        return true
    }

    fun path(drawable: Drawable, view: View, path: Path, kind: QsShapeRules.Kind, active: Boolean,
        available: Boolean, animatedProvider: Any? = null, clipView: View? = null) {
        if (animatedProvider != null && roundRect.isInstance(animatedProvider)) {
            watchTransition(view, animatedProvider)
            val transition = synchronized(transitions) { requireNotNull(transitions[view]) }
            transition.bounds.set(drawable.bounds)
            path.reset()
            path.addRoundRect(transition.bounds, nativeRadii.get(animatedProvider) as FloatArray, Path.Direction.CW)
            return
        }
        endTransition(view)
        val surface = synchronized(byView) { byView[view] } ?: Surface(view, kind, active).also { it.link(view) }
        if (surface.kind != kind) { surface.kind = kind; surface.snap() }
        synchronized(bindings) {
            if (!bindings.containsKey(drawable)) bindings[drawable] = Binding(surface, getProvider.invoke(drawable))
        }
        surface.drawables[drawable] = Unit
        if (getProvider.invoke(drawable) !== surface.provider) setProvider.invoke(drawable, surface.provider)
        if (clipView != null) surface.link(clipView)
        surface.select(active, available)
        surface.sync()
        surface.bounds.set(drawable.bounds)
        val radius = QsShapeRules.radius(kind, surface.bounds.width(), surface.bounds.height(),
            view.resources.displayMetrics.density, surface.spring.value)
        path.reset()
        path.addRoundRect(surface.bounds, radius, radius, Path.Direction.CW)
    }

    fun restore(drawable: Drawable) {
        val binding = synchronized(bindings) { bindings.remove(drawable) } ?: return
        val surface = binding.surface
        surface.view.get()?.let { endTransition(it) }
        surface.snap()
        surface.drawables.remove(drawable)
        binding.original?.let { setProvider.invoke(drawable, it) }
        if (surface.drawables.isEmpty()) {
            surface.outlines.forEach { (view, original) ->
                synchronized(byView) { if (byView[view] === surface) byView.remove(view) }
                if (view.outlineProvider === surface.provider) view.outlineProvider = original.get() ?: binding.original as? ViewOutlineProvider
                view.invalidateOutline()
            }
        }
    }
}

/** The production spring clock is independent of View/Looper, so reversal and time scaling can be checked on the JVM. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal class QsShapeSpring(initial: Float) {
    private val spec = MotionScheme.expressive().fastSpatialSpec<Float>()
    var value = initial
        private set
    var velocity = 0f
        private set
    var target = initial
        private set
    private var animation: TargetBasedAnimation<Float, AnimationVector1D>? = null
    private var started = 0L
    private var durationScale = 1f
    val isRunning: Boolean get() = animation != null

    fun retarget(next: Float, now: Long, scale: Float) {
        if (!scale.isFinite() || scale <= 0f) { snapTo(next); return }
        if (next == target) return
        sample(now)
        target = next
        started = now
        durationScale = scale
        animation = TargetBasedAnimation(spec, Float.VectorConverter, value, next, initialVelocityVector = AnimationVector1D(velocity))
    }

    fun sample(now: Long) {
        val motion = animation ?: return
        val time = ((now - started).coerceAtLeast(0L) / durationScale.toDouble()).toLong()
        value = motion.getValueFromNanos(time)
        velocity = motion.getVelocityVectorFromNanos(time).value
        if (motion.isFinishedFromNanos(time)) snapTo(target)
    }

    fun snapTo(next: Float) {
        value = next
        target = next
        velocity = 0f
        animation = null
    }
}
