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
// Tones shared across both schemes are named once so the schemes cannot drift apart.
private val Pine = Color(0xFF0E452E)
private val PineMid = Color(0xFF215E4A)
private val PineDeep = Color(0xFF163C2D)
private val PineLight = Color(0xFF8CC7A8)
private val PinePale = Color(0xFFB5DDC9)
private val PineMist = Color(0xFFD7E8DD)
private val Ivory = Color(0xFFFBF1E1)
private val Cream = Color(0xFFFFFBF4)
private val Charcoal = Color(0xFF101512)
private val Ink = Color(0xFF1A211D)
private val Mist = Color(0xFFE2E8E3)

private val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Ivory,
    primaryContainer = PinePale,
    onPrimaryContainer = Color(0xFF142A24),
    inversePrimary = PineLight,
    secondary = PineMid,
    onSecondary = Cream,
    secondaryContainer = PineMist,
    onSecondaryContainer = PineDeep,
    tertiary = Color(0xFF4A6B5C),
    onTertiary = Cream,
    tertiaryContainer = Color(0xFFDCE9E1),
    onTertiaryContainer = PineDeep,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE4EAE3),
    onSurfaceVariant = Color(0xFF5A6660),
    surfaceTint = Pine,
    inverseSurface = Charcoal,
    inverseOnSurface = Ivory,
    // Field borders need >= 3:1 against the cream surfaces (WCAG 1.4.11).
    outline = Color(0xFF7C8A7F),
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
    primaryContainer = PineDeep,
    onPrimaryContainer = PinePale,
    inversePrimary = Pine,
    secondary = PineLight,
    onSecondary = Color(0xFF0E2A1D),
    secondaryContainer = Color(0xFF2A4235),
    onSecondaryContainer = PineMist,
    tertiary = Color(0xFF9FBFAD),
    onTertiary = Color(0xFF10251B),
    tertiaryContainer = Color(0xFF33473B),
    onTertiaryContainer = PineMist,
    background = Charcoal,
    onBackground = Mist,
    surface = Charcoal,
    onSurface = Mist,
    surfaceVariant = Color(0xFF2A342D),
    onSurfaceVariant = Color(0xFFAAB5AC),
    surfaceTint = PineLight,
    inverseSurface = Mist,
    inverseOnSurface = Charcoal,
    outline = Color(0xFF6B7A70),
    outlineVariant = Color(0xFF3E4844),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF303A33),
    surfaceDim = Charcoal,
    surfaceContainerLowest = Color(0xFF0B0F0D),
    surfaceContainerLow = Color(0xFF141A16),
    surfaceContainer = Ink,
    surfaceContainerHigh = Color(0xFF202723),
    surfaceContainerHighest = Color(0xFF273029),
)

@Composable
fun CombrayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
