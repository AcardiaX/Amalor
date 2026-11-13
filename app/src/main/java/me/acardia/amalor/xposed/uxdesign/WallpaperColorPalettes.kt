package me.acardia.amalor.xposed.uxdesign

import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import kotlin.math.roundToInt

/** Standard roles for the separate COUI/NX wallpaper XML; never a replacement Monet seed. */
internal object WallpaperColorPalettes {
    fun styleForIndex(index: Int): String? = when (index) {
        21 -> "TONAL_SPOT"
        22 -> "SPRITZ"
        23 -> "VIBRANT"
        24 -> "EXPRESSIVE"
        else -> null
    }

    fun scheme(seed: Int, style: String, dark: Boolean, contrast: Double = 0.0): DynamicScheme? {
        val source = Hct.fromInt(seed or -0x1000000)
        // Match the normal system scheme's achromatic fallback.
        val hct = if (seed == 0 || source.chroma < 5.0) Hct.fromInt(0xff1b6ef3.toInt()) else source
        val spec = ColorSpec.SpecVersion.SPEC_2025
        return when (style) {
            "TONAL_SPOT" -> SchemeTonalSpot(hct, dark, contrast, spec)
            "SPRITZ" -> SchemeNeutral(hct, dark, contrast, spec)
            "VIBRANT" -> SchemeVibrant(hct, dark, contrast, spec)
            "EXPRESSIVE" -> SchemeExpressive(hct, dark, contrast, spec)
            else -> null
        }
    }

    fun alpha(color: Int, opacity: Double): Int =
        (color and 0xffffff) or ((255 * opacity).roundToInt().coerceIn(0, 255) shl 24)

    fun pressed(background: Int, foreground: Int): Int {
        var result = -0x1000000
        for (shift in intArrayOf(16, 8, 0)) {
            val bg = (background ushr shift) and 255
            val fg = (foreground ushr shift) and 255
            result = result or ((bg * 0.9 + fg * 0.1).roundToInt() shl shift)
        }
        return result
    }
}

/** Native XML order after the host's (4,2), then (2,0) swaps: N2, A2, N1, A1, A3. */
internal enum class WallpaperColorFamily(val xmlName: String) {
    NEUTRAL_VARIANT("Green"),
    SECONDARY("Red"),
    NEUTRAL("Yellow"),
    PRIMARY("Blue"),
    TERTIARY("Orange"),
}

internal class WallpaperColorRoles(scheme: DynamicScheme) {
    class Group(val normal: Int, val onNormal: Int, val container: Int, val onContainer: Int, onSurface: Int) {
        val disabledContainer = WallpaperColorPalettes.alpha(onSurface, 0.12)
        val disabledContent = WallpaperColorPalettes.alpha(onSurface, 0.38)
        // Native WallpaperColorWriter's seven resource suffixes, in its original order.
        val values = intArrayOf(normal, WallpaperColorPalettes.pressed(normal, onNormal),
            container, WallpaperColorPalettes.pressed(container, onContainer),
            WallpaperColorPalettes.alpha(normal, 0.4), disabledContainer, disabledContent)
    }

    private val groups = WallpaperColorFamily.entries.map { family ->
        when (family) {
            WallpaperColorFamily.NEUTRAL_VARIANT -> Group(scheme.onSurfaceVariant, scheme.surfaceVariant,
                scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.onSurface)
            WallpaperColorFamily.SECONDARY -> Group(scheme.secondary, scheme.onSecondary,
                scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.onSurface)
            WallpaperColorFamily.NEUTRAL -> Group(scheme.onSurface, scheme.surface,
                scheme.surfaceContainer, scheme.onSurface, scheme.onSurface)
            WallpaperColorFamily.PRIMARY -> Group(scheme.primary, scheme.onPrimary,
                scheme.primaryContainer, scheme.onPrimaryContainer, scheme.onSurface)
            WallpaperColorFamily.TERTIARY -> Group(scheme.tertiary, scheme.onTertiary,
                scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.onSurface)
        }
    }

    fun family(family: WallpaperColorFamily): Group = groups[family.ordinal]

    // COUI exposes one shared on-color resource, not separate onPrimary/onSecondary/onTertiary.
    // Surface supplies the neutral opposite-polarity foreground for these filled controls.
    // inverseOnSurface loses contrast against N2 at reduced system contrast.
    val sharedOnColor = scheme.surface
}

/** Carries the unmodified normal-Monet seed across the vendor's destructive list transforms. */
internal class WallpaperColorBindings {
    class Write(val colors: Any, val seed: Int, val index: Int, contrast: Double) {
        val style = WallpaperColorPalettes.styleForIndex(index)!!
        val light = WallpaperColorRoles(WallpaperColorPalettes.scheme(seed, style, false, contrast)!!)
        val dark = WallpaperColorRoles(WallpaperColorPalettes.scheme(seed, style, true, contrast)!!)
        var renderedFiles = 0
    }

    // ponytail: at most 64 transient list identities; bounded scans avoid mutable List hash keys.
    // Increase the cap only if the host starts retaining more than 64 in-flight picker copies.
    private val links = ArrayDeque<Pair<Any, Int>>()
    private val writing = ThreadLocal<Write?>()
    val current: Write? get() = writing.get()
    @Synchronized
    fun remember(colors: Any, seed: Int) {
        links.removeAll { it.first === colors }
        if (links.size == 64) links.removeFirst()
        links.addLast(colors to seed)
    }
    @Synchronized
    private fun seed(colors: Any): Int? = links.lastOrNull { it.first === colors }?.second

    fun <T> duringWrite(colors: Any, index: Int, contrast: Double, enabled: Boolean, action: (Write?) -> T): T {
        val previous = writing.get()
        val source = if (enabled && WallpaperColorPalettes.styleForIndex(index) != null) seed(colors) else null
        val next = source?.let { Write(colors, it, index, contrast) }
        writing.set(next)
        return try { action(next) } finally {
            if (previous == null) writing.remove() else writing.set(previous)
        }
    }
}
