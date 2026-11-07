package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The engine-failure, crash and startup overlays must reach their action at 200% font on a
 * small screen (Phase 1 finding: the fixed centered column clipped with no way to scroll).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h480dp")
class OverlayMessageTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theActionStaysReachableAtLargeFontOnASmallScreen() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                CombrayTheme {
                    Box(Modifier.size(320.dp, 480.dp)) {
                        OverlayMessage("This build has no browser engine. Build it with scripts/build-native.sh, then rebuild the app.") {
                            Button(onClick = {}) {
                                Text("Reload")
                            }
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Reload").performScrollTo().assertIsDisplayed()
    }
}
