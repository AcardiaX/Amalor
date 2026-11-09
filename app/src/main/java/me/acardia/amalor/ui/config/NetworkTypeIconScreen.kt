package me.acardia.amalor.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.NetworkTypeOptions
import me.acardia.amalor.ui.component.BaseWidget
import me.acardia.amalor.ui.component.GroupedDropdownMenuPopup
import me.acardia.amalor.ui.component.SwitchWidget
import me.acardia.amalor.ui.component.ExpandableContent
import me.acardia.amalor.ui.component.rememberExpandableItemShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NetworkTypeIconScreen(
    enabled: Boolean,
    canRestartSystemUi: Boolean,
    onRestartSystemUi: () -> Boolean,
    checked: Boolean,
    bold: Boolean,
    font: Int,
    onBoldChange: (Boolean) -> Unit,
    onFontChange: (Int) -> Unit,
    onBack: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRestartDialog by remember { mutableStateOf(false) }
    var fontMenuExpanded by remember { mutableStateOf(false) }
    val fontStyleInteractionSource = remember { MutableInteractionSource() }
    val fontStylePressed by fontStyleInteractionSource.collectIsPressedAsState()
    val boldEnabled = enabled && NetworkTypeOptions.canUseBold(if (checked) 1 else 0, font)
    val fontStyleShape = rememberExpandableItemShape(boldEnabled, fontStylePressed)
    LaunchedEffect(checked, enabled) {
        if (!checked || !enabled) fontMenuExpanded = false
    }
    val fontOptions = listOf(
        stringResource(R.string.config_font_official),
        stringResource(R.string.config_font_system),
        stringResource(R.string.config_font_google_rounded),
        stringResource(R.string.config_font_pixel),
    )
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(stringResource(R.string.config_mobile_network_type)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(50)),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
                actions = { RestartSystemUiAction(canRestartSystemUi) { showRestartDialog = true } },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
            ConfigMasterSwitch(stringResource(R.string.config_show_separately), checked, enabled,
                onCheckedChange, Modifier.fillMaxWidth().padding(16.dp))
            ExpandableConfigSection(visible = checked, title = stringResource(R.string.config_network_type_options)) {
                item { shape ->
                    BaseWidget(
                        title = stringResource(R.string.config_font_style),
                        description = fontOptions[font],
                        shape = fontStyleShape,
                        pressedShape = fontStyleShape,
                        interactionSource = fontStyleInteractionSource,
                        enabled = enabled && checked,
                        icon = androidx.compose.ui.res.painterResource(R.drawable.ic_custom_font),
                        onClick = { fontMenuExpanded = true },
                        foreContent = {
                            GroupedDropdownMenuPopup(fontMenuExpanded, { fontMenuExpanded = false }, listOf(fontOptions.size)) { _, index, itemShapes ->
                                androidx.compose.material3.SelectableDropdownMenuItem(
                                    selected = index == font,
                                    onClick = { onFontChange(index); fontMenuExpanded = false },
                                    text = { Text(fontOptions[index]) },
                                    enabled = true,
                                    shapes = itemShapes,
                                )
                            }
                        },
                    )
                }
                item(animatedVisibility = boldEnabled) { shape ->
                    AnimatedBoldSetting(visible = boldEnabled, shape = shape, bold = bold, onBoldChange = onBoldChange)
                }
            }
        }
    }
    if (showRestartDialog) {
        RestartSystemUiDialog(onDismiss = { showRestartDialog = false }, onRestart = onRestartSystemUi)
    }
}

@Composable
private fun AnimatedBoldSetting(
    visible: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    bold: Boolean,
    onBoldChange: (Boolean) -> Unit,
) {
    ExpandableContent(visible = visible) {
        SwitchWidget(
            title = stringResource(R.string.config_bold_style),
            description = stringResource(R.string.config_bold_style_desc),
            shape = shape,
            iconVector = Icons.Rounded.FormatBold,
            checked = bold,
            onCheckedChange = onBoldChange,
        )
    }
}
