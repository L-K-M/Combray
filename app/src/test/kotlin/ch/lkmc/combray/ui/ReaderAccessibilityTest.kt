package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import ch.lkmc.combray.core.browser.ReaderState
import ch.lkmc.combray.core.browser.TextSize
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reader view's messages are announced when they appear. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ReaderAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theUnavailableMessageIsAPoliteLiveRegion() {
        compose.setContent {
            CombrayTheme { ReaderView(ReaderState.Unavailable("tab"), TextSize.DEFAULT, onOpenLink = {}, onClose = {}) }
        }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertExists()
    }
}
