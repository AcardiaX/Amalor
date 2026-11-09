@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package me.acardia.amalor.ui.config

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.ui.component.BaseWidget
import me.acardia.amalor.ui.component.SegmentedColumn

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SystemUiStyleScreen(
    onBack: () -> Unit,
    onOpenStatusBar: () -> Unit,
    onOpenControlCenter: () -> Unit,
    onOpenOther: () -> Unit,
    materialEnabled: Boolean,
    onMaterialChange: (Boolean) -> Unit,
    canRestartSystemUi: Boolean,
    onRestartSystemUi: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var showRestartDialog by remember { mutableStateOf(false) }
    val interactions = remember { List(2) { MutableInteractionSource() } }
    fun selectMaterial(enabled: Boolean) {
        if (enabled != materialEnabled) onMaterialChange(enabled)
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(stringResource(R.string.config_system_ui)) },
                navigationIcon = { ConfigBackButton(onBack) },
                actions = { RestartSystemUiAction(canRestartSystemUi) { showRestartDialog = true } },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp)) {
            val modeColors = ToggleButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            ButtonGroup(
                overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(menuState = it) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                for (index in 0..1) {
                    val monet = index == 1
                    val label = if (monet) R.string.config_system_ui_monet else R.string.config_system_ui_blur
                    customItem(
                        buttonGroupContent = {
                            ToggleButton(
                                checked = materialEnabled == monet,
                                onCheckedChange = { selectMaterial(monet) },
                                shapes = if (monet) ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else ButtonGroupDefaults.connectedLeadingButtonShapes(),
                                colors = modeColors,
                                interactionSource = interactions[index],
                                modifier = Modifier.weight(1f).animateWidth(interactions[index])
                                    .semantics { role = Role.RadioButton },
                            ) { Text(stringResource(label), maxLines = 1, softWrap = false) }
                        },
                        menuContent = { menuState ->
                            SelectableDropdownMenuItem(
                                selected = materialEnabled == monet,
                                onClick = { selectMaterial(monet); menuState.dismiss() },
                                text = { Text(stringResource(label)) },
                                shapes = MenuDefaults.itemShape(index, 2),
                            )
                        },
                    )
                }
            }
            SegmentedColumn(title = stringResource(R.string.config), modifier = Modifier.padding(top = 8.dp), contentPadding = PaddingValues(0.dp)) {
                item { shape ->
                    BaseWidget(
                        title = stringResource(R.string.config_control_center),
                        shape = shape,
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        onClick = onOpenControlCenter,
                    )
                }
                item { shape ->
                    BaseWidget(
                        title = stringResource(R.string.config_status_bar),
                        shape = shape,
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        onClick = onOpenStatusBar,
                    )
                }
                item { shape ->
                    BaseWidget(
                        title = stringResource(R.string.config_other),
                        shape = shape,
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        onClick = onOpenOther,
                    )
                }
            }
        }
    }
    if (showRestartDialog) {
        RestartSystemUiDialog(onDismiss = { showRestartDialog = false }, onRestart = onRestartSystemUi)
    }
}
