package me.acardia.amalor

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.IconButton
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import io.github.libxposed.service.XposedService
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import me.acardia.amalor.ui.home.HomeScreen
import me.acardia.amalor.ui.config.ConfigScreen
import me.acardia.amalor.ui.config.SystemUiStyleScreen
import me.acardia.amalor.ui.config.StatusBarScreen
import me.acardia.amalor.ui.config.NetworkTypeIconScreen
import me.acardia.amalor.ui.config.SystemUiOtherScreen
import me.acardia.amalor.ui.navigation.AmalorRoute
import me.acardia.amalor.ui.settings.SettingsScreen
import me.acardia.amalor.ui.about.AboutScreen
import me.acardia.amalor.ui.about.OpenSourceLicenseScreen
import me.acardia.amalor.ui.animation.predictiveback.AmalorAospNavTransition
import me.acardia.amalor.ui.navigation.rememberMainPagerState
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import kotlinx.serialization.Serializable

private enum class Page { HOME, CONFIG, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AmalorApp(
    themeSettings: AppThemeSettings,
    xposedService: XposedService? = null,
    onThemeSettingsChange: (AppThemeSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { Page.entries.size })
    val mainPagerState = rememberMainPagerState(pagerState)
    LaunchedEffect(pagerState.currentPage) {
        mainPagerState.syncPage()
    }
    val selectedPage = mainPagerState.selectedPage
    var statusBarMode by rememberSaveable { mutableIntStateOf(0) }
    var networkBold by remember { mutableStateOf(false) }
    var networkFont by remember { mutableIntStateOf(NetworkTypeOptions.OFFICIAL) }
    var packageUpdateM3e by remember { mutableStateOf(false) }
    var batteryBodyFill by remember { mutableStateOf(BatteryOptions.DEFAULT_ENABLED) }
    var networkPreferences by remember { mutableStateOf<android.content.SharedPreferences?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val localNetworkPreferences = remember(context) {
        context.getSharedPreferences("network_type_local", android.content.Context.MODE_PRIVATE)
    }
    fun saveNetworkOption(update: android.content.SharedPreferences.Editor.() -> Unit): Boolean {
        val saved = runCatching {
            localNetworkPreferences.edit().apply(update).putBoolean("pending_sync", true).commit()
        }.getOrDefault(false)
        if (saved && networkPreferences != null) {
            val synced = runCatching {
                networkPreferences!!.edit()
                    .putInt(STATUS_BAR_MODE_KEY, localNetworkPreferences.getInt(STATUS_BAR_MODE_KEY, 0))
                    .putBoolean(NetworkTypeOptions.BOLD, localNetworkPreferences.getBoolean(NetworkTypeOptions.BOLD, false))
                    .putInt(NetworkTypeOptions.FONT, localNetworkPreferences.getInt(NetworkTypeOptions.FONT, 0))
                    .putBoolean(PackageUpdateOptions.ENABLED, localNetworkPreferences.getBoolean(PackageUpdateOptions.ENABLED, false))
                    .putBoolean(BatteryOptions.ENABLED, localNetworkPreferences.getBoolean(BatteryOptions.ENABLED, BatteryOptions.DEFAULT_ENABLED))
                    .commit()
            }.getOrDefault(false)
            if (synced) localNetworkPreferences.edit().putBoolean("pending_sync", false).apply()
        }
        if (!saved) android.widget.Toast.makeText(context, R.string.config_save_failed, android.widget.Toast.LENGTH_SHORT).show()
        return saved
    }
    LaunchedEffect(xposedService) {
        networkPreferences = null
        statusBarMode = localNetworkPreferences.getInt(STATUS_BAR_MODE_KEY, 0).coerceIn(0, 3)
        networkBold = localNetworkPreferences.getBoolean(NetworkTypeOptions.BOLD, false)
        networkFont = NetworkTypeOptions.supportedFont(localNetworkPreferences.getInt(NetworkTypeOptions.FONT, 0))
        packageUpdateM3e = localNetworkPreferences.getBoolean(PackageUpdateOptions.ENABLED, false)
        batteryBodyFill = localNetworkPreferences.getBoolean(BatteryOptions.ENABLED, BatteryOptions.DEFAULT_ENABLED)
        xposedService?.let { service ->
            val preferences = runCatching { service.getRemotePreferences(STATUS_BAR_PREFERENCES_GROUP) }
                .getOrNull() ?: return@let
            if (localNetworkPreferences.getBoolean("pending_sync", false)) {
                val synced = runCatching {
                    preferences.edit().putInt(STATUS_BAR_MODE_KEY, statusBarMode)
                        .putBoolean(NetworkTypeOptions.BOLD, networkBold)
                        .putInt(NetworkTypeOptions.FONT, networkFont)
                        .putBoolean(PackageUpdateOptions.ENABLED, packageUpdateM3e)
                        .putBoolean(BatteryOptions.ENABLED, batteryBodyFill).commit()
                }.getOrDefault(false)
                if (!synced) return@let
                localNetworkPreferences.edit().putBoolean("pending_sync", false).apply()
            }
            statusBarMode = preferences
                .getInt(STATUS_BAR_MODE_KEY, 0)
                .coerceIn(0, 3)
            networkBold = preferences.getBoolean(NetworkTypeOptions.BOLD, false)
            networkFont = NetworkTypeOptions.supportedFont(preferences.getInt(NetworkTypeOptions.FONT, NetworkTypeOptions.OFFICIAL))
            packageUpdateM3e = preferences.getBoolean(PackageUpdateOptions.ENABLED, false)
            batteryBodyFill = preferences.getBoolean(BatteryOptions.ENABLED, BatteryOptions.DEFAULT_ENABLED)
            networkPreferences = preferences
            localNetworkPreferences.edit().putInt(STATUS_BAR_MODE_KEY, statusBarMode)
                .putBoolean(NetworkTypeOptions.BOLD, networkBold)
                .putInt(NetworkTypeOptions.FONT, networkFont)
                .putBoolean(PackageUpdateOptions.ENABLED, packageUpdateM3e)
                .putBoolean(BatteryOptions.ENABLED, batteryBodyFill).apply()
        }
    }
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val windowWidthDp = with(density) { windowInfo.containerSize.width.toDp().value }
    val windowHeightDp = with(density) { windowInfo.containerSize.height.toDp().value }
    val windowType = when {
        windowWidthDp >= 840f -> 2
        windowWidthDp >= 600f -> 1
        else -> 0
    }
    val showNavigationRail = windowType == 2 || (windowType == 1 && windowWidthDp > windowHeightDp)
    val railState = rememberWideNavigationRailState()
    val railExpanded = railState.targetValue == WideNavigationRailValue.Expanded
    var rootState by remember { mutableStateOf<RootState?>(null) }
    val backStack = rememberNavBackStack<AmalorRoute>(AmalorRoute.Main)
    val navigator = remember(backStack) { NavController(backStack) }

    LaunchedEffect(Unit) {
        rootState = withContext(Dispatchers.IO) { RootState.detect() }
    }

        NavDisplay(
            backStack = backStack,
            onBack = { navigator.pop() },
            transition = AmalorAospNavTransition,
            effects = NavDisplayEffects(
                enableCornerClip = true,
                cornerClipRadius = 32.dp,
                cornerClipMode = NavCornerClipMode.All,
                dimAmount = 0.5f,
                backdropColor = MaterialTheme.colorScheme.surfaceContainer,
                blockInputDuringTransition = false,
            ),
        ) {
            entry<AmalorRoute.Main> {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wideLayout = showNavigationRail
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        contentWindowInsets = WindowInsets(),
                        bottomBar = {
                            if (!wideLayout) {
                                ShortNavigationBar(
                                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    arrangement = ShortNavigationBarArrangement.EqualWeight,
                                ) {
                                    MainNavigationItems(selectedPage, mainPagerState, compact = true)
                                }
                            }
                        },
                    ) { mainPadding ->
                        Row(Modifier.fillMaxSize()) {
                            if (wideLayout) {
                                WideNavigationRail(
                                    modifier = Modifier.fillMaxHeight(),
                                    state = railState,
                                    colors = WideNavigationRailDefaults.colors().copy(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
                                    header = {
                                        IconButton(
                                            modifier = Modifier.padding(start = 24.dp),
                                            onClick = {
                                                scope.launch {
                                                    if (railExpanded) railState.collapse() else railState.expand()
                                                }
                                            },
                                        ) {
                                            Icon(
                                                if (railExpanded) Icons.AutoMirrored.Filled.MenuOpen else Icons.Filled.Menu,
                                                null,
                                            )
                                        }
                                    },
                                ) {
                                    MainNavigationItems(selectedPage, mainPagerState, compact = false, railExpanded = railExpanded)
                                }
                            }
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.weight(1f).fillMaxSize().consumeWindowInsets(mainPadding),
                                overscrollEffect = null,
                            ) { targetPage ->
                    when (Page.entries[targetPage]) {
                         Page.HOME -> HomeScreen(outerPadding = mainPadding, isActive = xposedService != null, isRootAuthorized = rootState?.authorized == true, isModuleInstalled = rootState?.moduleInstalled == true, moduleVersion = rootState?.moduleVersion, activePrivilegeSource = rootState?.source, deviceName = rootState?.deviceName ?: "")
                        Page.CONFIG -> ConfigScreen(
                            outerPadding = mainPadding,
                            onOpenSystemUi = { navigator.push(AmalorRoute.SystemUiStyle) },
                        )
                        Page.SETTINGS -> SettingsScreen(outerPadding = mainPadding, settings = themeSettings, onSettingsChange = onThemeSettingsChange, onOpenAbout = { navigator.push(AmalorRoute.About) })
                    }
                            }
                        }
                    }
                }
            }
            entry<AmalorRoute.SystemUiStyle>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                SystemUiStyleScreen(
                    style = rootState?.config?.style ?: ModuleConfig.default.style,
                    enabled = rootState?.authorized == true && rootState?.moduleInstalled == true,
                    onBack = { navigator.pop() },
                    onStyleChange = { style ->
                                val state = rootState ?: return@SystemUiStyleScreen
                                val requested = ModuleConfig(style)
                                if (state.config == requested) return@SystemUiStyleScreen
                                scope.launch(Dispatchers.IO) {
                                    val applied = writeModuleStyle(style)
                                    withContext(Dispatchers.Main) {
                                        rootState = rootState?.copy(
                                            config = if (applied) requested else rootState?.config ?: ModuleConfig.default,
                                        )
                                    }
                                }
                            },
                    onOpenStatusBar = { navigator.push(AmalorRoute.StatusBar) },
                    onOpenOther = { navigator.push(AmalorRoute.SystemUiOther) },
                )
            }
            entry<AmalorRoute.SystemUiOther>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                SystemUiOtherScreen(
                    packageUpdateM3e = packageUpdateM3e,
                    canRestartSystemUi = rootState?.authorized == true && rootState?.moduleInstalled == true,
                    onRestartSystemUi = ::restartSystemUi,
                    onPackageUpdateM3eChange = { value ->
                        if (saveNetworkOption { putBoolean(PackageUpdateOptions.ENABLED, value) }) packageUpdateM3e = value
                    },
                    onBack = { navigator.pop() },
                )
            }
            entry<AmalorRoute.StatusBar>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                StatusBarScreen(
                    mode = statusBarMode,
                    batteryBodyFill = batteryBodyFill,
                    onBatteryBodyFillChange = { value ->
                        if (saveNetworkOption { putBoolean(BatteryOptions.ENABLED, value) }) batteryBodyFill = value
                    },
                    onBack = { navigator.pop() },
                    canRestartSystemUi = rootState?.authorized == true && rootState?.moduleInstalled == true,
                    onRestartSystemUi = ::restartSystemUi,
                    onOpenNetworkType = { navigator.push(AmalorRoute.NetworkTypeIcon) },
                    onModeChange = { mode ->
                        if (saveNetworkOption { putInt(STATUS_BAR_MODE_KEY, mode) }) statusBarMode = mode
                    },
                )
            }
            entry<AmalorRoute.NetworkTypeIcon>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                NetworkTypeIconScreen(
                    enabled = true,
                    canRestartSystemUi = rootState?.authorized == true && rootState?.moduleInstalled == true,
                    onRestartSystemUi = ::restartSystemUi,
                    checked = statusBarMode != 0,
                    bold = networkBold,
                    font = networkFont,
                    onBoldChange = { value ->
                        if (saveNetworkOption { putBoolean(NetworkTypeOptions.BOLD, value) }) networkBold = value
                    },
                    onFontChange = { value ->
                        if (saveNetworkOption { putInt(NetworkTypeOptions.FONT, value) }) networkFont = value
                    },
                    onBack = { navigator.pop() },
                    onCheckedChange = { checked ->
                        val mode = if (checked) 1 else 0
                        if (saveNetworkOption { putInt(STATUS_BAR_MODE_KEY, mode) }) statusBarMode = mode
                    },
                )
            }
            entry<AmalorRoute.About>(swipeDismiss = NavSwipeDirection.LeftToRight) { AboutScreen(onBack = { navigator.pop() }, onOpenLicenses = { navigator.push(AmalorRoute.Licenses) }) }
            entry<AmalorRoute.Licenses>(swipeDismiss = NavSwipeDirection.LeftToRight) { OpenSourceLicenseScreen(onBack = { navigator.pop() }) }
        }
}

