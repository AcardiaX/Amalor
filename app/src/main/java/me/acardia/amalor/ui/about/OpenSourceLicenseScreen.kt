package me.acardia.amalor.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.LibraryDefaults
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.m3.libraryColors
import com.mikepenz.aboutlibraries.ui.compose.m3.style.m3VariantColors
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryDetailMode
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryRow
import me.acardia.amalor.R
import me.acardia.amalor.ui.component.BaseWidget

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenSourceLicenseScreen(onBack: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    var selectedLibrary by remember { mutableStateOf<Library?>(null) }
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(stringResource(R.string.about_open_source_license)) },
                navigationIcon = {
                    Row {
                        IconButton(
                            onClick = onBack,
                            shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(50)),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                        }
                        Spacer(Modifier.size(16.dp))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { paddingValues ->
        libraries?.let { libs ->
            LibrariesContainer(
                libraries = libs,
                modifier = Modifier.fillMaxSize(),
                contentPadding = paddingValues + PaddingValues(horizontal = 16.dp),
                colors = LibraryDefaults.libraryColors(
                    libraryBackgroundColor = MaterialTheme.colorScheme.surfaceContainer,
                    libraryContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                variantColors = LibraryDefaults.m3VariantColors(
                    rowBackground = MaterialTheme.colorScheme.surfaceBright,
                    rowExpandedBackground = MaterialTheme.colorScheme.surfaceBright,
                    rowOnBackground = MaterialTheme.colorScheme.onSurface,
                    rowSubtleContent = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                detailMode = LibraryDetailMode.None,
                libraryRow = { _, library, expanded, toggle, style ->
                    LibraryRow(
                        library = library,
                        expanded = expanded,
                        onToggle = toggle,
                        style = style,
                        modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)),
                    )
                },
                onLibraryClick = { library -> selectedLibrary = library; true },
            )
        }
    }

    selectedLibrary?.let { library ->
        AlertDialog(
            onDismissRequest = { selectedLibrary = null },
            modifier = Modifier.padding(24.dp),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text(library.name) },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(library.licenses.toList()) { license ->
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    license.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                        license.url?.let(uriHandler::openUri)
                                    },
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    license.licenseContent ?: stringResource(R.string.no_license_text),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { selectedLibrary = null }) { Text(stringResource(R.string.close)) } },
            dismissButton = {
                library.website?.let { url ->
                    OutlinedButton(onClick = { uriHandler.openUri(url) }) { Text(stringResource(R.string.visit_home_page)) }
                }
            },
        )
    }
}
