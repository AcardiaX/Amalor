package me.acardia.amalor.xposed.systemui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import me.acardia.amalor.NativeFeatureOptions
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs

/** Connected-width feedback on successful clicks, using temporary RenderNode matrices. */
internal class QsTileClickMotion(private val r: NativeFeatures, private val iconViews: () -> List<ImageView>) {
    private val resize = r.type("com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileView")
    private val tileTypes = listOf(resize,
        r.type("com.oplus.systemui.qs.base.tile.OplusQSTileBaseView"),
        r.type("com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView"))
    private val editMode = resize.getDeclaredMethod("isEditMode")
    private val slotType = r.type("com.oplus.systemui.plugins.qs.customize.view.deformation.DeformationFrameLayout")
    private val slotContent = slotType.getDeclaredMethod("getCurrInnerView")
    private val clicking = ThreadLocal<Boolean>()
    private val longClicking = ThreadLocal<Boolean>()
    private val hookedCallbacks = mutableSetOf<Method>()
    private val sessions = WeakHashMap<View, Session>()
    private val activeGlyphs = WeakHashMap<ImageView, TransformTarget>()
    private var reported = false

    private fun enabled() = r.enabled(NativeFeatureOptions.QS_TILE_NATIVE_APPEARANCE)
    private fun tile(view: View) = tileTypes.any { it.isInstance(view) }
    private fun visible(view: View) = view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
        view.alpha > 0f && view.width > 0 && view.height > 0

    private fun owner(view: View): View? {
        var node: View? = view
        repeat(16) {
            val current = node ?: return null
            if (tile(current)) return current
            node = current.parent as? View
        }
        return null
    }

    private fun tileInSlot(view: View): View? {
        // C17 slots expose their active content. Hidden QsLabelView subtrees and cached
        // resize variants must not make an otherwise valid one-tile slot "ambiguous".
        val content = if (slotType.isInstance(view)) slotContent.invoke(view) as? View else view
        return content?.takeIf { tile(it) && visible(it) }
    }

    private data class Group(val views: List<View>, val clicked: Int)

    private fun group(clicked: View): Group {
        val density = clicked.resources.displayMetrics.density
        var slot = clicked
        repeat(3) {
            val parent = slot.parent as? ViewGroup ?: return Group(listOf(clicked), 0)
            if (parent.childCount in 2..32) {
                val peers = (0 until parent.childCount).map { parent.getChildAt(it) }.filter { view ->
                    visible(view) && abs(view.top - slot.top) <= density && abs(view.height - slot.height) <= density &&
                        tileInSlot(view)?.let { it.isEnabled && (!resize.isInstance(it) || editMode.invoke(it) != true) } == true
                }.sortedBy { it.left }
                val index = peers.indexOfFirst { it === slot }
                if (index >= 0 && peers.size > 1) {
                    fun adjacent(left: Int, right: Int): Boolean {
                        val gap = peers[right].left - peers[left].right
                        return gap >= -density && gap <= 32f * density
                    }
                    var first = index
                    var last = index
                    while (first > 0 && adjacent(first - 1, first)) first--
                    while (last < peers.lastIndex && adjacent(last, last + 1)) last++
                    if (last > first) return Group(peers.subList(first, last + 1), index - first)
                }
            }
            slot = parent
        }
        return Group(listOf(clicked), 0)
    }

    private class TransformTarget(view: View) {
        val view = WeakReference(view)
        val parent = WeakReference(view.parent)
        val left = view.left
        val top = view.top
        val width = view.width
        val height = view.height
        val matrix = Matrix()
        var applied = false
        var scale = 1f
        var shift = 0f

        fun ownsMatrix(view: View): Boolean = if (applied) view.animationMatrix == matrix else view.animationMatrix == null
        fun valid(): Boolean = view.get()?.let {
            it.isAttachedToWindow && it.parent === parent.get() && it.left == left && it.top == top &&
                it.width == width && it.height == height && ownsMatrix(it) && it.animation?.hasEnded() != false
        } == true

