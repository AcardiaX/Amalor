package me.acardia.amalor.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.ui.component.SegmentedColumn
import me.acardia.amalor.ui.component.SwitchWidget
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusBarScreen(
    mode: Int,
    marginsExpanded: Boolean,
    onMarginsExpandedChange: (Boolean) -> Unit,
    leftMargin: Int,
    rightMargin: Int,
    onMarginsEnabledChange: (Boolean) -> Unit,
    onLeftMarginChange: (Int) -> Unit,
    onRightMarginChange: (Int) -> Unit,
    batteryBodyFill: Boolean,
    onBatteryBodyFillChange: (Boolean) -> Unit,
    hideNonDataSim: Boolean,
    onHideNonDataSimChange: (Boolean) -> Unit,
    canRestartSystemUi: Boolean,
    onRestartSystemUi: () -> Boolean,
    onBack: () -> Unit,
    onOpenNetworkType: () -> Unit,
    onModeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRestartDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(stringResource(R.string.config_status_bar)) },
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(top = 8.dp).padding(horizontal = 16.dp)) {
            SegmentedColumn(title = stringResource(R.string.statusbar_layout_group), contentPadding = PaddingValues(0.dp)) {
                item {
                    ExpandableConfigHeader(
                        title = stringResource(R.string.statusbar_margins_title),
                        description = stringResource(R.string.statusbar_margins_desc),
                        icon = painterResource(R.drawable.ic_side_margins),
                        expanded = marginsExpanded,
                        onExpandedChange = onMarginsExpandedChange,
                    )
                }
                item(animatedVisibility = marginsExpanded) { shape ->
                    MarginSlider(stringResource(R.string.statusbar_margin_left), true, leftMargin, marginsExpanded, shape) {
                        onMarginsEnabledChange(true)
                        onLeftMarginChange(it)
                    }
                }
                item(animatedVisibility = marginsExpanded) { shape ->
                    MarginSlider(stringResource(R.string.statusbar_margin_right), false, rightMargin, marginsExpanded, shape) {
                        onMarginsEnabledChange(true)
                        onRightMarginChange(it)
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
            SegmentedColumn(title = stringResource(R.string.config_battery_icon), contentPadding = PaddingValues(0.dp)) {
                item { shape ->
                    SwitchWidget(
                        title = stringResource(R.string.config_battery_level_display),
                        description = stringResource(R.string.config_battery_level_display_desc),
                        shape = shape,
                        iconVector = Icons.Rounded.Percent,
                        checked = batteryBodyFill,
                        onCheckedChange = onBatteryBodyFillChange,
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
            SegmentedColumn(title = stringResource(R.string.config_mobile_network), contentPadding = PaddingValues(0.dp)) {
                item { shape ->
                    SwitchWidget(
                        title = stringResource(R.string.config_mobile_network_type),
                        description = stringResource(R.string.config_mobile_network_type_desc),
                        shape = shape,
                        icon = painterResource(R.drawable.ic_mobiledata),
                        checked = mode != 0,
                        onCheckedChange = { onModeChange(if (it) 1 else 0) },
                        onClick = onOpenNetworkType,
                        trailingDivider = true,
                    )
                }
                item { shape ->
                    SwitchWidget(
                        title = stringResource(R.string.config_hide_non_data_sim),
                        description = stringResource(R.string.config_hide_non_data_sim_desc),
                        shape = shape,
                        icon = painterResource(R.drawable.ic_hide_non_data_sim),
                        checked = hideNonDataSim,
                        onCheckedChange = onHideNonDataSimChange,
                    )
                }
            }
        }
    }
    if (showRestartDialog) {
        RestartSystemUiDialog(onDismiss = { showRestartDialog = false }, onRestart = onRestartSystemUi)
    }
}

@Composable
private fun MarginSlider(title: String, isLeft: Boolean, amount: Int, expanded: Boolean, shape: Shape, onChange: (Int) -> Unit) {
    var draft by remember(amount) { mutableFloatStateOf(amount.toFloat()) }
    ExpandableConfigSurface(expanded, shape) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (draft.roundToInt() != 0) {
                    Box(
                        modifier = Modifier.size(24.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable { draft = 0f; onChange(0) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_reset),
                            stringResource(R.string.statusbar_margin_reset),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Box(
                    modifier = Modifier.height(24.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(
                            R.string.statusbar_margin_value,
                            draft.roundToInt(),
                        ),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange(draft.roundToInt()) },
            valueRange = -10f..10f,
            steps = 19,
        )
    }
}
