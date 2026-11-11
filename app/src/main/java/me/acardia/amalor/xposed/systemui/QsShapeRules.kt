package me.acardia.amalor.xposed.systemui

internal object QsShapeRules {
    enum class Kind { Single, Card, Inset, Media }

    fun radius(kind: Kind, width: Float, height: Float, density: Float, activeFraction: Float): Float {
        if (!width.isFinite() || !height.isFinite() || !density.isFinite() ||
            width <= 0f || height <= 0f || density <= 0f) return 0f
        val limit = minOf(width, height) / 2f
        val active = minOf(limit, when (kind) {
            Kind.Single -> 16f
            Kind.Card, Kind.Media -> 20f
            Kind.Inset -> 12f
        } * density)
        if (kind == Kind.Media) return active
        val fraction = if (activeFraction.isFinite()) activeFraction else 0f
        // Spatial springs may overshoot; the resulting outline must still fit its bounds.
        return (limit + (active - limit) * fraction).coerceIn(0f, limit)
    }

    /** Animated cover radii are already converted to local coordinates: null must not cap them. */
    fun outlineRadius(requested: Float, width: Int, height: Int, maximum: Float?): Float {
        if (!requested.isFinite() || width <= 0 || height <= 0) return 0f
        val bounds = minOf(width, height) / 2f
        val cap = maximum?.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: bounds
        return minOf(requested.coerceAtLeast(0f), cap, bounds)
    }
}
