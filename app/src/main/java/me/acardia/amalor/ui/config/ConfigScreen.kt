@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package me.acardia.amalor.ui.config

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.ModuleConfig
import me.acardia.amalor.ui.component.BaseWidget
import me.acardia.amalor.ui.component.GroupedDropdownMenuPopup
import me.acardia.amalor.ui.component.SegmentedColumn

private enum class ConfigOption(val index: Int) { FIRST(0), SECOND(1) }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConfigScreen(
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = PaddingValues(0.dp),
    isRootAuthorized: Boolean = false,
    onSave: (signal: Int, style: Int, padding: Int) -> Unit = { _, _, _ -> },
    initialConfig: ModuleConfig = ModuleConfig.default,
    saving: Boolean = false,
    saveResult: Boolean? = null,
    onSaveResultShown: () -> Unit = {},
) {
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)
    var signal by rememberSaveable(initialConfig.signal) { mutableStateOf(ConfigOption.entries[initialConfig.signal.coerceIn(0, 1)]) }
    var systemUi by rememberSaveable(initialConfig.style) { mutableStateOf(ConfigOption.entries[initialConfig.style.coerceIn(0, 1)]) }
    var notification by rememberSaveable(initialConfig.padding) { mutableStateOf(ConfigOption.entries[initialConfig.padding.coerceIn(0, 1)]) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var handledSaveResult by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val saveMessage = stringResource(if (saveResult == true) R.string.config_save_success else R.string.config_save_failed)

    LaunchedEffect(saveResult) {
        if (saveResult == null) {
            handledSaveResult = null
            return@LaunchedEffect
        }
        saveResult.takeIf { it != handledSaveResult }?.let {
            handledSaveResult = it
            snackbarHostState.showSnackbar(saveMessage)
            onSaveResultShown()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = outerPadding.calculateBottomPadding())
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.config), modifier = Modifier.padding(start = 12.dp)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = {
            SmallExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.save)) },
                icon = { Icon(painterResource(R.drawable.ic_save), contentDescription = stringResource(R.string.save)) },
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .then(if (isRootAuthorized) Modifier else Modifier.semantics { disabled() }),
                shape = MaterialTheme.shapes.large,
                elevation = FloatingActionButtonDefaults.elevation(),
                containerColor = if (isRootAuthorized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (isRootAuthorized) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                onClick = {
                    if (isRootAuthorized && !saving) {
                        onSave(signal.index, systemUi.index, notification.index)
                        dirty = false
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + outerPadding + PaddingValues(bottom = 96.dp),
            overscrollEffect = null,
        ) {
            item {
                SegmentedColumn(
                    title = stringResource(R.string.config_style),
                ) {
                    item { shape ->
                        ConfigDropdown(
                            title = stringResource(R.string.config_signal),
                            choice = signal,
                            options = listOf(stringResource(R.string.config_signal_single), stringResource(R.string.config_signal_dual)),
                            iconRes = R.drawable.ic_signal,
                            shape = shape,
                            enabled = isRootAuthorized,
                        ) { signal = it; dirty = true }
                    }
                    item { shape ->
                        ConfigDropdown(
                            title = stringResource(R.string.config_system_ui),
                            choice = systemUi,
                            options = listOf(stringResource(R.string.config_system_ui_blur), stringResource(R.string.config_system_ui_monet)),
                            iconRes = R.drawable.ic_systemui,
                            shape = shape,
                            enabled = isRootAuthorized,
                        ) { systemUi = it; dirty = true }
                    }
                    item { shape ->
                        ConfigDropdown(
                            title = stringResource(R.string.config_notification_card),
                            choice = notification,
                            options = listOf(stringResource(R.string.config_notification_default), stringResource(R.string.config_notification_narrow)),
                            iconRes = R.drawable.ic_notif_card,
                            shape = shape,
                            enabled = isRootAuthorized,
                        ) { notification = it; dirty = true }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConfigDropdown(
    title: String,
    choice: ConfigOption,
    options: List<String>,
    iconRes: Int,
    shape: Shape,
    enabled: Boolean,
    onChoiceChange: (ConfigOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    BaseWidget(
        title = title,
        description = options[choice.index],
        shape = shape,
        icon = painterResource(iconRes),
        enabled = enabled,
        onClick = if (enabled) ({ expanded = true }) else null,
        foreContent = {
            if (enabled) {
                GroupedDropdownMenuPopup(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    groupSizes = listOf(options.size),
                ) { _, index, itemShapes ->
                    SelectableDropdownMenuItem(
                        selected = index == choice.index,
                        onClick = {
                            onChoiceChange(if (index == 0) ConfigOption.FIRST else ConfigOption.SECOND)
                            expanded = false
                        },
                        text = { Text(options[index]) },
                        shapes = itemShapes,
                    )
                }
            }
        },
    )
}
