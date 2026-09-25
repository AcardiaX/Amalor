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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.acardia.amalor.R
import me.acardia.amalor.ui.component.BaseWidget
import me.acardia.amalor.ui.component.SegmentedColumn

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = PaddingValues(0.dp),
    isLoading: Boolean = false,
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
            if (!isLoading) {
                item {
                    ActivationStatusCard(
                        isRootAuthorized = isRootAuthorized,
                        isModuleInstalled = isModuleInstalled,
                        moduleVersion = moduleVersion,
                    )
                }
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
                            title = stringResource(R.string.active_privilege),
                            description = when {
                                !isRootAuthorized -> stringResource(R.string.privilege_none)
                                activePrivilegeSource.isNullOrBlank() -> stringResource(R.string.privilege_unknown)
                                else -> activePrivilegeSource
                            },
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
    isRootAuthorized: Boolean,
    isModuleInstalled: Boolean,
    moduleVersion: String?,
) {
    val containerColor: Color
    val contentColor: Color
    val icon: ImageVector?
    val titleRes: Int
    val summaryRes: Int

    if (!isRootAuthorized) {
        containerColor = MaterialTheme.colorScheme.errorContainer
        contentColor = MaterialTheme.colorScheme.onErrorContainer
        icon = Icons.Rounded.Tag
        titleRes = R.string.not_authorized
        summaryRes = R.string.not_authorized_summary
    } else if (isModuleInstalled) {
        containerColor = MaterialTheme.colorScheme.primaryContainer
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        icon = null
        titleRes = R.string.installed
        summaryRes = R.string.installed_summary
    } else {
        containerColor = MaterialTheme.colorScheme.tertiaryContainer
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        icon = null
        titleRes = R.string.not_installed
        summaryRes = R.string.not_installed_summary
    }

    ElevatedCard(
        onClick = {},
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
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = stringResource(titleRes),
                        tint = contentColor,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(
                            if (isModuleInstalled) R.drawable.ic_installed else R.drawable.ic_not_installed,
                        ),
                        contentDescription = stringResource(titleRes),
                        tint = contentColor,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Column(modifier = Modifier.padding(start = 20.dp)) {
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = contentColor,
                    )
                    if (!isModuleInstalled) {
                        Text(
                            text = stringResource(summaryRes),
                            style = MaterialTheme.typography.bodySmallEmphasized,
                            color = contentColor,
                        )
                    }
                    if (isModuleInstalled && moduleVersion != null) {
                        Text(
                            text = stringResource(R.string.module_version, moduleVersion),
                            style = MaterialTheme.typography.bodySmallEmphasized,
                            color = contentColor.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "HomeScreen - No Root")
@Composable
private fun PreviewNoRoot() {
    MaterialTheme {
        HomeScreen(isRootAuthorized = false, isModuleInstalled = false)
    }
}

@Preview(name = "HomeScreen - Authorized Not Installed")
@Composable
private fun PreviewAuthorizedNotInstalled() {
    MaterialTheme {
        HomeScreen(isRootAuthorized = true, isModuleInstalled = false, activePrivilegeSource = "KernelSU")
    }
}

@Preview(name = "HomeScreen - Installed")
@Composable
private fun PreviewInstalled() {
    MaterialTheme {
        HomeScreen(isRootAuthorized = true, isModuleInstalled = true, moduleVersion = "v1.0.0", activePrivilegeSource = "KernelSU")
    }
}
