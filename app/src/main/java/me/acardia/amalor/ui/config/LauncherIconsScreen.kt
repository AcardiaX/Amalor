package me.acardia.amalor.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.ui.component.SegmentedColumn
import me.acardia.amalor.ui.component.SwitchWidget

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LauncherIconsScreen(
    widgets: Boolean,
    colorfulDots: Boolean,
    onWidgetsChange: (Boolean) -> Unit,
    onColorfulDotsChange: (Boolean) -> Unit,
    canRestartLauncher: Boolean,
    onRestartLauncher: () -> Boolean,
    onBack: () -> Unit,
) {
    var showRestartDialog by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.launcher_app_icons)) },
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                navigationIcon = { ConfigBackButton(onBack) },
                actions = { RestartSystemUiAction(canRestartLauncher, R.string.restart_launcher) { showRestartDialog = true } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
            SegmentedColumn(title = stringResource(R.string.launcher_icons_group)) {
                item { shape ->
                    SwitchWidget(
                        title = stringResource(R.string.launcher_widgets_menu_title),
                        description = stringResource(R.string.launcher_widgets_menu_desc),
                        icon = painterResource(R.drawable.ic_widgets),
                        centerContent = true,
                        shape = shape,
                        checked = widgets,
                        onCheckedChange = onWidgetsChange,
                    )
                }
                item { shape ->
                    SwitchWidget(
                        title = stringResource(R.string.launcher_notification_badge_colors_title),
                        description = stringResource(R.string.launcher_notification_badge_colors_desc),
                        icon = painterResource(R.drawable.ic_monet_badge),
                        centerContent = true,
                        shape = shape,
                        checked = colorfulDots,
                        onCheckedChange = onColorfulDotsChange,
                    )
                }
            }
        }
    }
    if (showRestartDialog) RestartSystemUiDialog(
        onDismiss = { showRestartDialog = false },
        onRestart = onRestartLauncher,
        titleRes = R.string.restart_launcher,
    )
}
