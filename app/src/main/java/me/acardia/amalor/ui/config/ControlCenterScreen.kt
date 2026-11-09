package me.acardia.amalor.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.ui.component.SegmentedColumn
import me.acardia.amalor.ui.component.SwitchWidget

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ControlCenterScreen(
    brightnessIconClipBypass: Boolean,
    onBrightnessIconClipBypassChange: (Boolean) -> Unit,
    tileAnimationsDisabled: Boolean,
    onTileAnimationsDisabledChange: (Boolean) -> Unit,
    canRestartSystemUi: Boolean,
    onRestartSystemUi: () -> Boolean,
    onBack: () -> Unit,
) {
    var showRestartDialog by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(stringResource(R.string.config_control_center)) },
                navigationIcon = { ConfigBackButton(onBack) },
                actions = { RestartSystemUiAction(canRestartSystemUi) { showRestartDialog = true } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp)) {
            SegmentedColumn(title = stringResource(R.string.config_tile_icons), modifier = Modifier.padding(top = 8.dp), contentPadding = PaddingValues(0.dp)) {
                item { shape ->
                    SwitchWidget(
                        title = stringResource(R.string.config_auto_brightness_icon_clip_bypass),
                        description = stringResource(R.string.config_auto_brightness_icon_clip_bypass_desc),
                        shape = shape,
                        icon = androidx.compose.ui.res.painterResource(R.drawable.ic_brightness),
                        checked = brightnessIconClipBypass,
                        onCheckedChange = onBrightnessIconClipBypassChange,
                    )
                }
                item { shape ->
                    SwitchWidget(
                        title = stringResource(R.string.config_disable_tile_icon_animation),
                        description = stringResource(R.string.config_disable_tile_icon_animation_desc),
                        shape = shape,
                        iconVector = Icons.Rounded.Block,
                        checked = tileAnimationsDisabled,
                        onCheckedChange = onTileAnimationsDisabledChange,
                    )
                }
            }
        }
    }
    if (showRestartDialog) {
        RestartSystemUiDialog(onDismiss = { showRestartDialog = false }, onRestart = onRestartSystemUi)
    }
}