        fun apply(scale: Float, shift: Float): Boolean {
            if (scale == 1f && shift == 0f) {
                val changed = applied
                restore()
                return changed
            }
            val target = view.get() ?: return false
            if (!applied || this.scale != scale || this.shift != shift) {
                matrix.setScale(scale, 1f)
                matrix.postTranslate(shift, 0f)
                target.animationMatrix = matrix
                this.scale = scale
                this.shift = shift
                applied = true
                return true
            }
            return false
        }

        fun restore() {
            view.get()?.let { if (applied && ownsMatrix(it)) it.animationMatrix = null }
            applied = false
        }
    }

    private inner class Session(views: List<View>) : View.OnAttachStateChangeListener {
        val slots = views.map(::TransformTarget)
        // The color adapter registers QSIconViewProxy.getIconView(), never its background ImageViews.
        val glyphs = iconViews().mapNotNull { image ->
            var parent = image.parent as? View
            var index = -1
            while (parent != null) {
                index = views.indexOfFirst { it === parent }
                if (index >= 0) break
                parent = parent.parent as? View
            }
            if (index >= 0) WeakReference(image) to slots[index] else null
        }
        val anchor = WeakReference(views.first())
        val pulse = QsClickPulse(views.map { it.width.toFloat() }.toFloatArray(), views.first().resources.displayMetrics.density)
        var pending = false
        var finished = false
        val frame = Choreographer.FrameCallback { now ->
            pending = false
            runCatching {
                if (!enabled() || !ValueAnimator.areAnimatorsEnabled() || !valid()) { finish(); return@runCatching }
                pulse.sample(now)
                if (!pulse.isRunning) { finish(); return@runCatching }
                var shift = 0f
                for (i in slots.indices) {
                    val slot = slots[i]
                    val delta = pulse.deltas[i]
                    val scale = (slot.width + delta) / slot.width
                    val translation = if (slots.size == 1) -delta / 2f else shift
                    if (slot.apply(scale, translation)) glyphs.forEach { (image, owner) ->
                        if (owner === slot) image.get()?.invalidate()
                    }
                    shift += delta
                }
                schedule()
            }.onFailure {
                finish()
                if (!reported) { reported = true; r.error("QS click feedback fallback", it) }
            }
        }

        init {
            views.forEach { it.addOnAttachStateChangeListener(this) }
            glyphs.forEach { (image, slot) -> image.get()?.let { activeGlyphs[it] = slot } }
        }

        fun matches(views: List<View>) = slots.size == views.size && slots.indices.all { slots[it].view.get() === views[it] }
        fun valid(): Boolean = !finished && slots.all { it.valid() && it.view.get()?.let(::visible) == true }

        fun click(index: Int) {
            pulse.click(index, System.nanoTime(), ValueAnimator.getDurationScale())
            if (pulse.isRunning) schedule() else finish()
        }

        fun schedule() {
            if (!pending && !finished) { pending = true; Choreographer.getInstance().postFrameCallback(frame) }
        }

        fun finish() {
            if (finished) return
            finished = true
            if (pending) Choreographer.getInstance().removeFrameCallback(frame)
            pending = false
            pulse.reset()
            slots.forEach { slot ->
                runCatching { slot.restore() }
                slot.view.get()?.removeOnAttachStateChangeListener(this)
            }
            glyphs.forEach { (image, slot) -> image.get()?.let {
                if (activeGlyphs[it] === slot) { activeGlyphs.remove(it); it.invalidate() }
            } }
            anchor.get()?.let { if (sessions[it] === this) sessions.remove(it) }
        }

        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = finish()
    }

    private fun start(clicked: View) {
        if (Looper.myLooper() != Looper.getMainLooper() || !visible(clicked) || !clicked.isEnabled ||
            !ValueAnimator.areAnimatorsEnabled() || (resize.isInstance(clicked) && editMode.invoke(clicked) == true)) return
        val group = group(clicked)
        val anchor = group.views.first()
        sessions[anchor]?.takeIf { it.matches(group.views) && it.valid() }?.let { it.click(group.clicked); return }
        sessions.values.toList().filter { session ->
            session.slots.any { slot -> group.views.any { it === slot.view.get() } }
        }.forEach { it.finish() }
        // Do not replace matrices owned by a native launch/edit/drag transition.
        if (group.views.any { it.animationMatrix != null || it.animation?.hasEnded() == false }) return
        Session(group.views).also { sessions[anchor] = it; it.click(group.clicked) }
    }

