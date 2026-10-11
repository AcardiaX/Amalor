@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package me.acardia.amalor.ui.home

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.BuildConfig
import me.acardia.amalor.ui.component.BaseWidget
import me.acardia.amalor.ui.component.SegmentedColumn

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = PaddingValues(0.dp),
    isActive: Boolean = false,
    isRootAuthorized: Boolean = false,
    isModuleInstalled: Boolean = false,
    moduleVersion: String? = null,
    activePrivilegeSource: String? = null,
    deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}",
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.home), modifier = Modifier.padding(start = 12.dp)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp) + paddingValues + outerPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            overscrollEffect = null,
        ) {
            item {
                ActivationStatusCard(isActive = isActive)
            }

            item {
                SegmentedColumn(
                    title = stringResource(R.string.device_info),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 8.dp),
                ) {
                    item { shape ->
                        BaseWidget(
                            title = stringResource(R.string.device),
                            description = deviceName,
                            shape = shape,
                        )
                    }
                    item { shape ->
                        BaseWidget(
                            title = stringResource(R.string.android_version),
                            description = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                            shape = shape,
                        )
                    }
                    item { shape ->
                        BaseWidget(
                            title = stringResource(R.string.myfc_status),
                            description = if (!isRootAuthorized) {
                                stringResource(R.string.myfc_root_not_authorized)
                            } else if (isModuleInstalled) {
                                "${moduleVersion ?: stringResource(R.string.privilege_unknown)} (${activePrivilegeSource ?: stringResource(R.string.privilege_unknown)})"
                            } else "${stringResource(R.string.not_installed)} (${activePrivilegeSource ?: stringResource(R.string.privilege_unknown)})",
                            shape = shape,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivationStatusCard(
    isActive: Boolean,
) {
    val containerColor: Color
    val contentColor: Color
    val titleRes: Int

    if (!isActive) {
        containerColor = MaterialTheme.colorScheme.errorContainer
        contentColor = MaterialTheme.colorScheme.onErrorContainer
        titleRes = R.string.module_inactive
    } else {
        containerColor = MaterialTheme.colorScheme.primaryContainer
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        titleRes = R.string.module_active
    }

    ElevatedCard(
        onClick = {},
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            draggedElevation = 0.dp,
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                    Icon(
                        painter = painterResource(
                            if (isActive) R.drawable.ic_activated else R.drawable.ic_inactive,
                        ),
                        contentDescription = stringResource(titleRes),
                        tint = contentColor,
                        modifier = Modifier.size(24.dp),
                    )

                Column(modifier = Modifier.padding(start = 20.dp)) {
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = contentColor,
                    )
                    Text(
                        text = if (isActive) stringResource(R.string.amalor_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) else stringResource(R.string.module_inactive_summary),
                        style = MaterialTheme.typography.bodySmallEmphasized,
                        color = contentColor.copy(alpha = if (isActive) 0.7f else 1f),
                    )
                }
            }
        }
    }
}

@Preview(name = "HomeScreen - No Root")
@Composable
private fun PreviewNoRoot() {
    MaterialTheme {
        HomeScreen(isActive = false, isModuleInstalled = false)
    }
}

@Preview(name = "HomeScreen - Authorized Not Installed")
@Composable
private fun PreviewAuthorizedNotInstalled() {
    MaterialTheme {
        HomeScreen(isActive = true, isModuleInstalled = false, activePrivilegeSource = "KernelSU")
    }
}

@Preview(name = "HomeScreen - Installed")
@Composable
private fun PreviewInstalled() {
    MaterialTheme {
        HomeScreen(isActive = true, isModuleInstalled = true, moduleVersion = "v1.0.0", activePrivilegeSource = "KernelSU")
    }
}
