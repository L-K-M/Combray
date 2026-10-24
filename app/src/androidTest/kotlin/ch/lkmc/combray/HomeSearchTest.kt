package ch.lkmc.combray

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.SessionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home input regression without fixture services or web navigation. */
@RunWith(AndroidJUnit4::class)
class HomeSearchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun newTabFocusesTheExistingHomeField() {
        val device = Device { compose.mainClock.advanceTimeByFrame() }
        device.awaitState { it.session == SessionState.RESTORED }
        device.closeAllTabs()
        compose.onNodeWithContentDescription("New tab").performClick()
        device.awaitState { it.activeTab?.isHome == true }

        compose.onNode(hasSetTextAction() and isFocused()).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.home_search_hint)).assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()
    }
}