private fun writeModuleStyle(style: Int): Boolean = runCatching {
    val command = "sed -i 's/^systemui_style=.*/systemui_style=\"$style\"/' /data/adb/modules/material_you_for_coloros/config.conf"
    Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
}.getOrDefault(false)

private fun restartSystemUi(): Boolean = runCatching {
    Runtime.getRuntime().exec(arrayOf("su", "-c", "killall com.android.systemui")).waitFor() == 0
}.getOrDefault(false)

private const val STATUS_BAR_PREFERENCES_GROUP = NetworkTypeOptions.GROUP
private const val STATUS_BAR_MODE_KEY = NetworkTypeOptions.MODE

@Composable
private fun MainNavigationItems(
    selectedPage: Int,
    pagerState: me.acardia.amalor.ui.navigation.MainPagerState,
    compact: Boolean,
    railExpanded: Boolean = true,
) {
    val items = listOf(
        Triple(R.string.home, R.drawable.ic_home_filled, R.drawable.ic_home),
        Triple(R.string.config, R.drawable.ic_config_filled, R.drawable.ic_config),
        Triple(R.string.settings, R.drawable.ic_settings_filled, R.drawable.ic_settings),
    )
    items.forEachIndexed { index, (label, selectedIcon, unselectedIcon) ->
        val selected = selectedPage == index
        if (compact) {
            ShortNavigationBarItem(
                selected = selected,
                onClick = { pagerState.animateToPage(index) },
                iconPosition = NavigationItemIconPosition.Top,
                icon = { Icon(painterResource(if (selected) selectedIcon else unselectedIcon), null) },
                label = { Text(stringResource(label)) },
            )
        } else {
            WideNavigationRailItem(
                railExpanded = railExpanded,
                selected = selected,
                onClick = { pagerState.animateToPage(index) },
                icon = { Icon(painterResource(if (selected) selectedIcon else unselectedIcon), null) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

data class ModuleConfig(val style: Int) {
    companion object { val default = ModuleConfig(0) }
}

private data class RootState(
    val authorized: Boolean = false,
    val moduleInstalled: Boolean = false,
    val moduleVersion: String? = null,
    val privilege: Privilege = Privilege.NONE,
    val source: String? = null,
    val deviceName: String = "",
    val config: ModuleConfig = ModuleConfig.default,
) {
    companion object {
        fun detect(): RootState {
            val authorized = runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).inputStream.bufferedReader().use { reader ->
                    reader.readText().contains("uid=0")
                }
            }.getOrDefault(false)
            val deviceName = detectDeviceName()
            if (!authorized) return RootState(deviceName = deviceName, privilege = Privilege.NONE)

            val source = runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "su -v"))
                    .inputStream.bufferedReader().use { reader ->
                        reader.readLines()
                            .firstOrNull { it.isNotBlank() }
                            ?.trim()
                            ?.let(::normalizeRootSource)
                    }
            }.getOrNull()

            val modulePath = "/data/adb/modules/material_you_for_coloros"
            val moduleInstalled = runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "test -d $modulePath")).waitFor() == 0
            }.getOrDefault(false)
            val version = runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /data/adb/modules/material_you_for_coloros/module.prop"))
                    .inputStream.bufferedReader().use { reader ->
                        reader.readLines()
                            .firstOrNull { it.startsWith("version=") }
                            ?.substringAfter('=')
                            ?.takeIf { it.isNotBlank() }
                    }
            }.getOrNull()
            return RootState(authorized, moduleInstalled, version, Privilege.ROOT, source, deviceName, readModuleConfig())
        }
    }
}

