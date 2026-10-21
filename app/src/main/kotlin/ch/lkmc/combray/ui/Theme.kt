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
private val WarmWhite = Color(0xFFFFFBF4)
private val Charcoal = Color(0xFF101512)

// Every Material3 role is set explicitly: an unset role falls back to the baseline
// Material seed palette (purple), which shows up as violet checkboxes, radios and
// sheet surfaces in a pine-green app. ThemeContrastTest pins the text pairs below.

internal val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Ivory,
    primaryContainer = Color(0xFFD3E9DB),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4A6154),
    onSecondary = WarmWhite,
    secondaryContainer = Color(0xFFD7E8DD),
    onSecondaryContainer = Color(0xFF081F15),
    tertiary = Color(0xFF5C4E33),
    onTertiary = WarmWhite,
    tertiaryContainer = Color(0xFFE8DDBF),
    onTertiaryContainer = Color(0xFF1A1604),
    background = WarmWhite,
    onBackground = Color(0xFF191D19),
    surface = WarmWhite,
    onSurface = Color(0xFF191D19),
    surfaceVariant = Color(0xFFDFE4DA),
    onSurfaceVariant = Color(0xFF414941),
    surfaceTint = Pine,
    inverseSurface = Color(0xFF2E322E),
    inverseOnSurface = Color(0xFFF2F3EC),
    inversePrimary = Color(0xFF9DD6B7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF727970),
    outlineVariant = Color(0xFFC2C8BE),
    scrim = Color(0xFF000000),
    // Warm surface ladder: low steps lift toward white, high steps deepen the ivory.
    surfaceContainerLowest = Color(0xFFFFFEFB),
    surfaceContainerLow = Color(0xFFFFFCF7),
    surfaceContainer = WarmWhite,
    surfaceContainerHigh = Color(0xFFF4EEE2),
    surfaceContainerHighest = Color(0xFFEEE8DC),
)

internal val DarkColors = darkColorScheme(
    primary = PineLight,
    onPrimary = Color(0xFF00210F),
    primaryContainer = Color(0xFF164C34),
    onPrimaryContainer = Color(0xFFA8E8C7),
    secondary = Color(0xFFB4CCBD),
    onSecondary = Color(0xFF1B332A),
    secondaryContainer = Color(0xFF2A4235),
    onSecondaryContainer = Color(0xFFD7E8DD),
    tertiary = Color(0xFFE3C88F),
    onTertiary = Color(0xFF322B10),
    tertiaryContainer = Color(0xFF4A3F22),
    onTertiaryContainer = Color(0xFFF8E1B6),
    background = Charcoal,
    onBackground = Color(0xFFE0E4DB),
    surface = Charcoal,
    onSurface = Color(0xFFE0E4DB),
    surfaceVariant = Color(0xFF1A211D),
    onSurfaceVariant = Color(0xFFBFC7BE),
    surfaceTint = PineLight,
    inverseSurface = Color(0xFFE0E4DB),
    inverseOnSurface = Color(0xFF2E322E),
    inversePrimary = Pine,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A938A),
    outlineVariant = Color(0xFF414941),
    scrim = Color(0xFF000000),
    // Dark elevation is lighter than the page: sheets and the bottom bar rise.
    surfaceContainerLowest = Color(0xFF0A0E0C),
    surfaceContainerLow = Color(0xFF151B17),
    surfaceContainer = Color(0xFF171E1A),
    surfaceContainerHigh = Color(0xFF1D2521),
    surfaceContainerHighest = Color(0xFF242D28),
)

@Composable
fun CombrayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
