package me.acardia.amalor.xposed.systemui

internal object QsSliderRules {
    data class Geometry(val top: Float, val bottom: Float, val centerX: Float,
        val trackWidth: Float, val handleWidth: Float, val corner: Float, val unit: Float) {
        val thumbInset: Float get() = 2f * unit
    }

    fun geometry(width: Float, height: Float, density: Float, paddingTop: Int, paddingBottom: Int,
        scale: Float): Geometry? {
        if (!width.isFinite() || !height.isFinite() || !density.isFinite() || !scale.isFinite() ||
            density <= 0f || width <= 0f || scale <= 0f) return null
        val top = paddingTop.coerceAtLeast(0).toFloat()
        val end = height - paddingBottom.coerceAtLeast(0)
        val unit = density * scale
        // A 24dp inset icon needs room on either side of the handle, including its gap.
        if (!unit.isFinite() || end - top < 96f * unit || width < 48f * unit) return null
        // Keep geometry proportional to the actual slot, including the enlarged detail slider.
        return Geometry(top, end, width / 2f,
            width * 0.875f, width * 0.9375f, 16f * unit, unit)
    }

    fun thumbY(geometry: Geometry, fraction: Float): Float =
        geometry.bottom - geometry.thumbInset - (geometry.bottom - geometry.top - 2f * geometry.thumbInset) *
            (if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f)

    fun iconY(geometry: Geometry, fraction: Float): Float {
        val thumb = thumbY(geometry, fraction)
        val unit = geometry.unit
        val resting = geometry.bottom - 28f * unit // 12dp half icon + 16dp bottom inset.
        val clearance = 24f * unit // Half icon + clearance + handle gap + half handle.
        return if (resting - thumb >= clearance) resting else minOf(resting, thumb - clearance)
    }

    fun detailWidth(sourceWidth: Float, sourceHeight: Float, detailHeight: Float, availableWidth: Float): Float? {
        if (!sourceWidth.isFinite() || !sourceHeight.isFinite() || !detailHeight.isFinite() ||
            !availableWidth.isFinite() || sourceWidth <= 0f || sourceHeight <= 0f || detailHeight <= 0f || availableWidth <= 0f) return null
        return minOf(sourceWidth * (detailHeight / sourceHeight), availableWidth)
    }
}
