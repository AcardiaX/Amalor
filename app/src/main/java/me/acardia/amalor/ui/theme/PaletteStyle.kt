package me.acardia.amalor.ui.theme

enum class PaletteStyle(val displayName: String, val desc: String = "") {
    TonalSpot("Tonal Spot"),
    Neutral("Neutral"),
    Vibrant("Vibrant"),
    Expressive("Expressive"),
    Rainbow("Rainbow"),
    FruitSalad("FruitSalad"),
    Monochrome("Monochrome"),
    Fidelity("Fidelity"),
    Content("Content"),
    Cmf("CMF"),
    ;

    val supportsSpec2025: Boolean
        get() = this == TonalSpot ||
            this == Neutral ||
            this == Vibrant ||
            this == Expressive

    val supportedSpecs: List<ThemeColorSpec>
        get() = when {
            this == Cmf -> listOf(ThemeColorSpec.SPEC_2026)
            supportsSpec2025 -> listOf(ThemeColorSpec.SPEC_2021, ThemeColorSpec.SPEC_2025)
            else -> listOf(ThemeColorSpec.SPEC_2021)
        }

    fun effectiveSpec(preferred: ThemeColorSpec): ThemeColorSpec =
        preferred.takeIf { it in supportedSpecs } ?: supportedSpecs.last()

    companion object {
        fun fromValueOrDefault(value: String) = entries.find { it.name == value } ?: Expressive
    }
}
