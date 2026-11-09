package me.acardia.amalor.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.NativeFeatureOptions
import me.acardia.amalor.ui.component.SegmentedColumn
import me.acardia.amalor.ui.component.SwitchWidget

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SystemUiOtherScreen(
    packageUpdateM3e: Boolean,
    nativeFeatures: Map<String, Boolean>,
    onNativeFeatureChange: (String, Boolean) -> Unit,
    canRestartSystemUi: Boolean,
    onRestartSystemUi: () -> Boolean,
    onPackageUpdateM3eChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenBackGesture: () -> Unit,
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
                title = { Text(stringResource(R.string.config_other)) },
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
            SegmentedColumn(title = stringResource(R.string.config_software_update)) {
                item { shape ->
                    SwitchWidget(
                        title = stringResource(R.string.config_replace_style),
                        description = stringResource(R.string.config_replace_style_desc),
                        icon = painterResource(R.drawable.ic_package_update),
                        shape = shape,
                        checked = packageUpdateM3e,
                        onCheckedChange = onPackageUpdateM3eChange,
                    )
                }
            }
            SegmentedColumn(title = stringResource(R.string.config_native_features)) {
                val features = listOf(
                    Triple(NativeFeatureOptions.POWER_MENU, R.string.config_native_power, R.drawable.ic_power_menu),
                    Triple(NativeFeatureOptions.BACK_PANEL, R.string.config_back_gesture, R.drawable.ic_edge_gesture),
                    Triple(NativeFeatureOptions.VOLUME_PANEL, R.string.config_native_volume, R.drawable.ic_volume_panel),
                    Triple(NativeFeatureOptions.CLIPBOARD, R.string.config_native_clipboard, R.drawable.ic_clipboard),
                )
                val descriptions = listOf(R.string.config_native_power_desc, R.string.config_back_gesture_desc,
                    R.string.config_native_volume_desc, R.string.config_native_clipboard_desc)
                features.forEachIndexed { index, (key, title, icon) ->
                    item(key = key) { shape ->
                        SwitchWidget(
                            title = stringResource(title),
                            description = stringResource(descriptions[index]),
                            icon = painterResource(icon),
                            shape = shape,
                            checked = nativeFeatures[key] == true,
                            onCheckedChange = { onNativeFeatureChange(key, it) },
                            onClick = if (key == NativeFeatureOptions.BACK_PANEL) onOpenBackGesture else null,
                            trailingDivider = key == NativeFeatureOptions.BACK_PANEL,
                        )
                    }
                }
            }
        }
    }
    if (showRestartDialog) {
        RestartSystemUiDialog(onDismiss = { showRestartDialog = false }, onRestart = onRestartSystemUi)
    }
}
