package ch.lkmc.combray.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
fun CombrayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
