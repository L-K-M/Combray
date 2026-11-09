package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import ch.lkmc.combray.core.browser.TextSize
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The dialog steps from the latest pending choice even when several clicks share one frame. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class TextSizeDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rapidStepsUpTakeTheirStepsFromTheLatestChoiceWithinOneFrame() {
        var current by mutableStateOf(TextSize.PERCENT_100)
        val choices = mutableListOf<TextSize>()
        compose.setContent {
            TextSizeDialog(
                current = { current },
                onChange = { choice -> choices += choice; current = choice },
                onDismiss = {},
            )
        }

        compose.mainClock.autoAdvance = false
        try {
            val larger = compose.onNodeWithContentDescription("Larger text")
            larger.performTouchInput { click() }
            larger.performTouchInput { click() }
            larger.performTouchInput { click() }
        } finally {
            compose.mainClock.autoAdvance = true
        }

        assertEquals(
            listOf(TextSize.PERCENT_110, TextSize.PERCENT_125, TextSize.PERCENT_150),
            choices,
        )
    }

    @Test
    fun rapidStepsDownTakeTheirStepsFromTheLatestChoiceWithinOneFrame() {
        var current by mutableStateOf(TextSize.PERCENT_150)
        val choices = mutableListOf<TextSize>()
        compose.setContent {
            TextSizeDialog(
                current = { current },
                onChange = { choice -> choices += choice; current = choice },
                onDismiss = {},
            )
        }

        compose.mainClock.autoAdvance = false
        try {
            val smaller = compose.onNodeWithContentDescription("Smaller text")
            smaller.performTouchInput { click() }
            smaller.performTouchInput { click() }
        } finally {
            compose.mainClock.autoAdvance = true
        }

        assertEquals(
            listOf(TextSize.PERCENT_125, TextSize.PERCENT_110),
            choices,
        )
    }
}
