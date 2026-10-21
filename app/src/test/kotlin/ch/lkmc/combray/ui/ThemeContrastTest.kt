package ch.lkmc.combray.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.reflect.KProperty1
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2.x contrast of Combray's color roles, in both schemes. */
class ThemeContrastTest {

    @Test
    fun textAndOutlinesMeetWcagContrastInBothSchemes() {
        val failures = schemes.flatMap { (name, scheme) ->
            requiredPairs.mapNotNull { pair ->
                val ratio = contrast(pair.foreground.get(scheme), pair.background.get(scheme))
                if (ratio >= pair.minimum) null else "$name ${pair.foreground.name} on ${pair.background.name}: %.2f < ${pair.minimum}".format(ratio)
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun noRoleKeepsMaterialBaselineColor() {
        val baselines = mapOf("light" to lightColorScheme(), "dark" to darkColorScheme())
        val failures = schemes.flatMap { (name, scheme) ->
            val baseline = baselines.getValue(name)
            allRoles.filter { role ->
                val value = role.get(scheme)
                value == role.get(baseline) && value !in neutralExtremes && role !in errorRoles
            }.map { "$name ${it.name}" }
        }

        assertTrue("Roles still on Material's baseline:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** The WCAG check itself, against published reference ratios. */
    @Test
    fun contrastFormulaMatchesWcagReferenceValues() {
        assertTrue(contrast(Color.Black, Color.White) in 20.99..21.01)
        assertTrue(contrast(Color.White, Color.White) in 0.99..1.01)
        // #767676 on white is the well-known 4.54:1 threshold grey.
        assertTrue(contrast(Color(0xFF767676), Color.White) in 4.53..4.55)
    }

    private class Requirement(
        val foreground: KProperty1<ColorScheme, Color>,
        val background: KProperty1<ColorScheme, Color>,
        val minimum: Double,
    )

    private companion object {
        const val TEXT_MINIMUM = 4.5
        const val NON_TEXT_MINIMUM = 3.0

        val schemes = listOf("light" to LightColors, "dark" to DarkColors)

        val surfaces = listOf(
            ColorScheme::background,
            ColorScheme::surface,
            ColorScheme::surfaceContainerLowest,
            ColorScheme::surfaceContainerLow,
            ColorScheme::surfaceContainer,
            ColorScheme::surfaceContainerHigh,
            ColorScheme::surfaceContainerHighest,
        )

        // Each on-X role on its X role, as Material pairs them.
        val onRolePairs = listOf(
            ColorScheme::onPrimary to ColorScheme::primary,
            ColorScheme::onPrimaryContainer to ColorScheme::primaryContainer,
            ColorScheme::onSecondary to ColorScheme::secondary,
            ColorScheme::onSecondaryContainer to ColorScheme::secondaryContainer,
            ColorScheme::onTertiary to ColorScheme::tertiary,
            ColorScheme::onTertiaryContainer to ColorScheme::tertiaryContainer,
            ColorScheme::onBackground to ColorScheme::background,
            ColorScheme::onSurface to ColorScheme::surface,
            ColorScheme::onSurfaceVariant to ColorScheme::surfaceVariant,
            ColorScheme::inverseOnSurface to ColorScheme::inverseSurface,
            // The snackbar's action label.
            ColorScheme::inversePrimary to ColorScheme::inverseSurface,
            ColorScheme::onError to ColorScheme::error,
            ColorScheme::onErrorContainer to ColorScheme::errorContainer,
            ColorScheme::onPrimaryFixed to ColorScheme::primaryFixed,
            ColorScheme::onPrimaryFixed to ColorScheme::primaryFixedDim,
            ColorScheme::onPrimaryFixedVariant to ColorScheme::primaryFixed,
            ColorScheme::onPrimaryFixedVariant to ColorScheme::primaryFixedDim,
            ColorScheme::onSecondaryFixed to ColorScheme::secondaryFixed,
            ColorScheme::onSecondaryFixed to ColorScheme::secondaryFixedDim,
            ColorScheme::onSecondaryFixedVariant to ColorScheme::secondaryFixed,
            ColorScheme::onSecondaryFixedVariant to ColorScheme::secondaryFixedDim,
            ColorScheme::onTertiaryFixed to ColorScheme::tertiaryFixed,
            ColorScheme::onTertiaryFixed to ColorScheme::tertiaryFixedDim,
            ColorScheme::onTertiaryFixedVariant to ColorScheme::tertiaryFixed,
            ColorScheme::onTertiaryFixedVariant to ColorScheme::tertiaryFixedDim,
        ).map { (foreground, background) -> Requirement(foreground, background, TEXT_MINIMUM) }

        // Body, muted and accent text (a Recent headline, a Settings error) sit on every surface
        // a sheet, dialog, menu or the bar uses; outlines (text fields, chips) must stay visible there.
        val surfaceText = listOf(
            ColorScheme::onSurface,
            ColorScheme::onSurfaceVariant,
            ColorScheme::primary,
            ColorScheme::secondary,
            ColorScheme::tertiary,
            ColorScheme::error,
        )
        val surfacePairs = surfaces.flatMap { background ->
            surfaceText.map { Requirement(it, background, TEXT_MINIMUM) } + Requirement(ColorScheme::outline, background, NON_TEXT_MINIMUM)
        }

        val requiredPairs = onRolePairs + surfacePairs

        val errorRoles = setOf(ColorScheme::error, ColorScheme::onError, ColorScheme::errorContainer, ColorScheme::onErrorContainer)

        // Pure white and black are not baseline hues; Material's baseline happens to use them too.
        val neutralExtremes = setOf(Color.White, Color.Black)

        val allRoles = listOf(
            ColorScheme::primary, ColorScheme::onPrimary, ColorScheme::primaryContainer, ColorScheme::onPrimaryContainer,
            ColorScheme::inversePrimary,
            ColorScheme::secondary, ColorScheme::onSecondary, ColorScheme::secondaryContainer, ColorScheme::onSecondaryContainer,
            ColorScheme::tertiary, ColorScheme::onTertiary, ColorScheme::tertiaryContainer, ColorScheme::onTertiaryContainer,
            ColorScheme::background, ColorScheme::onBackground, ColorScheme::surface, ColorScheme::onSurface,
            ColorScheme::surfaceVariant, ColorScheme::onSurfaceVariant, ColorScheme::surfaceTint,
            ColorScheme::inverseSurface, ColorScheme::inverseOnSurface,
            ColorScheme::error, ColorScheme::onError, ColorScheme::errorContainer, ColorScheme::onErrorContainer,
            ColorScheme::outline, ColorScheme::outlineVariant, ColorScheme::scrim,
            ColorScheme::surfaceBright, ColorScheme::surfaceDim,
            ColorScheme::surfaceContainerLowest, ColorScheme::surfaceContainerLow, ColorScheme::surfaceContainer,
            ColorScheme::surfaceContainerHigh, ColorScheme::surfaceContainerHighest,
            ColorScheme::primaryFixed, ColorScheme::primaryFixedDim, ColorScheme::onPrimaryFixed, ColorScheme::onPrimaryFixedVariant,
            ColorScheme::secondaryFixed, ColorScheme::secondaryFixedDim, ColorScheme::onSecondaryFixed,
            ColorScheme::onSecondaryFixedVariant,
            ColorScheme::tertiaryFixed, ColorScheme::tertiaryFixedDim, ColorScheme::onTertiaryFixed, ColorScheme::onTertiaryFixedVariant,
        )

        /** WCAG 2.x contrast ratio, (L1 + 0.05) / (L2 + 0.05), from sRGB relative luminance. */
        fun contrast(a: Color, b: Color): Double {
            val first = relativeLuminance(a.toArgb())
            val second = relativeLuminance(b.toArgb())
            return (max(first, second) + 0.05) / (min(first, second) + 0.05)
        }

        fun relativeLuminance(argb: Int): Double {
            fun linear(shift: Int): Double {
                val channel = ((argb shr shift) and 0xFF) / 255.0
                return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
        }
    }
}
