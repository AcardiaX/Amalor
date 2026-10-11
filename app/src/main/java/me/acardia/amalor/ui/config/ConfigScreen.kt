package me.acardia.amalor.ui.config

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.rememberTopAppBarState
import me.acardia.amalor.R
import me.acardia.amalor.ui.component.BaseWidget
import me.acardia.amalor.ui.component.SegmentedColumn

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConfigScreen(
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = PaddingValues(0.dp),
    onOpenSystemUi: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.config), modifier = Modifier.padding(start = 12.dp)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding + outerPadding, overscrollEffect = null) {
            item {
                SegmentedColumn(title = stringResource(R.string.config_style)) {
                    item { shape -> BaseWidget(title = stringResource(R.string.config_system_ui), shape = shape, description = stringResource(R.string.config_system_ui_desc), icon = painterResource(R.drawable.ic_systemui), onClick = onOpenSystemUi) }
                }
            }
        }
    }
}
