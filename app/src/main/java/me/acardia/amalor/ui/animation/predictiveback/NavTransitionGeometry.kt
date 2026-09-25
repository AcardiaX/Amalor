package me.acardia.amalor.ui.animation.predictiveback

import kotlin.math.roundToInt

internal fun snapScaleToPixelExtent(scale: Float, extent: Float): Float =
    if (extent > 0f) (scale * extent).roundToInt() / extent else scale

internal fun snapTranslationToPixelEdge(
    translation: Float,
    scale: Float,
    extent: Float,
    pivotFraction: Float = 0.5f,
): Float {
    if (extent <= 0f) return translation
    val offset = extent * pivotFraction * (1f - scale)
    return (translation + offset).roundToInt() - offset
}
