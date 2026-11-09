package me.acardia.amalor.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.ui.component.SegmentedColumn
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LauncherPagesScreen(
    landing: Int,
    expanded: Boolean,
    canRestartLauncher: Boolean,
    onRestartLauncher: () -> Boolean,
    onLandingChange: (Int) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var showRestartDialog by remember { mutableStateOf(false) }
    var draft by remember(landing) { mutableFloatStateOf(landing.toFloat()) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(stringResource(R.string.launcher_pages)) },
                navigationIcon = { ConfigBackButton(onBack) },
                actions = { RestartSystemUiAction(canRestartLauncher, titleRes = R.string.restart_launcher) { showRestartDialog = true } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
            SegmentedColumn(title = stringResource(R.string.launcher_main_screen)) {
                item {
                    ExpandableConfigHeader(
                        title = stringResource(R.string.launcher_custom_home_screen_title),
                        description = stringResource(R.string.launcher_custom_home_screen_desc),
                        icon = painterResource(R.drawable.ic_default_page),
                        expanded = expanded,
                        onExpandedChange = onExpandedChange,
                    )
                }
                item(animatedVisibility = expanded) { shape ->
                    ExpandableConfigSurface(expanded, shape) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.launcher_main_screen),
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                            if (draft.roundToInt() != 0) {
                                Box(
                                    modifier = Modifier.size(24.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .clickable { draft = 0f; onLandingChange(0) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_reset),
                                        stringResource(R.string.launcher_reset_home_screen),
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
                                    draft.roundToInt().toString(),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        Slider(
                            value = draft,
                            onValueChange = { draft = it },
                            onValueChangeFinished = { onLandingChange(draft.roundToInt()) },
                            valueRange = 0f..10f,
                            steps = 9, // Nine interior stops plus the two endpoints.
                        )
                    }
                }
            }
        }
    }
    if (showRestartDialog) {
        RestartSystemUiDialog(
            onDismiss = { showRestartDialog = false },
            onRestart = onRestartLauncher,
            titleRes = R.string.restart_launcher,
        )
    }
}
