package me.acardia.amalor.ui.theme

enum class ThemeColorSpec(val displayName: String) {
    SPEC_2021("Material 3 (2021)"),
    SPEC_2025("Expressive (2025)"),
    SPEC_2026("Material 3 (2026)"),
    ;

    companion object {
        fun fromValueOrDefault(value: String) = entries.find { it.name == value } ?: SPEC_2025
    }
}