private fun readModuleConfig(): ModuleConfig = runCatching {
    Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /data/adb/modules/material_you_for_coloros/config.conf"))
        .inputStream.bufferedReader().use { reader ->
            val values = reader.readLines().mapNotNull { it.substringAfter('=', "").trim().trim('"').toIntOrNull() }
            values.firstOrNull()?.let(::ModuleConfig) ?: ModuleConfig.default
        }
}.getOrDefault(ModuleConfig.default)

private fun detectDeviceName(): String {
    val marketName = runCatching {
        listOf(
            "ro.product.marketname",
            "ro.vendor.oplus.market.name",
            "ro.vivo.market.name",
            "ro.config.marketing_name",
        ).firstNotNullOfOrNull { key ->
            Runtime.getRuntime().exec(arrayOf("getprop", key)).inputStream.bufferedReader().use { reader ->
                reader.readText().trim().takeIf { it.isNotBlank() && it != "unknown" }
            }
        }
    }.getOrNull()
    return marketName ?: "${Build.MANUFACTURER} ${Build.MODEL}"
}

private fun normalizeRootSource(raw: String): String? = when {
    raw.contains("kernelsu", ignoreCase = true) -> "KernelSU"
    raw.contains("magisk", ignoreCase = true) -> "Magisk"
    raw.contains("apatch", ignoreCase = true) -> "APatch"
    else -> raw.substringAfterLast(':').trim().takeIf { it.isNotBlank() && it.any(Char::isLetter) }
}

private enum class Privilege { NONE, ROOT }

@Composable
private fun PlaceholderScreen(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(stringResource(R.string.placeholder_page), style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
    }
}
