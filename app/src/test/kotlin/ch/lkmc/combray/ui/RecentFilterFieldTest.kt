package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recent's local filter: the keyboard's Search key runs the filter's action, which puts the
 * keyboard away, without dropping the typed text. The filter itself applies as you type.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class RecentFilterFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun searchRunsTheActionAndKeepsTheFilter() {
        var text by mutableStateOf("")
        var focusLosses = 0
        compose.setContent {
            CombrayTheme {
                RecentFilterField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.onFocusChanged { if (!it.isFocused) focusLosses++ },
                )
            }
        }

        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNode(hasImeAction(ImeAction.Search)).assertExists()
        compose.onNode(hasSetTextAction()).performClick()
        focusLosses = 0
        compose.onNode(hasSetTextAction()).performTextInput("example")
        compose.onNode(hasSetTextAction()).performImeAction()

        compose.runOnIdle {
            assertTrue("the Search action never put the keyboard away", focusLosses > 0)
            assertEquals("example", text)
        }
    }
}
