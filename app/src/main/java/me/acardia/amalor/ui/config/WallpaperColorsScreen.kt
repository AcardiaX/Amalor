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
import me.acardia.amalor.ui.component.SegmentedColumn
import me.acardia.amalor.ui.component.SwitchWidget

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WallpaperColorsScreen(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    canRestart: Boolean,
    onRestart: () -> Boolean,
    onBack: () -> Unit,
) {
    var showRestartDialog by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.config_uxdesign)) },
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                navigationIcon = { ConfigBackButton(onBack) },
                actions = { RestartSystemUiAction(canRestart, R.string.restart_uxdesign) { showRestartDialog = true } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
            SegmentedColumn(title = stringResource(R.string.wallpaper_colors_group)) {
                item { shape ->
                    SwitchWidget(
                        title = stringResource(R.string.wallpaper_colors_2025_title),
                        description = stringResource(R.string.wallpaper_colors_2025_desc),
                        icon = painterResource(R.drawable.ic_color_spec),
                        shape = shape,
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                    )
                }
            }
        }
    }
    if (showRestartDialog) RestartSystemUiDialog(
        onDismiss = { showRestartDialog = false },
        onRestart = onRestart,
        titleRes = R.string.restart_uxdesign,
    )
}
