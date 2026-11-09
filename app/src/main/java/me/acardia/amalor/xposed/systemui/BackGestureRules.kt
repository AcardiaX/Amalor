package me.acardia.amalor.xposed.systemui

/** Adapted from ONextBox BackGestureRules/BackGestureRouteSlot (GPL-3.0). */
internal object BackGestureRules {
    fun predictiveRoute(master: Boolean, predictive: Boolean, edge: Int, type: Int, cancelled: Boolean): Boolean =
        master && predictive && edge in 0..1 && type in 2..3 && !cancelled

    fun haptics(configured: Int, nativeAosp: Boolean): Int =
        if (configured in 0..1) configured else if (nativeAosp) 0 else 1

    fun cornerPolicy(resetCurve: Boolean, standardWrite: Boolean): Boolean? = when {
        resetCurve -> true
        standardWrite -> false
        else -> null
    }

    fun closingAlpha(progress: Float): Float =
        if (progress.isFinite()) (1f - 5f * progress.coerceIn(0f, 1f)).coerceIn(0f, 1f) else 1f

    data class HomeFrame(val left: Float, val top: Float, val width: Float, val height: Float, val progress: Float)

    fun homePreview(width: Float, height: Float, progress: Float, edge: Int, deltaY: Float, margin: Float): HomeFrame? {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f ||
            !progress.isFinite() || !deltaY.isFinite() || !margin.isFinite() || edge !in 0..1) return null
        val bounded = progress.coerceIn(0f, 1f)
        val eased = 1f - (1f - bounded) * (1f - bounded)
        val scale = 1f - 0.15f * eased
        val w = width * scale
        val h = height * scale
        val inset = margin.coerceIn(0f, minOf(width, height) * 0.075f)
        val y = (kotlin.math.abs(deltaY) / (height * 0.5f)).coerceIn(0f, 1f)
        val yEase = 1f - (1f - y) * (1f - y)
        val shift = maxOf(0f, (height - h) * 0.5f - inset) * yEase * kotlin.math.sign(deltaY)
        val left = if (edge == 1) eased * inset else width - w - eased * inset
        return HomeFrame(left, (height - h) * 0.5f + shift, w, h, eased)
    }
}

/** Identity-scoped standard writes must never disable another transaction's vendor behavior. */
internal class BackCornerScope {
    private val target = ThreadLocal<Any?>()
    val active: Boolean get() = target.get() != null
    fun matches(value: Any?): Boolean = value != null && target.get() === value

    fun <T> withTarget(value: Any, action: () -> T): T {
        val previous = target.get()
        target.set(value)
        return try { action() } finally {
            if (previous == null) target.remove() else target.set(previous)
        }
    }
}

internal class BackGestureRouteSlot<T : Any> {
    private var original: T? = null
    private var selected: T? = null

    fun replace(native: T, replacement: T): T {
        original = native
        selected = replacement
        return replacement
    }

    fun restore(current: T?): T? {
        val restored = if (selected != null && current === selected) original else current
        original = null
        selected = null
        return restored
    }
}
