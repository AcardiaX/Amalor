package me.acardia.amalor.xposed.systemui

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.math.abs

internal object QsClickMotionRules {
    // ButtonGroupDefaults.ExpandedRatio, with compression bounded by the neighbouring tile.
    private const val ExpandedRatio = 0.15f

    fun clickBinding(type: Class<*>, declaration: Method): Method? =
        type.getMethod(declaration.name, *declaration.parameterTypes).takeUnless { Modifier.isAbstract(it.modifiers) }

    fun deltas(widths: FloatArray, clicked: Int, density: Float): FloatArray {
        val result = FloatArray(widths.size)
        if (clicked !in widths.indices || !density.isFinite() || density <= 0f ||
            widths.any { !it.isFinite() || it <= 0f }) return result
        if (widths.size == 1) {
            result[0] = -widths[0] * 0.06f
            return result
        }
        fun capacity(index: Int) = minOf(16f * density, widths[index] * ExpandedRatio)
        val growth = widths[clicked] * ExpandedRatio
        when (clicked) {
            0 -> minOf(growth, capacity(1)).let { result[0] = it; result[1] = -it }
            widths.lastIndex -> minOf(growth, capacity(clicked - 1)).let { result[clicked] = it; result[clicked - 1] = -it }
            else -> {
                val half = minOf(growth / 2f, capacity(clicked - 1), capacity(clicked + 1))
                result[clicked] = half * 2f
                result[clicked - 1] = -half
                result[clicked + 1] = -half
            }
        }
        return result
    }

    fun shouldPulse(handled: Boolean, longClick: Boolean): Boolean = handled && !longClick

    // Used only while drawing a registered QS glyph, never on background/title View nodes.
    fun glyphScaleX(slotScale: Float): Float =
        if (slotScale.isFinite() && slotScale > 0f) 1f / slotScale else 1f
}

/** Click-only press/release clock. No frame work exists until click() is called. */
internal class QsClickPulse(widths: FloatArray, private val density: Float) {
    private val widths = widths.copyOf()
    private val springs = Array(widths.size) { QsShapeSpring(0f) }
    val deltas = FloatArray(widths.size)
    private var clicked = -1
    private var targetDelta = 0f
    private var releasing = true
    private var durationScale = 1f
    val isRunning: Boolean get() = !releasing || springs.any { it.isRunning }

    fun click(index: Int, now: Long, scale: Float) {
        if (!scale.isFinite() || scale <= 0f || index !in widths.indices) { reset(); return }
        sampleValues(now)
        val targets = QsClickMotionRules.deltas(widths, index, density)
        if (targets[index] == 0f) { reset(); return }
        clicked = index
        targetDelta = targets[index]
        durationScale = scale
        releasing = false
        for (i in springs.indices) springs[i].retarget(targets[i], now, scale)
    }

    fun sample(now: Long) {
        sampleValues(now)
        // Match ButtonGroup's minimum visible press before releasing a short click.
        if (!releasing && (springs[clicked].value / targetDelta >= 0.75f || springs.none { it.isRunning })) {
            releasing = true
            for (spring in springs) spring.retarget(0f, now, durationScale)
        }
    }

    private fun sampleValues(now: Long) {
        var limit = 1f
        for (i in springs.indices) {
            springs[i].sample(now)
            val value = springs[i].value
            if (!value.isFinite()) { reset(); return }
            deltas[i] = value
            if (value != 0f) limit = minOf(limit, widths[i] * 0.25f / abs(value))
        }
        // Apply one common limit so interrupted springs cannot overlap or lose total row width.
        if (limit < 1f) for (i in deltas.indices) deltas[i] *= limit
    }

    fun reset() {
        for (spring in springs) spring.snapTo(0f)
        deltas.fill(0f)
        clicked = -1
        targetDelta = 0f
        releasing = true
    }
}
