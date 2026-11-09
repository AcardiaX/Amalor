package me.acardia.amalor.xposed.launcher

import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.score.Score
import java.util.LinkedHashMap

internal data class LauncherBadgeColors(val background: Int, val foreground: Int)

internal data class LauncherBadgePalette(val light: LauncherBadgeColors, val dark: LauncherBadgeColors) {
    fun colors(isDark: Boolean) = if (isDark) dark else light
}

/** Pure MCU work, called by the icon worker; no Compose theme or full ColorScheme is built. */
internal object LauncherBadgePalettes {
    private val palettes = object : LinkedHashMap<Int, LauncherBadgePalette>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, LauncherBadgePalette>?) = size > 128
    }

    fun fromPixels(pixels: IntArray): LauncherBadgePalette? {
        val opaque = pixels.filter { (it ushr 24) >= 128 }.map { it or -0x1000000 }.toIntArray()
        if (opaque.isEmpty()) return null
        val histogram = opaque.asIterable().groupingBy { it }.eachCount()
        val quantized = if (histogram.size <= 16) histogram else QuantizerCelebi.quantize(opaque, 16)
        val fallback = quantized.maxByOrNull { it.value }?.key ?: return null
        // A monochrome icon should keep its neutral seed, not fall back to Google Blue.
        val seed = Score.score(quantized, desired = 1, fallbackColorArgb = fallback).first()
        return fromSeed(seed)
    }

    @Synchronized
    fun fromSeed(seed: Int): LauncherBadgePalette = palettes.getOrPut(seed or -0x1000000) {
        val hct = Hct.fromInt(seed or -0x1000000)
        fun colors(dark: Boolean): LauncherBadgeColors {
            val scheme = if (hct.chroma < 5.0) SchemeMonochrome(hct, dark, 0.0, ColorSpec.SpecVersion.SPEC_2021)
                else SchemeTonalSpot(hct, dark, 0.0, ColorSpec.SpecVersion.SPEC_2025)
            return LauncherBadgeColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        }
        LauncherBadgePalette(colors(false), colors(true))
    }
}
