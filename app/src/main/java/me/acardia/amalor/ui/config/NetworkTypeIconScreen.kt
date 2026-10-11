package me.acardia.amalor.ui.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.rounded.Start
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.NetworkTypeOptions
import me.acardia.amalor.ui.component.BaseWidget
import me.acardia.amalor.ui.component.GroupedDropdownMenuPopup
import me.acardia.amalor.ui.component.SegmentedColumn
import me.acardia.amalor.ui.component.SwitchWidget

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
    val boldEnabled = enabled && NetworkTypeOptions.canUseBold(if (checked) 1 else 0, font)
    LaunchedEffect(checked, enabled) {
        if (!checked || !enabled) fontMenuExpanded = false
    }
    val fontOptions = listOf(
        stringResource(R.string.config_font_official),
        stringResource(R.string.config_font_system),
        stringResource(R.string.config_font_google_rounded),
        stringResource(R.string.config_font_custom),
    )
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val headingGap = with(LocalDensity.current) { 16.dp.toPx() }
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
            NetworkMasterSwitch(checked, enabled, onCheckedChange, Modifier.fillMaxWidth().padding(16.dp))
            AnimatedVisibility(
                visible = checked,
                enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec(), expandFrom = Alignment.Top, clip = false),
                // Effects springs are critically damped: collapsing must not rebound below zero.
                exit = shrinkVertically(MaterialTheme.motionScheme.fastEffectsSpec(), shrinkTowards = Alignment.Top, clip = false),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.config_network_icon_settings),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .animateEnterExit(
                                enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                                exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                            )
                            .padding(start = 16.dp, top = 8.dp, bottom = 16.dp),
                    )
                    // Reveal just below the heading, including its bottom spacing. Entry only
                    // clips above this boundary; leave room below for the spatial spring rebound.
                    Box(
                        if (checked) Modifier.drawWithContent {
                            clipRect(
                                left = -size.width,
                                top = -headingGap,
                                right = size.width * 2,
                                bottom = size.height * 2,
                            ) { this@drawWithContent.drawContent() }
                        } else Modifier.clipToBounds(),
                    ) {
                    SegmentedColumn(
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.animateEnterExit(
                            enter = slideInVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { -it },
                            exit = slideOutVertically(MaterialTheme.motionScheme.fastEffectsSpec()) { -it },
                        ),
                    ) {
                item { shape ->
                    SwitchWidget(title = stringResource(R.string.config_show_separately), description = stringResource(R.string.config_show_separately_desc), shape = shape, iconVector = Icons.Rounded.Start, enabled = false, checked = true, onCheckedChange = {})
                }
                item { shape ->
                    SwitchWidget(title = stringResource(R.string.config_bold_style), description = stringResource(R.string.config_bold_style_desc), shape = shape, iconVector = Icons.Rounded.FormatBold, enabled = boldEnabled, checked = bold, onCheckedChange = onBoldChange)
                }
                item { shape ->
                    BaseWidget(
                        title = stringResource(R.string.config_font_style),
                        description = fontOptions[font],
                        shape = shape,
                        enabled = enabled && checked,
                        icon = androidx.compose.ui.res.painterResource(R.drawable.ic_custom_font),
                        onClick = { fontMenuExpanded = true },
                        foreContent = {
                            GroupedDropdownMenuPopup(fontMenuExpanded, { fontMenuExpanded = false }, listOf(fontOptions.size)) { _, index, itemShapes ->
                                androidx.compose.material3.SelectableDropdownMenuItem(
                                    selected = index == font,
                                    onClick = { onFontChange(index); fontMenuExpanded = false },
                                    text = { Text(fontOptions[index]) },
                                    enabled = index != NetworkTypeOptions.CUSTOM,
                                    shapes = itemShapes,
                                )
                            }
                        },
                    )
                }
                }
                }
                }
            }
        }
    }
    if (showRestartDialog) {
        RestartSystemUiDialog(onDismiss = { showRestartDialog = false }, onRestart = onRestartSystemUi)
    }
}

@Composable
private fun NetworkMasterSwitch(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val change: (Boolean) -> Unit = { value ->
        haptic.performHapticFeedback(if (value) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff)
        onCheckedChange(value)
    }
    Row(
        modifier.clip(RoundedCornerShape(35.dp)).background(MaterialTheme.colorScheme.primaryContainer)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = change).padding(horizontal = 20.dp).heightIn(min = 72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = stringResource(R.string.config_enable_modification), style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f).padding(end = 16.dp))
        Switch(enabled = enabled, checked = checked, onCheckedChange = null, thumbContent = { Icon(if (checked) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(SwitchDefaults.IconSize)) })
    }
}
