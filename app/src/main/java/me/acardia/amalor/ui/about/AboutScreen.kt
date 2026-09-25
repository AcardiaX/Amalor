package me.acardia.amalor.ui.about

import androidx.compose.foundation.Image
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import me.acardia.amalor.BuildConfig
import me.acardia.amalor.R
import me.acardia.amalor.ui.component.BaseWidget
import me.acardia.amalor.ui.component.SegmentedColumn
import com.mikepenz.aboutlibraries.ui.compose.m3.libraryColors
import me.acardia.amalor.ui.component.card.AnimatedFluidBackground

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                title = { Text(stringResource(R.string.about)) },
                navigationIcon = {
                    Row {
                        IconButton(
                            onClick = onBack,
                            shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(50)),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                        Spacer(modifier = Modifier.size(16.dp))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            horizontalAlignment = Alignment.CenterHorizontally,
            overscrollEffect = null,
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Box {
                            AnimatedFluidBackground(
                                baseColor = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.matchParentSize(),
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                AndroidView(
                                    factory = { ImageView(it).apply { setImageDrawable(context.applicationInfo.loadIcon(context.packageManager)) } },
                                    modifier = Modifier.size(56.dp),
                                )
                                Text(
                                    stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item {
                SegmentedColumn(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    title = stringResource(R.string.about),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    item { shape ->
                        BaseWidget(
                            title = stringResource(R.string.about_source_code),
                            description = stringResource(R.string.about_source_code_desc),
                            iconVector = Icons.Rounded.Code,
                            shape = shape,
                            trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                            onClick = { uriHandler.openUri("https://github.com/AcardiaX/Amalor") },
                        )
                    }
                    item { shape ->
                        BaseWidget(
                            title = stringResource(R.string.about_open_source_license),
                            description = stringResource(R.string.about_open_source_license_desc),
                            icon = painterResource(R.drawable.ic_copyright),
                            shape = shape,
                            trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                            onClick = onOpenLicenses,
                        )
                    }
                    item { shape ->
                        BaseWidget(
                            title = stringResource(R.string.about_telegram),
                            description = stringResource(R.string.about_telegram_desc),
                            iconVector = Icons.Rounded.Link,
                            shape = shape,
                            trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                            onClick = { uriHandler.openUri("https://t.me/+hnEPIdNSwotlNGJl") },
                        )
                    }
                }
            }
        }
    }
}
