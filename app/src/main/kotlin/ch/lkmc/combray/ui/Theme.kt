package ch.lkmc.combray.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ch.lkmc.combray.core.browser.BrowserSettings
import ch.lkmc.combray.core.browser.ThemeMode

// Pine green and ivory from the launcher artwork (media-sources/icon.png).
private val Pine = Color(0xFF0E452E)
private val PineLight = Color(0xFF8CC7A8)
private val Ivory = Color(0xFFFBF1E1)

private val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Ivory,
    secondaryContainer = Color(0xFFD7E8DD),
    background = Color(0xFFFFFBF4),
    surface = Color(0xFFFFFBF4),
)

private val DarkColors = darkColorScheme(
    primary = PineLight,
    onPrimary = Color(0xFF00210F),
    secondaryContainer = Color(0xFF2A4235),
    background = Color(0xFF101512),
    surface = Color(0xFF101512),
)

/** Pitch-black surfaces for OLED screens, keeping the pine accents legible on pure black. */
private val DarkOledColors = darkColorScheme(
    primary = PineLight,
    onPrimary = Color(0xFF00210F),
    secondaryContainer = Color(0xFF2A4235),
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color.Black,
    outline = Color(0xFF3A4A3C),
)

/**
 * The app's theme. The mode comes from [settings]; Material You dynamic color is used on
 * Android 12+ for the system-following modes, and the pine palette is the fallback everywhere.
 */
@Composable
fun CombrayTheme(settings: BrowserSettings, content: @Composable () -> Unit) {
    val mode by settings.themeMode.collectAsState()
    val context = LocalContext.current
    val dark = when (mode) {
        ThemeMode.DARK, ThemeMode.DARK_OLED -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = when {
        mode == ThemeMode.DARK_OLED -> DarkOledColors
        mode == ThemeMode.SYSTEM && dark && supportsDynamic(context) -> dynamicDarkColorScheme(context)
        mode == ThemeMode.SYSTEM && !dark && supportsDynamic(context) -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private fun supportsDynamic(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
