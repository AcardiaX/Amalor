package me.acardia.amalor.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.ui.component.SwitchWidget

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackGestureScreen(
    checked: Boolean,
    predictive: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onPredictiveChange: (Boolean) -> Unit,
    canRestart: Boolean,
    onRestart: () -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRestartDialog by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(stringResource(R.string.config_back_gesture)) },
                navigationIcon = { ConfigBackButton(onBack) },
                actions = { RestartSystemUiAction(canRestart, R.string.restart_scopes) { showRestartDialog = true } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
            ConfigMasterSwitch(stringResource(R.string.config_native_style), checked, true, onCheckedChange,
                Modifier.fillMaxWidth().padding(16.dp))
            ExpandableConfigSection(visible = checked, title = stringResource(R.string.config)) {
                item { shape ->
                    SwitchWidget(title = stringResource(R.string.config_native_predictive_back),
                        description = stringResource(R.string.config_native_predictive_back_desc),
                        shape = shape, enabled = checked, checked = predictive, onCheckedChange = onPredictiveChange)
                }
            }
        }
    }
    if (showRestartDialog) RestartSystemUiDialog(
        onDismiss = { showRestartDialog = false }, onRestart = onRestart, titleRes = R.string.restart_scopes,
        text = {
            Text("${stringResource(R.string.config_system_ui)}(com.android.systemui)\n" +
                "${stringResource(R.string.config_launcher)}(com.android.launcher)",
                textAlign = TextAlign.Center)
        },
    )
}
