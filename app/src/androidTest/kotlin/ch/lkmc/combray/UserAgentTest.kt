package ch.lkmc.combray

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real engine's `User-Agent` passes a site firewall rule that takes an agent naming Firefox
 * without a `Gecko/` token for a forged one: Servo's own agent has none, and such sites answered
 * every page with 403 "Access denied". The page also sees the header as `navigator.userAgent`.
 */
@RunWith(AndroidJUnit4::class)
class UserAgentTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    @Test
    fun aFirewallThatRefusesFirefoxWithoutGeckoLetsThePageThrough() {
        device.browser.openUrl(Fixtures.AGENT, Placement.CURRENT_TAB)
        try {
            device.awaitState { state ->
                val tab = state.activeTab
                tab?.url == Fixtures.AGENT && tab.title == SAME_AGENT
            }
        } catch (timeout: TimeoutCancellationException) {
            val tab = device.browser.browserState.value.activeTab
            throw AssertionError(
                "the page did not report the agent it was sent; the tab shows ${tab?.url} titled ${tab?.title}",
                timeout,
            )
        }
    }

    private companion object {
        const val SAME_AGENT = "AGENT:same"
    }
}
