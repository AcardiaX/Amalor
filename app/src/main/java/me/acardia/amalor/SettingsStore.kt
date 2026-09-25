package me.acardia.amalor

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.amalorSettingsDataStore by preferencesDataStore(name = "amalor_settings")

val Context.amalorThemeSettings: Flow<AppThemeSettings>
    get() = amalorSettingsDataStore.data.map { preferences ->
        AppThemeSettings(
            mode = preferences[ThemeSettingKeys.MODE]?.let { value ->
                runCatching { AppThemeMode.valueOf(value) }.getOrDefault(AppThemeMode.SYSTEM)
            } ?: AppThemeMode.SYSTEM,
            paletteStyle = preferences[ThemeSettingKeys.PALETTE]?.let { value ->
                runCatching { me.acardia.amalor.ui.theme.PaletteStyle.valueOf(value) }
                    .getOrDefault(me.acardia.amalor.ui.theme.PaletteStyle.TonalSpot)
            } ?: me.acardia.amalor.ui.theme.PaletteStyle.TonalSpot,
            colorSpec = preferences[ThemeSettingKeys.COLOR_SPEC]?.let { value ->
                runCatching { me.acardia.amalor.ui.theme.ThemeColorSpec.valueOf(value) }
                    .getOrDefault(me.acardia.amalor.ui.theme.ThemeColorSpec.SPEC_2025)
            } ?: me.acardia.amalor.ui.theme.ThemeColorSpec.SPEC_2025,
        )
    }

suspend fun Context.saveAmalorThemeSettings(settings: AppThemeSettings) {
    amalorSettingsDataStore.edit { preferences ->
        preferences[ThemeSettingKeys.MODE] = settings.mode.name
        preferences[ThemeSettingKeys.PALETTE] = settings.paletteStyle.name
        preferences[ThemeSettingKeys.COLOR_SPEC] = settings.colorSpec.name
    }
}

private object ThemeSettingKeys {
    val MODE = stringPreferencesKey("theme_mode")
    val PALETTE = stringPreferencesKey("theme_palette_style")
    val COLOR_SPEC = stringPreferencesKey("theme_color_spec")
}
