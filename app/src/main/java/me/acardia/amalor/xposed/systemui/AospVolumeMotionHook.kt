package me.acardia.amalor.xposed.systemui

import android.view.View
import androidx.compose.animation.core.SpringSpec
import androidx.compose.material3.MotionScheme
import me.acardia.amalor.NativeFeatureOptions
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Retain the native spring/coroutine lifecycle; change its profile and rendered geometry. */
internal class AospVolumeMotionHook(private val r: NativeFeatures) {
    private class Surface(background: View) {
        val background = WeakReference(background)
        var entering = true
        var alpha = 0f
    }
    private val surfaces = WeakHashMap<View, Surface>()

    fun bind(root: View, background: View) {
        synchronized(surfaces) { surfaces[root] = Surface(background) }
    }

    fun install() {
        val owner = "com.android.systemui.volume.dialog.ui.binder.VolumeDialogViewBinder"
        val transition = r.type("$owner\$animateVisibility\$1")
        val transitionView = r.field(transition, "\$view")
        val spring = r.type("androidx.dynamicanimation.animation.SpringAnimation")
        val force = r.type("androidx.dynamicanimation.animation.SpringForce")
        val getSpring = spring.getDeclaredMethod("getSpring")
        val stiffness = force.getDeclaredMethod("setStiffness", Float::class.javaPrimitiveType)
        val damping = force.getDeclaredMethod("setDampingRatio", Float::class.javaPrimitiveType)
        val spec = MotionScheme.expressive().defaultSpatialSpec<Float>() as SpringSpec<Float>
        val start = r.type("com.android.systemui.volume.dialog.ui.utils.SuspendAnimatorsKt")
            .getDeclaredMethod("suspendAnimate\$default", spring, Float::class.javaPrimitiveType,
                r.type("kotlin.jvm.functions.Function1"), r.type("kotlin.coroutines.jvm.internal.SuspendLambda"),
                Int::class.javaPrimitiveType)
        r.hook("volume:expressive-spring", start) { chain ->
            val continuation = chain.getArg(3)
            if (r.enabled(NativeFeatureOptions.VOLUME_PANEL) && transition.isInstance(continuation)) {
                val root = transitionView.get(continuation) as View
                synchronized(surfaces) { surfaces[root] }?.let { surface ->
                    val nativeSpring = getSpring.invoke(chain.getArg(0))
                    stiffness.invoke(nativeSpring, spec.stiffness)
                    damping.invoke(nativeSpring, spec.dampingRatio)
                    surface.entering = (chain.getArg(1) as Float) > 0f
                    surface.alpha = root.alpha.coerceIn(0f, 1f)
                }
            }
            chain.proceed()
        }
        r.module.deoptimize(transition.getDeclaredMethod("invokeSuspend", Any::class.java))

        val callback = r.type("$owner\$animateVisibility\$animation\$1")
        val view = r.field(callback, "\$view")
        val animation = r.type("androidx.dynamicanimation.animation.DynamicAnimation")
        val update = callback.getDeclaredMethod("onAnimationUpdate", animation,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        r.hook("volume:expressive-frame", update) { chain ->
            if (!r.enabled(NativeFeatureOptions.VOLUME_PANEL)) return@hook chain.proceed()
            val root = view.get(chain.thisObject) as View
            val surface = synchronized(surfaces) { surfaces[root] } ?: return@hook chain.proceed()
            val background = surface.background.get() ?: return@hook chain.proceed()
            val progress = chain.getArg(1) as Float
            if (!progress.isFinite()) return@hook chain.proceed()
            val travel = AospVolumeMotionRules.travel(root.width, background.left, background.layoutParams.width)
            if (background.width > 0 && background.height > 0) {
                root.pivotX = background.right.toFloat()
                root.pivotY = background.top + background.height / 2f
            }
            surface.alpha = AospVolumeMotionRules.alpha(progress, surface.alpha, surface.entering)
            root.alpha = surface.alpha
            root.translationX = AospVolumeMotionRules.translation(progress, travel)
            val scale = AospVolumeMotionRules.scale(progress)
            root.scaleX = scale
            root.scaleY = scale
            null
        }
        r.module.deoptimize(update)
        r.module.deoptimize(animation.getDeclaredMethod("setPropertyValue", Float::class.javaPrimitiveType))
        r.info("Native volume expressive motion: stiffness=${spec.stiffness}, damping=${spec.dampingRatio}")
    }
}

internal object AospVolumeMotionRules {
    const val PANEL_SCALE = 0.9f

    fun travel(rootWidth: Int, backgroundLeft: Int, backgroundWidth: Int): Float {
        val width = backgroundWidth.coerceAtLeast(0)
        val edgeGap = if (rootWidth > 0) (rootWidth - backgroundLeft - width).coerceAtLeast(0) else 0
        return width * PANEL_SCALE + edgeGap
    }

    fun translation(progress: Float, travel: Float): Float = (1f - progress.coerceIn(-0.1f, 1.15f)) * travel

    // One transform preserves native ratios in idle, dragging, and open-ringer states alike.
    fun scale(progress: Float): Float = PANEL_SCALE * (0.94f + 0.06f * progress.coerceIn(0f, 1.15f))

    fun alpha(progress: Float, previous: Float, entering: Boolean): Float {
        val target = progress.coerceIn(0f, 1f)
        // Spatial overshoot is welcome; opacity must not flash back after reaching an endpoint.
        return if (entering) maxOf(previous, target) else minOf(previous, target)
    }
}
