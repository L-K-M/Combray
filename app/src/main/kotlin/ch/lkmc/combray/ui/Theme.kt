package ch.lkmc.combray.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import ch.lkmc.combray.core.browser.ThemeMode

// Pine green, ivory and the sage fold from the launcher artwork (media-sources/icon.png).
private val Pine = Color(0xFF0E452E)
private val PineLight = Color(0xFF8CC7A8)
private val Ivory = Color(0xFFFBF1E1)
private val Sage = Color(0xFF96AF93)

// Every role is set, so no Material baseline purple shows in sheets, dialogs, menus or snackbars.
// Tones are CIELAB L* (which Material's HCT tone follows) on five hues: pine (160) for primary,
// sage (141) for secondary, a "madeleine" ochre (72) for tertiary, kept for memory and Hister
// accents, warm paper (85) for light surfaces and pine charcoal (158) for dark ones.
// ThemeContrastTest checks every text and outline pairing against WCAG.

// Accent tones shared by both schemes: one scheme's containers are the other's on-colors, and
// they fill the fixed roles, which Material keeps the same in light and dark.
private val PineTint = Color(0xFFC1ECD4)
private val PineInk = Color(0xFF002211)
private val SageTint = Color(0xFFD4E8D1)
private val SageDim = Color(0xFFB7CCB4)
private val SageDeep = Color(0xFF3C4A3B)
private val SageInk = Color(0xFF121F10)
private val MadeleineTint = Color(0xFFFDDDBA)
private val MadeleineLight = Color(0xFFEABF92)
private val MadeleineDeep = Color(0xFF67441B)
private val MadeleineInk = Color(0xFF321B00)

private val Paper = Color(0xFFFFFBF4)
private val PaperText = Color(0xFFE7E2D9)
private val Ink = Color(0xFF1D2521)
private val Charcoal = Color(0xFF141916)
private val CharcoalRaised = Color(0xFF29302C)

internal val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Ivory,
    primaryContainer = PineTint,
    onPrimaryContainer = PineInk,
    inversePrimary = PineLight,
    secondary = Color(0xFF4F644D),
    onSecondary = Color.White,
    secondaryContainer = SageTint,
    onSecondaryContainer = SageInk,
    tertiary = Color(0xFF7D5727),
    onTertiary = Color.White,
    tertiaryContainer = MadeleineTint,
    onTertiaryContainer = MadeleineInk,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFDAE5DC),
    onSurfaceVariant = Color(0xFF45504A),
    surfaceTint = Pine,
    inverseSurface = CharcoalRaised,
    inverseOnSurface = Color(0xFFF5F0E9),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF6F7A72),
    outlineVariant = Color(0xFFC1CCC3),
    scrim = Color.Black,
    surfaceBright = Paper,
    surfaceContainer = Color(0xFFF4EFE6),
    surfaceContainerHigh = Color(0xFFEFE9E0),
    surfaceContainerHighest = Color(0xFFE8E2D8),
    surfaceContainerLow = Color(0xFFFAF4EC),
    surfaceContainerLowest = Color.White,
    surfaceDim = Color(0xFFDFD9D0),
    primaryFixed = PineTint,
    primaryFixedDim = PineLight,
    onPrimaryFixed = PineInk,
    onPrimaryFixedVariant = Pine,
    secondaryFixed = SageTint,
    secondaryFixedDim = SageDim,
    onSecondaryFixed = SageInk,
    onSecondaryFixedVariant = SageDeep,
    tertiaryFixed = MadeleineTint,
    tertiaryFixedDim = MadeleineLight,
    onTertiaryFixed = MadeleineInk,
    onTertiaryFixedVariant = MadeleineDeep,
)

internal val DarkColors = darkColorScheme(
    primary = PineLight,
    onPrimary = Color(0xFF0A3825),
    // The icon's own pairing: ivory on pine.
    primaryContainer = Pine,
    onPrimaryContainer = Ivory,
    inversePrimary = Pine,
    secondary = Sage,
    onSecondary = Color(0xFF222F21),
    secondaryContainer = SageDeep,
    onSecondaryContainer = SageTint,
    tertiary = MadeleineLight,
    onTertiary = Color(0xFF472A02),
    tertiaryContainer = MadeleineDeep,
    onTertiaryContainer = MadeleineTint,
    background = Charcoal,
    onBackground = PaperText,
    surface = Charcoal,
    onSurface = PaperText,
    surfaceVariant = Color(0xFF404942),
    onSurfaceVariant = Color(0xFFB9C4BB),
    surfaceTint = PineLight,
    inverseSurface = PaperText,
    inverseOnSurface = CharcoalRaised,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF88938C),
    outlineVariant = Color(0xFF404942),
    scrim = Color.Black,
    surfaceBright = Color(0xFF363D39),
    surfaceContainer = Color(0xFF1D2320),
    surfaceContainerHigh = Color(0xFF282D2A),
    surfaceContainerHighest = Color(0xFF323835),
    surfaceContainerLow = Color(0xFF1A1F1C),
    surfaceContainerLowest = Color(0xFF0A110D),
    surfaceDim = Charcoal,
    primaryFixed = PineTint,
    primaryFixedDim = PineLight,
    onPrimaryFixed = PineInk,
    onPrimaryFixedVariant = Pine,
    secondaryFixed = SageTint,
    secondaryFixedDim = SageDim,
    onSecondaryFixed = SageInk,
    onSecondaryFixedVariant = SageDeep,
    tertiaryFixed = MadeleineTint,
    tertiaryFixedDim = MadeleineLight,
    onTertiaryFixed = MadeleineInk,
    onTertiaryFixedVariant = MadeleineDeep,
)

@Composable
fun CombrayTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // System bar icons follow the in-app choice, not the system's: a forced Dark on a
    // light-mode phone must not leave dark icons on the dark background.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !dark
            bars.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}

/** Whether [CombrayTheme] resolved to dark: the system's setting, or the in-app choice over it. */
internal val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * Rows inside a sheet show the sheet's own color. ListItem paints `surface` by default, which
 * differs from a sheet's `surfaceContainerLow` and drew a band behind every row.
 */
@Composable
internal fun sheetListItemColors(): ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
