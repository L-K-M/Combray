package ch.lkmc.combray.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Pine green and ivory from the launcher artwork (media-sources/icon.png); supporting
// tones from the design fragment (docs/design). Every role is set so Material defaults
// (purple-tinged greys) cannot show through on menus, chips, snackbars or sheets.
private val Pine = Color(0xFF0E452E)
private val PineMid = Color(0xFF215E4A)
private val PineLight = Color(0xFF8CC7A8)
private val PinePale = Color(0xFFB5DDC9)
private val Ivory = Color(0xFFFBF1E1)
private val Cream = Color(0xFFFFFBF4)

private val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Ivory,
    primaryContainer = PinePale,
    onPrimaryContainer = Color(0xFF142A24),
    inversePrimary = PineLight,
    secondary = PineMid,
    onSecondary = Cream,
    secondaryContainer = Color(0xFFD7E8DD),
    onSecondaryContainer = Color(0xFF163C2D),
    tertiary = Color(0xFF4A6B5C),
    onTertiary = Cream,
    tertiaryContainer = Color(0xFFDCE9E1),
    onTertiaryContainer = Color(0xFF163C2D),
    background = Cream,
    onBackground = Color(0xFF1A211D),
    surface = Cream,
    onSurface = Color(0xFF1A211D),
    surfaceVariant = Color(0xFFE4EAE3),
    onSurfaceVariant = Color(0xFF5A6660),
    surfaceTint = Pine,
    inverseSurface = Color(0xFF101512),
    inverseOnSurface = Ivory,
    outline = Color(0xFF9AA79D),
    outlineVariant = Color(0xFFCBD4CC),
    scrim = Color(0xFF000000),
    surfaceBright = Cream,
    surfaceDim = Color(0xFFE4DECF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2E8),
    surfaceContainer = Color(0xFFF1EBDD),
    surfaceContainerHigh = Color(0xFFEBE5D7),
    surfaceContainerHighest = Color(0xFFE6DFD0),
)

private val DarkColors = darkColorScheme(
    primary = PineLight,
    onPrimary = Color(0xFF00210F),
    primaryContainer = Color(0xFF163C2D),
    onPrimaryContainer = PinePale,
    inversePrimary = Pine,
    secondary = PineLight,
    onSecondary = Color(0xFF0E2A1D),
    secondaryContainer = Color(0xFF2A4235),
    onSecondaryContainer = Color(0xFFD7E8DD),
    tertiary = Color(0xFF9FBFAD),
    onTertiary = Color(0xFF10251B),
    tertiaryContainer = Color(0xFF33473B),
    onTertiaryContainer = Color(0xFFD7E8DD),
    background = Color(0xFF101512),
    onBackground = Color(0xFFE2E8E3),
    surface = Color(0xFF101512),
    onSurface = Color(0xFFE2E8E3),
    surfaceVariant = Color(0xFF2A342D),
    onSurfaceVariant = Color(0xFFAAB5AC),
    surfaceTint = PineLight,
    inverseSurface = Color(0xFFE2E8E3),
    inverseOnSurface = Color(0xFF101512),
    outline = Color(0xFF6B7A70),
    outlineVariant = Color(0xFF3E4844),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF303A33),
    surfaceDim = Color(0xFF101512),
    surfaceContainerLowest = Color(0xFF0B0F0D),
    surfaceContainerLow = Color(0xFF141A16),
    surfaceContainer = Color(0xFF1A211D),
    surfaceContainerHigh = Color(0xFF202723),
    surfaceContainerHighest = Color(0xFF273029),
)

@Composable
fun CombrayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
