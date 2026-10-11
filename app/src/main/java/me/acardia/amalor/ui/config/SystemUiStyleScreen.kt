@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package me.acardia.amalor.ui.config

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.ui.component.BaseWidget
import me.acardia.amalor.ui.component.SegmentedColumn

private enum class SystemUiStyle(val index: Int) { BLUR(0), MONET(1) }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SystemUiStyleScreen(
    style: Int,
    enabled: Boolean,
    onBack: () -> Unit,
    onStyleChange: (Int) -> Unit,
    onOpenStatusBar: () -> Unit,
    onOpenOther: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = if (enabled) SystemUiStyle.entries.getOrElse(style.coerceIn(0, 1)) { SystemUiStyle.BLUR } else null
    val blurInteraction = remember { MutableInteractionSource() }
    val monetInteraction = remember { MutableInteractionSource() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(stringResource(R.string.config_system_ui)) },
                navigationIcon = { ConfigBackButton(onBack) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp)) {
            ButtonGroup(
                overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(menuState = it) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                customItem(
                    buttonGroupContent = {
                        ToggleButton(
                            checked = selected == SystemUiStyle.BLUR,
                            enabled = enabled,
                            onCheckedChange = { if (it) onStyleChange(SystemUiStyle.BLUR.index) },
                            interactionSource = blurInteraction,
                            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                            colors = ToggleButtonDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            modifier = Modifier.weight(1f).animateWidth(blurInteraction),
                        ) { Text(stringResource(R.string.config_system_ui_blur)) }
                    },
                    menuContent = {},
                )
                customItem(
                    buttonGroupContent = {
                        ToggleButton(
                            checked = selected == SystemUiStyle.MONET,
                            enabled = enabled,
                            onCheckedChange = { if (it) onStyleChange(SystemUiStyle.MONET.index) },
                            interactionSource = monetInteraction,
                            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                            colors = ToggleButtonDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            modifier = Modifier.weight(1f).animateWidth(monetInteraction),
                        ) { Text(stringResource(R.string.config_system_ui_monet)) }
                    },
                    menuContent = {},
                )
            }
            SegmentedColumn(modifier = Modifier.padding(top = 8.dp), contentPadding = PaddingValues(0.dp)) {
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
}
