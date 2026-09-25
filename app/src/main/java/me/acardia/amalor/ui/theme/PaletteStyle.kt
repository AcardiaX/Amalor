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
    ;

    val supportsSpec2025: Boolean
        get() = this == TonalSpot ||
            this == Neutral ||
            this == Vibrant ||
            this == Expressive

    companion object {
        fun fromValueOrDefault(value: String) = entries.find { it.name == value } ?: Expressive
    }
}
