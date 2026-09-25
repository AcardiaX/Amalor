@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package me.acardia.amalor.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.acardia.amalor.AppThemeMode
import me.acardia.amalor.AppThemeSettings
import me.acardia.amalor.R
import me.acardia.amalor.ui.component.BaseWidget
import me.acardia.amalor.ui.component.GroupedDropdownMenuPopup
import me.acardia.amalor.ui.component.SegmentedColumn
import me.acardia.amalor.ui.theme.PaletteStyle
import me.acardia.amalor.ui.theme.ThemeColorSpec
import me.acardia.amalor.BuildConfig

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = PaddingValues(0.dp),
    settings: AppThemeSettings,
    onSettingsChange: (AppThemeSettings) -> Unit,
    onOpenAbout: () -> Unit,
) {
    val topAppBarState = androidx.compose.material3.rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    var showThemeModeDialog by remember { mutableStateOf(false) }
    var showPaletteDialog by remember { mutableStateOf(false) }

    if (showThemeModeDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_theme_mode),
            values = listOf(
                stringResource(R.string.theme_mode_system),
                stringResource(R.string.theme_mode_light),
                stringResource(R.string.theme_mode_dark),
            ),
            selected = settings.mode.ordinal,
            onDismiss = { showThemeModeDialog = false },
            onSelect = {
                onSettingsChange(settings.copy(mode = AppThemeMode.entries[it]))
                showThemeModeDialog = false
            },
        )
    }

    if (showPaletteDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_palette_style),
            values = PaletteStyle.entries.map { it.displayName },
            selected = settings.paletteStyle.ordinal,
            onDismiss = { showPaletteDialog = false },
            onSelect = {
                val style = PaletteStyle.entries[it]
                onSettingsChange(
                    settings.copy(
                        paletteStyle = style,
                    ),
                )
                showPaletteDialog = false
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings), modifier = Modifier.padding(start = 12.dp)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + outerPadding,
            overscrollEffect = null,
        ) {
            item {
                SegmentedColumn(title = stringResource(R.string.settings_personalization)) {
                    item { shape ->
                        SettingItem(
                            title = stringResource(R.string.settings_theme_mode),
                            description = settings.mode.displayName(),
                            icon = Icons.Rounded.DarkMode,
                            iconRes = when (settings.mode) {
                                AppThemeMode.LIGHT -> R.drawable.ic_system_mode_light
                                AppThemeMode.DARK -> R.drawable.ic_system_mode_dark
                                AppThemeMode.SYSTEM -> R.drawable.ic_system_mode_auto
                            },
                            shape = shape,
                            onClick = { showThemeModeDialog = true },
                        )
                    }
                    item { shape ->
                        SettingItem(
                            title = stringResource(R.string.settings_palette_style),
                            description = settings.paletteStyle.displayName,
                            icon = Icons.Rounded.Palette,
                            iconRes = R.drawable.ic_palette_style,
                            shape = shape,
                            onClick = { showPaletteDialog = true },
                        )
                    }
                    item { shape ->
                        val specs = if (settings.paletteStyle.supportsSpec2025) {
                            ThemeColorSpec.entries
                        } else {
                            listOf(ThemeColorSpec.SPEC_2021)
                        }
                        SettingMenuItem(
                            title = stringResource(R.string.settings_color_spec),
                            description = settings.colorSpec.displayName,
                            icon = Icons.Rounded.Palette,
                            iconRes = R.drawable.ic_color_spec,
                            values = specs.map { it.displayName },
                            selected = specs.indexOf(settings.colorSpec).coerceAtLeast(0),
                            shape = shape,
                            enabled = settings.paletteStyle.supportsSpec2025,
                            disabledDescription = if (settings.paletteStyle.supportsSpec2025) null else stringResource(R.string.theme_settings_color_spec_only_2021),
                        ) { onSettingsChange(settings.copy(colorSpec = specs[it])) }
                    }
                }
            }
            item {
                SegmentedColumn(title = stringResource(R.string.settings_about)) {
                    item { shape ->
                        SettingItem(
                            title = stringResource(R.string.settings_about_amalor),
                            description = stringResource(R.string.settings_app_version, BuildConfig.VERSION_NAME),
                            icon = Icons.Rounded.Info,
                            iconRes = R.drawable.ic_about,
                            shape = shape,
                            onClick = onOpenAbout,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppThemeMode.displayName(): String = stringResource(
    when (this) {
        AppThemeMode.SYSTEM -> R.string.theme_mode_system
        AppThemeMode.LIGHT -> R.string.theme_mode_light
        AppThemeMode.DARK -> R.string.theme_mode_dark
    },
)

@Composable
private fun SettingItem(
    title: String,
    description: String,
    icon: ImageVector,
    iconRes: Int,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
) {
    BaseWidget(
        title = title,
        description = description,
        icon = painterResource(iconRes),
        iconVector = icon,
        shape = shape,
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingMenuItem(
    title: String,
    description: String,
    icon: ImageVector,
    iconRes: Int,
    values: List<String>,
    selected: Int,
    shape: androidx.compose.ui.graphics.Shape,
    enabled: Boolean = true,
    disabledDescription: String? = null,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    BaseWidget(
        title = title,
        description = disabledDescription ?: description,
        icon = painterResource(iconRes),
        iconVector = icon,
        shape = shape,
        enabled = enabled,
        onClick = if (enabled) ({ expanded = true }) else null,
        foreContent = {
            GroupedDropdownMenuPopup(expanded, { expanded = false }, listOf(values.size)) { _, index, itemShape ->
                SelectableDropdownMenuItem(
                    selected = index == selected,
                    onClick = { onSelected(index); expanded = false },
                    text = { Text(values[index]) },
                    shapes = itemShape,
                )
            }
        },
    )
}

@Composable
private fun SelectionDialog(
    title: String,
    values: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                values.forEachIndexed { index, value ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(index) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(index == selected, { onSelect(index) })
                        Spacer(Modifier.width(8.dp))
                        Text(value)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
