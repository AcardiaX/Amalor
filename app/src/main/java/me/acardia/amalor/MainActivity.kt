package me.acardia.amalor

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.core.view.WindowCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import me.acardia.amalor.ui.theme.PaletteStyle
import me.acardia.amalor.ui.theme.ThemeColorSpec
import me.acardia.amalor.ui.theme.dynamicColorScheme
import kotlinx.coroutines.launch

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

data class AppThemeSettings(
    val mode: AppThemeMode = AppThemeMode.SYSTEM,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val colorSpec: ThemeColorSpec = ThemeColorSpec.SPEC_2025,
)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        val splashStartedAt = SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition {
            SystemClock.uptimeMillis() - splashStartedAt < 500L
        }
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val themeSettings by context.amalorThemeSettings.collectAsState(initial = AppThemeSettings())
            val scope = rememberCoroutineScope()
            AmalorTheme(themeSettings) {
                AmalorApp(themeSettings) { newSettings ->
                    scope.launch { context.saveAmalorThemeSettings(newSettings) }
                }
            }
        }
    }
}

@Composable
private fun AmalorTheme(
    settings: AppThemeSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    // Use system accent color (Android 12+ only)
    val darkTheme = when (settings.mode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val keyColor = colorResource(id = android.R.color.system_accent1_500)

    // Generate M3 Expressive color scheme (no animation for instant color changes)
    val cacheKey = "${keyColor.value}_${darkTheme}_${settings.paletteStyle.name}_${settings.colorSpec.name}"
    val colorScheme by produceState<ColorScheme?>(
        initialValue = colorSchemeCache[cacheKey],
        key1 = cacheKey,
    ) {
        colorSchemeCache[cacheKey]?.let { value = it; return@produceState }
        withContext(Dispatchers.Default) {
            val generated = dynamicColorScheme(
                keyColor = keyColor,
                isDark = darkTheme,
                style = settings.paletteStyle,
                colorSpec = settings.colorSpec,
            )
            colorSchemeCache[cacheKey] = generated
            value = generated
        }
    }
    val appliedColorScheme = colorScheme ?: if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    // Set status bar appearance
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as AppCompatActivity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    // Apply Material 3 Expressive theme with expressive motion
    MaterialExpressiveTheme(
            colorScheme = appliedColorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

private val colorSchemeCache = ConcurrentHashMap<String, ColorScheme>()
