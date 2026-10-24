package ch.lkmc.combray

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.TabCounter
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The persisted preference updates the browser's badge and survives activity recreation. */
@RunWith(AndroidJUnit4::class)
class TabCounterTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        device.awaitEngine()
        device.settings.setTabCounter(TabCounter.HIDDEN)
        device.closeAllTabs()
    }

    @After
    fun tearDown() = device.settings.setTabCounter(TabCounter.HIDDEN)

    @Test
    fun theCounterIsOptionalAndItsChoiceSurvivesRecreation() {
        val url = Fixtures.solid(Device.GREEN)
        device.browser.openUrl(url, Placement.NEW_TAB)
        device.awaitPage(url)

        compose.onNodeWithText("1", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Recent, 1 open tab").assertIsDisplayed()

        // Settings uses this same preference. Sheets miss the software emulator's focus deadline.
        device.settings.setTabCounter(TabCounter.SHOWN)
        compose.onNodeWithText("1", useUnmergedTree = true).assertIsDisplayed()

        device.browser.openUrl(url, Placement.NEW_TAB)
        device.awaitState { it.openTabCount == 2 }
        compose.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()

        device.settings.setTabCounter(TabCounter.HIDDEN)
        compose.onNodeWithText("2", useUnmergedTree = true).assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("2", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Recent, 2 open tabs").assertIsDisplayed()
    }
}
