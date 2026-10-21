package ch.lkmc.combray.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG contrast of every text pair the UI can put on screen, in both schemes, plus the
 * surface ladder ordering. Guards the palette against future edits that would quietly
 * make supporting text or containers unreadable.
 */
class ThemeContrastTest {
    @Test
    fun lightSchemeTextPairsMeetContrast() = assertTextPairs(LightColors)

    @Test
    fun darkSchemeTextPairsMeetContrast() = assertTextPairs(DarkColors)

    @Test
    fun outlineDistinguishesComponentsOnSurfaces() {
        // Non-text UI (borders, checkbox outlines) needs 3:1 against its surface.
        assertAtLeast("light outline/surface", LightColors.outline, LightColors.surface, 3.0)
        assertAtLeast("dark outline/surface", DarkColors.outline, DarkColors.surface, 3.0)
        assertAtLeast("light outline/lowest", LightColors.outline, LightColors.surfaceContainerLowest, 3.0)
        assertAtLeast("dark outline/highest", DarkColors.outline, DarkColors.surfaceContainerHighest, 3.0)
        assertAtLeast("light outline/low", LightColors.outline, LightColors.surfaceContainerLow, 3.0)
        assertAtLeast("dark outline/high", DarkColors.outline, DarkColors.surfaceContainerHigh, 3.0)
    }

    @Test
    fun lightSurfaceLadderDarkensWithElevation() {
        val order = listOf(
            LightColors.surfaceContainerLowest,
            LightColors.surfaceContainerLow,
            LightColors.surfaceContainer,
            LightColors.surfaceContainerHigh,
            LightColors.surfaceContainerHighest,
        )
        assertLuminanceOrder(order, descending = true, name = "light ladder")
        // Sheet containers must be a real step away from the page background, not a token
        // difference that reads as "the same surface but slightly off".
        val delta = luminance(LightColors.background) - luminance(LightColors.surfaceContainerHigh)
        assertTrue("light background vs high is only $delta", delta >= 0.04)
    }

    @Test
    fun darkSurfaceLadderLightensWithElevation() {
        val order = listOf(
            DarkColors.surfaceContainerLowest,
            DarkColors.surface,
            DarkColors.surfaceContainerLow,
            DarkColors.surfaceContainer,
            DarkColors.surfaceContainerHigh,
            DarkColors.surfaceContainerHighest,
        )
        assertLuminanceOrder(order, descending = false, name = "dark ladder")
    }

    private fun assertTextPairs(scheme: ColorScheme) {
        val pairs = listOf(
            Triple("onPrimary/primary", scheme.onPrimary, scheme.primary),
            Triple("onPrimaryContainer/primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer),
            Triple("onSecondary/secondary", scheme.onSecondary, scheme.secondary),
            Triple("onSecondaryContainer/secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer),
            Triple("onTertiary/tertiary", scheme.onTertiary, scheme.tertiary),
            Triple("onTertiaryContainer/tertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer),
            Triple("onBackground/background", scheme.onBackground, scheme.background),
            Triple("onSurface/surface", scheme.onSurface, scheme.surface),
            Triple("onSurfaceVariant/highest", scheme.onSurfaceVariant, scheme.surfaceContainerHighest),
            Triple("onSurfaceVariant/background", scheme.onSurfaceVariant, scheme.background),
            Triple("onError/error", scheme.onError, scheme.error),
            Triple("onErrorContainer/errorContainer", scheme.onErrorContainer, scheme.errorContainer),
            Triple("inverseOnSurface/inverseSurface", scheme.inverseOnSurface, scheme.inverseSurface),
            Triple("inversePrimary/inverseSurface", scheme.inversePrimary, scheme.inverseSurface),
            // Supporting text on the sheet containers the bottom bar and sheets use.
            Triple("onSurface/low", scheme.onSurface, scheme.surfaceContainerLow),
            Triple("onSurface/high", scheme.onSurface, scheme.surfaceContainerHigh),
        )
        pairs.forEach { (name, foreground, background) ->
            assertAtLeast(name, foreground, background, 4.5)
        }
    }

    private fun assertAtLeast(name: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$name is $ratio, needs at least $minimum:1", ratio >= minimum)
    }

    private fun assertLuminanceOrder(colors: List<Color>, descending: Boolean, name: String) {
        colors.zipWithNext().forEach { (brighter, darker) ->
            val orderOk = if (descending) {
                luminance(brighter) >= luminance(darker)
            } else {
                luminance(brighter) <= luminance(darker)
            }
            assertTrue(
                "$name out of order: #${brighter.value.toString(16)} then #${darker.value.toString(16)}",
                orderOk,
            )
        }
    }
}

private fun luminance(color: Color): Double {
    fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

private fun contrastRatio(first: Color, second: Color): Double {
    val a = luminance(first)
    val b = luminance(second)
    val (lighter, darker) = if (a >= b) a to b else b to a
    return (lighter + 0.05) / (darker + 0.05)
}