    fun install() {
        val drawIcon = ImageView::class.java.getDeclaredMethod("onDraw", Canvas::class.java)
        r.hook("qs-click:glyph-draw", drawIcon) { chain ->
            val image = chain.thisObject as ImageView
            val slot = activeGlyphs[image] ?: return@hook chain.proceed()
            val owner = slot.view.get() ?: return@hook chain.proceed()
            if (!slot.applied || slot.scale == 1f || !slot.ownsMatrix(owner)) return@hook chain.proceed()
            val canvas = chain.getArg(0) as Canvas
            val save = canvas.save()
            try {
                // Only image pixels are unscaled. View bounds, backgrounds, outlines and titles
                // keep the slot's deformation and never acquire an expanded child clip.
                canvas.scale(QsClickMotionRules.glyphScaleX(slot.scale), 1f, image.width / 2f, image.height / 2f)
                chain.proceed()
            } finally { canvas.restoreToCount(save) }
        }
        val performClick = View::class.java.getDeclaredMethod("performClick")
        hookClick(performClick)
        for (type in tileTypes) type.declaredMethods.filter { it.name == "performClick" }.forEach { r.module.deoptimize(it) }
        View::class.java.declaredMethods.filter { it.name == "performLongClick" }.forEach(::hookLongClick)

        // Observe the actual listener ABI at binding time; do not depend on generated Kotlin
        // lambda class names, which can change between optimized builds of the same ROM.
        // The base declaration is abstract on C17. Hook each concrete (or inherited) implementation.
        val declaration = resize.declaredMethods.single { it.name == "bindClickListener" && it.parameterCount == 4 }
        val bindings = (listOf("OneXOne", "TwoXOne", "TwoXTwo").map { r.type("${resize.name}$it") } +
            r.type("com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableThreeStageView"))
            .mapNotNull { QsClickMotionRules.clickBinding(it, declaration) }.distinct()
        check(bindings.isNotEmpty()) { "No concrete QS click listener binding" }
        for (bind in bindings) {
            r.hook("qs-click:bind-listeners:$bind", bind) { chain ->
                for (index in 0..1) chain.getArg(index)?.let { listener ->
                    hookClick(listener.javaClass.getMethod("onClick", View::class.java))
                }
                chain.getArg(2)?.let { listener ->
                    hookLongClick(listener.javaClass.getMethod("onLongClick", View::class.java))
                }
                chain.proceed()
            }
            r.module.deoptimize(bind)
        }
        r.module.deoptimize(resize.getDeclaredMethod("init", r.type("com.android.systemui.plugins.qs.QSTile")))
    }

    private fun hookClick(method: Method) {
        if (!hookedCallbacks.add(method)) return
        r.hook("qs-click:callback:$method", method) { chain ->
            if (!enabled() || clicking.get() == true || longClicking.get() == true) return@hook chain.proceed()
            val source = if (method.parameterCount == 0) chain.thisObject else chain.getArg(0)
            val clicked = owner(source as? View ?: return@hook chain.proceed()) ?: return@hook chain.proceed()
            clicking.set(true)
            try {
                chain.proceed().also {
                    if (QsClickMotionRules.shouldPulse(method.returnType == Void.TYPE || it == true, longClicking.get() == true)) start(clicked)
                }
            } finally { clicking.remove() }
        }
        r.module.deoptimize(method)
    }

    private fun hookLongClick(method: Method) {
        if (!hookedCallbacks.add(method)) return
        r.hook("qs-click:exclude-long:$method", method) { chain ->
            val previous = longClicking.get()
            longClicking.set(true)
            try { chain.proceed() } finally { if (previous == null) longClicking.remove() else longClicking.set(previous) }
        }
        r.module.deoptimize(method)
    }
}
