package me.acardia.amalor

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import me.acardia.amalor.ui.home.HomeScreen
import me.acardia.amalor.ui.config.ConfigScreen
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
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

private enum class Page { HOME, CONFIG, SETTINGS }

private sealed interface AmalorRoute : NavKey {
    data object Main : AmalorRoute
    data object About : AmalorRoute
    data object Licenses : AmalorRoute
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AmalorApp(
    themeSettings: AppThemeSettings,
    onThemeSettingsChange: (AppThemeSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { Page.entries.size })
    val mainPagerState = rememberMainPagerState(pagerState)
    val selectedPage = mainPagerState.selectedPage
    var rootState by remember { mutableStateOf<RootState?>(null) }
    val backStack = remember { navBackStackOf(AmalorRoute.Main) }
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
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(),
                    bottomBar = {
                        ShortNavigationBar(
                            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            arrangement = ShortNavigationBarArrangement.EqualWeight,
                        ) {
                            ShortNavigationBarItem(selectedPage == Page.HOME.ordinal, { mainPagerState.animateToPage(Page.HOME.ordinal) }, iconPosition = NavigationItemIconPosition.Top, icon = { Icon(painterResource(if (selectedPage == Page.HOME.ordinal) R.drawable.ic_home_filled else R.drawable.ic_home), null) }, label = { Text(stringResource(R.string.home)) })
                            ShortNavigationBarItem(selectedPage == Page.CONFIG.ordinal, { mainPagerState.animateToPage(Page.CONFIG.ordinal) }, iconPosition = NavigationItemIconPosition.Top, icon = { Icon(painterResource(if (selectedPage == Page.CONFIG.ordinal) R.drawable.ic_config_filled else R.drawable.ic_config), null) }, label = { Text(stringResource(R.string.config)) })
                            ShortNavigationBarItem(selectedPage == Page.SETTINGS.ordinal, { mainPagerState.animateToPage(Page.SETTINGS.ordinal) }, iconPosition = NavigationItemIconPosition.Top, icon = { Icon(painterResource(if (selectedPage == Page.SETTINGS.ordinal) R.drawable.ic_settings_filled else R.drawable.ic_settings), null) }, label = { Text(stringResource(R.string.settings)) })
                        }
                    },
                ) { mainPadding ->
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().consumeWindowInsets(mainPadding),
                    overscrollEffect = null,
                ) { targetPage ->
                    when (Page.entries[targetPage]) {
                        Page.HOME -> HomeScreen(outerPadding = mainPadding, isLoading = rootState == null, isRootAuthorized = rootState?.authorized == true, isModuleInstalled = rootState?.moduleInstalled == true, moduleVersion = rootState?.moduleVersion, activePrivilegeSource = rootState?.source, deviceName = rootState?.deviceName ?: "")
                        Page.CONFIG -> ConfigScreen(
                            outerPadding = mainPadding,
                            isRootAuthorized = rootState?.authorized == true,
                            initialConfig = rootState?.config ?: ModuleConfig.default,
                            saving = rootState?.saving == true,
                            saveResult = rootState?.saveResult,
                            onSaveResultShown = { rootState = rootState?.copy(saveResult = null) },
                            onSave = { signal, style, paddingMode ->
                                val state = rootState ?: return@ConfigScreen
                                val requested = ModuleConfig(signal, style, paddingMode)
                                if (state.saving || state.config == requested) return@ConfigScreen
                                rootState = state.copy(saving = true, saveResult = null)
                                scope.launch(Dispatchers.IO) {
                                    val applied = applyModuleConfig(signal, style, paddingMode)
                                    withContext(Dispatchers.Main) {
                                        rootState = rootState?.copy(
                                            config = if (applied) requested else rootState?.config ?: ModuleConfig.default,
                                            saving = false,
                                            saveResult = applied,
                                        )
                                    }
                                }
                            },
                        )
                        Page.SETTINGS -> SettingsScreen(outerPadding = mainPadding, settings = themeSettings, onSettingsChange = onThemeSettingsChange, onOpenAbout = { navigator.push(AmalorRoute.About) })
                    }
                }
                }
            }
            entry<AmalorRoute.About>(swipeDismiss = NavSwipeDirection.LeftToRight) { AboutScreen(onBack = { navigator.pop() }, onOpenLicenses = { navigator.push(AmalorRoute.Licenses) }) }
            entry<AmalorRoute.Licenses>(swipeDismiss = NavSwipeDirection.LeftToRight) { OpenSourceLicenseScreen(onBack = { navigator.pop() }) }
        }
}

private fun applyModuleConfig(signal: Int, style: Int, padding: Int): Boolean = runCatching {
    val command = "sh /data/adb/modules/material_you_for_coloros/apply-config.sh $signal $style $padding"
    Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
}.getOrDefault(false)

data class ModuleConfig(val signal: Int, val style: Int, val padding: Int) {
    companion object { val default = ModuleConfig(0, 0, 0) }
}

private data class RootState(
    val authorized: Boolean = false,
    val moduleInstalled: Boolean = false,
    val moduleVersion: String? = null,
    val privilege: Privilege = Privilege.NONE,
    val source: String? = null,
    val deviceName: String = "",
    val config: ModuleConfig = ModuleConfig.default,
    val saving: Boolean = false,
    val saveResult: Boolean? = null,
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
            if (values.size >= 3) ModuleConfig(values[0], values[1], values[2]) else ModuleConfig.default
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
