package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ReloadPullIndicatorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun feedbackAppearsOnlyAfterMovingAndNamesWhenReleaseWillReload() {
        var progress by mutableFloatStateOf(0f)
        compose.setContent { CombrayTheme { ReloadPullIndicator(progress) } }
        compose.onNodeWithContentDescription(PULL).assertDoesNotExist()
        compose.onNodeWithContentDescription(RELEASE).assertDoesNotExist()

        compose.runOnIdle { progress = 0.5f }
        compose.onNodeWithContentDescription(PULL).assertIsDisplayed()
        compose.runOnIdle { progress = 1f }
        compose.onNodeWithContentDescription(RELEASE).assertIsDisplayed()
        compose.onNodeWithContentDescription(PULL).assertDoesNotExist()

        compose.runOnIdle { progress = 0f }
        compose.onNodeWithContentDescription(RELEASE).assertDoesNotExist()
    }

    private companion object {
        const val PULL = "Pull down to reload"
        const val RELEASE = "Release to reload"
    }
}
