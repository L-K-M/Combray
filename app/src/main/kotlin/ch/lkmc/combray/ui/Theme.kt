package ch.lkmc.combray.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import ch.lkmc.combray.core.browser.Appearance

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

@Composable
fun CombrayTheme(appearance: Appearance = Appearance.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    // System bar icons follow the in-app choice, not the system's: a forced Dark on a
    // light-mode phone must not leave dark icons on the dark background.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !dark
            bars.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
