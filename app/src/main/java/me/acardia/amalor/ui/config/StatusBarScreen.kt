package me.acardia.amalor.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Battery4Bar
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusBarScreen(
    mode: Int,
    batteryBodyFill: Boolean,
    onBatteryBodyFillChange: (Boolean) -> Unit,
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
            SegmentedColumn(title = stringResource(R.string.config_battery_icon), contentPadding = PaddingValues(0.dp)) {
                item { shape ->
                    SwitchWidget(
                        title = stringResource(R.string.config_battery_body_fill),
                        description = stringResource(R.string.config_battery_body_fill_desc),
                        shape = shape,
                        iconVector = Icons.Rounded.Battery4Bar,
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
            }
        }
    }
    if (showRestartDialog) {
        RestartSystemUiDialog(onDismiss = { showRestartDialog = false }, onRestart = onRestartSystemUi)
    }
}
