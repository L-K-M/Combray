package ch.lkmc.combray

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.BrowserState
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.ReaderState
import ch.lkmc.combray.core.browser.RecentItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real loader cancellation: a native UI flag cannot complete the fixture's document. */
@RunWith(AndroidJUnit4::class)
class StopLoadingTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private val key = UUID.randomUUID().toString().replace("-", "")

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
        Fixtures.reset()
    }

    @After
    fun tearDown() {
        if (Fixtures.state().getJSONObject("stop_loads").has(key)) Fixtures.finishStopLoad(key)
        device.closeAllTabs()
    }

    @Test
    fun stopAbortsTheParserButKeepsTheArticleAndItsScriptsAlive() {
        val url = Fixtures.stopPage(key, StopFixtureStage.COMMITTED)
        device.browser.openUrl(url, Placement.NEW_TAB)
        val before = device.awaitState {
            val tab = it.activeTab ?: return@awaitState false
            tab.url == url && tab.loading && tab.page != null && tab.title?.startsWith(LOADING_HEARTBEAT) == true
        }
        val original = before.activeTab!!
        awaitHeldRequest()
        tapStop()

        // The heartbeat runs in the retained document. UI-only cancellation cannot make it complete.
        val stopped = device.awaitState {
            val tab = it.activeTab ?: return@awaitState false
            tab.page == original.page && !tab.loading && tab.title?.startsWith(STOPPED_HEARTBEAT) == true
        }
        val tick = heartbeatTick(stopped)
        Fixtures.finishStopLoad(key)
        val after = device.awaitState {
            it.activeTab?.title?.startsWith(STOPPED_HEARTBEAT) == true &&
                heartbeatTick(it) >= tick + HEARTBEAT_ADVANCES
        }
        val retained = after.activeTab!!
        assertEquals(original.page, retained.page)
        assertEquals(original.id, retained.id)
        assertEquals(url, retained.url)
        assertEquals(1, after.openTabCount)

        compose.onNodeWithContentDescription(READER).assertIsDisplayed().performClick()
        val reader = device.awaitState { it.reader is ReaderState.Ready }.reader as ReaderState.Ready
        val text = reader.article.blocks.flatMap { it.spans }.joinToString("") { it.text }
        assertTrue("The committed article was lost", "STOP-COMMITTED" in text)
        compose.onNodeWithContentDescription(LEAVE_READER).assertIsDisplayed().performClick()
        device.awaitState { it.reader == null }
    }

    @Test
    fun stopCancelsAPendingNavigationWithoutReplacingTheCommittedPage() {
        val original = Fixtures.solid(Device.GREEN)
        device.browser.openUrl(original, Placement.NEW_TAB)
        val before = device.awaitPage(original).activeTab!!
        val pending = Fixtures.stopPage(key, StopFixtureStage.PENDING)
        device.browser.openUrl(pending, Placement.CURRENT_TAB)
        awaitHeldRequest()
        device.awaitState { it.activeTab?.loading == true }
        tapStop()
        val stopped = device.awaitState { it.activeTab?.loading == false }.activeTab!!
        assertEquals(before.page, stopped.page)
        assertEquals(original, stopped.url)

        Fixtures.finishStopLoad(key)
        navigateAgain(before.id)
        assertFalse(device.recent().filterIsInstance<RecentItem.Visit>().any { it.url == pending })
    }

    @Test
    fun stoppingTheFirstResponseLeavesTheSameTabUsable() {
        val pending = Fixtures.stopPage(key, StopFixtureStage.PENDING)
        device.browser.openUrl(pending, Placement.NEW_TAB)
        awaitHeldRequest()
        val before = device.awaitState { it.activeTab?.loading == true }.activeTab!!
        assertEquals(null, before.page)
        tapStop()
        device.awaitState {
            val tab = it.activeTab ?: return@awaitState false
            !tab.loading && tab.page != null
        }

        Fixtures.finishStopLoad(key)
        navigateAgain(before.id)
        assertFalse(device.recent().filterIsInstance<RecentItem.Visit>().any { it.url == pending })
    }

    private fun awaitHeldRequest() {
        compose.waitUntil(FIXTURE_TIMEOUT_MS) { Fixtures.stopLoadRequested(key) }
    }

    private fun tapStop() {
        compose.onNodeWithContentDescription(STOP).assertIsDisplayed().performClick()
    }

    private fun navigateAgain(tab: String) {
        val next = Fixtures.solid(Device.BLUE)
        device.browser.openUrl(next, Placement.CURRENT_TAB)
        val after = device.awaitPage(next)
        assertEquals(tab, after.activeTab!!.id)
        assertEquals(1, after.openTabCount)
    }

    private fun heartbeatTick(state: BrowserState) = state.activeTab!!.title!!.substringAfterLast(':').toInt()

    private companion object {
        const val STOP = "Stop loading"
        const val READER = "Reader view"
        const val LEAVE_READER = "Leave reader view"
        const val LOADING_HEARTBEAT = "STOP:loading:0:"
        const val STOPPED_HEARTBEAT = "STOP:complete:0:"
        const val HEARTBEAT_ADVANCES = 2
        const val FIXTURE_TIMEOUT_MS = 15_000L
    }
}
