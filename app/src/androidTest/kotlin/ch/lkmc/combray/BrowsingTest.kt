package ch.lkmc.combray

import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.RecentItem
import ch.lkmc.combray.core.browser.ReopenFocus
import ch.lkmc.combray.core.policy.AddressPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M1 gate on the real engine (docs/IMPLEMENTATION_PLAN.md section 9): navigation, same-URL tabs,
 * close and reopen, failed loads, ten-tab switching, rotation, page-opened windows, external
 * links and query encoding. Screens are checked with real screenshots of solid-color pages.
 */
@RunWith(AndroidJUnit4::class)
class BrowsingTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    private fun open(color: Int, placement: Placement = Placement.CURRENT_TAB) {
        device.browser.openUrl(Fixtures.solid(color), placement)
        device.awaitPage(Fixtures.solid(color))
        assertScreen(color)
    }

    private fun assertScreen(color: Int) {
        val seen = device.awaitScreen(color)
        assertTrue("screen shows %06x, expected %06x".format(seen ?: -1, color), device.matches(seen, color))
    }

    @Test
    fun navigatingShowsTheNewPageAndKeepsTheOldOneInRecent() {
        // Earlier checks in this run leave their closed pages in Recent: look only at this one's.
        val started = System.currentTimeMillis()
        open(Device.RED)
        open(Device.GREEN)
        assertTrue(device.browser.browserState.value.activeTab!!.canGoBack)

        val items = device.recent()
        assertEquals(Fixtures.solid(Device.GREEN), items.filterIsInstance<RecentItem.OpenTab>().single().url)
        assertEquals(listOf(Fixtures.solid(Device.RED)), items.filterIsInstance<RecentItem.Visit>().filter { it.time >= started }.map { it.url })

        device.browser.goBack()
        device.awaitPage(Fixtures.solid(Device.RED))
        assertScreen(Device.RED)
    }

    @Test
    fun twoTabsAtTheSameAddressStayIndependent() {
        open(Device.BLUE)
        val first = device.browser.browserState.value.activeTab!!.id
        // The first tab already shows this address: wait until the new tab's own page reported
        // its title (a new tab has none until the engine loaded it).
        device.browser.openUrl(Fixtures.solid(Device.BLUE), Placement.NEW_TAB)
        val second = device.awaitState { it.activeTab?.id != first && it.activeTab?.title == Fixtures.title(Device.BLUE) && it.activeTab?.loading == false }
            .activeTab!!.id
        assertNotEquals(first, second)
        assertScreen(Device.BLUE)

        device.browser.selectTab(first)
        device.awaitState { it.activeTab?.id == first }
        assertScreen(Device.BLUE)
        device.browser.closeTab(second)
        device.awaitState { it.openTabCount == 1 }
        assertEquals(first, device.browser.browserState.value.activeTab?.id)
        assertScreen(Device.BLUE)
    }

    @Test
    fun closingTheLastTabShowsHomeAndUndoReopensTheSameTab() {
        open(Device.OCHRE)
        val tab = device.browser.browserState.value.activeTab!!.id
        device.browser.closeTab(tab)
        device.awaitState { it.activeTab == null }
        // The closed page must not stay painted under the home screen.
        assertFalse(device.matches(device.screenColor(), Device.OCHRE))

        device.browser.reopenTab(tab, ReopenFocus.FOCUS)
        device.awaitPage(Fixtures.solid(Device.OCHRE))
        assertEquals(tab, device.browser.browserState.value.activeTab?.id)
        assertScreen(Device.OCHRE)
    }

    @Test
    fun aFailedLoadIsShownButNotRecordedAsHistory() {
        open(Device.GREEN)
        val unreachable = "http://localhost:9/unreachable"
        device.browser.openUrl(unreachable, Placement.CURRENT_TAB)
        device.awaitState { it.activeTab?.url == unreachable && it.activeTab?.title == "Error loading page" }
        assertTrue(device.recent().none { it is RecentItem.Visit && it.url == unreachable })
    }

    @Test
    fun aFailedReloadKeepsThePageInRecentWithItsTitle() {
        // A key of its own: the fixture answers it once, so the reload fails.
        val url = Fixtures.once(System.currentTimeMillis().toString())
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        device.awaitState { it.activeTab?.url == url && it.activeTab?.title == Fixtures.ONCE_TITLE && it.activeTab?.loading == false }

        device.browser.reload()
        device.awaitState { it.activeTab?.loadFailed == true && it.activeTab?.title == "Error loading page" }
        device.browser.closeTab(device.browser.browserState.value.activeTab!!.id)
        device.awaitState { it.activeTab == null }
        assertTrue(device.recent().any { it is RecentItem.Visit && it.url == url && it.title == Fixtures.ONCE_TITLE })
    }

    @Test
    fun tenTabsCanBeSwitched() {
        val colors = (0 until 10).map { 0x204060 + it * 0x101010 }
        val tabs = colors.mapIndexed { index, color ->
            open(color, if (index == 0) Placement.CURRENT_TAB else Placement.NEW_TAB)
            device.browser.browserState.value.activeTab!!.id
        }
        assertEquals(10, device.browser.browserState.value.openTabCount)
        tabs.zip(colors).reversed().forEach { (tab, color) ->
            device.browser.selectTab(tab)
            device.awaitState { it.activeTab?.id == tab }
            assertScreen(color)
        }
    }

    @Test
    fun rotationRecreatesTheSurfaceButKeepsTabsAndThePage() {
        open(Device.RED)
        val tab = device.browser.browserState.value.activeTab!!.id
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            // The frame from before the rotation must not count: wait for the turned screen.
            device.awaitOrientation(Orientation.LANDSCAPE)
            assertScreen(Device.RED)
            assertEquals(tab, device.browser.browserState.value.activeTab?.id)
        } finally {
            automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
        device.awaitOrientation(Orientation.PORTRAIT)
        assertScreen(Device.RED)
    }

    @Test
    fun aWindowOpenedByThePageBecomesAFocusedTabThatBackReturnsFrom() {
        val opener = "${Fixtures.BASE}/opener.html"
        device.browser.openUrl(opener, Placement.CURRENT_TAB)
        device.awaitState { it.activeTab?.title == "OPENER" && it.activeTab?.loading == false }
        val openerTab = device.browser.browserState.value.activeTab!!.id
        // Loaded is not yet painted: tap only once the page's own colour is on screen.
        assertScreen(Device.VIOLET)

        tapPageCenter()
        device.awaitPage(Fixtures.solid(Device.BLUE))
        assertNotEquals(openerTab, device.browser.browserState.value.activeTab?.id)
        assertEquals(2, device.browser.browserState.value.openTabCount)
        assertScreen(Device.BLUE)

        // Back on its first page closes it and returns to the opener, without leaving the app.
        assertTrue(device.browser.browserState.value.activeTab!!.returnsToOpener)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val state = device.awaitState { it.openTabCount == 1 }
        assertEquals(openerTab, state.activeTab?.id)
        assertScreen(Device.VIOLET)
        assertEquals(Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
    }

    @Test
    fun anExternalLinkOpensInANewTab() {
        open(Device.GREEN)
        val url = Fixtures.solid(Device.OCHRE)
        compose.activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(compose.activity.packageName))
        device.awaitPage(url)
        assertEquals(2, device.browser.browserState.value.openTabCount)
        assertScreen(Device.OCHRE)
    }

    @Test
    fun aSharedLinkOpensInANewTab() {
        open(Device.GREEN)
        val url = Fixtures.solid(Device.OCHRE)
        // As a chat app shares it: inside a sentence, a full stop after it. Explicit, because the
        // Save to Karakeep target takes shared text too.
        val share = Intent(Intent.ACTION_SEND).setClass(compose.activity, MainActivity::class.java)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Look at $url.")
        compose.activity.startActivity(share)
        device.awaitPage(url)
        assertEquals(2, device.browser.browserState.value.openTabCount)
        assertTrue(device.browser.browserState.value.activeTab!!.openedByOtherApp)
        assertScreen(Device.OCHRE)
    }

    @Test
    fun backFromAnotherAppsLinkLeavesTheAppAndKeepsThePageInHistoryOnly() {
        open(Device.GREEN)
        val url = Fixtures.solid(Device.OCHRE)
        compose.activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(compose.activity.packageName))
        // The title comes from the engine, so the page has committed (a new tab has its address before).
        device.awaitState { it.activeTab?.url == url && it.activeTab?.title == Fixtures.title(Device.OCHRE) }
        assertTrue(device.browser.browserState.value.activeTab!!.openedByOtherApp)
        // The UI enables its Back handling once it has drawn that state, as it has long before a
        // person presses Back.
        compose.waitForIdle()

        // The system Back key, as the user presses it.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val state = device.awaitState { it.openTabCount == 1 }
        assertEquals(Fixtures.solid(Device.GREEN), state.activeTab?.url)
        val recent = device.recent()
        assertTrue("$url not in history: ${recent.take(5)}", recent.any { it is RecentItem.Visit && it.url == url })
        awaitActivityStopped()
    }

    private fun awaitActivityStopped() {
        val deadline = SystemClock.uptimeMillis() + STOP_TIMEOUT_MS
        while (compose.activityRule.scenario.state != Lifecycle.State.CREATED) {
            assertTrue("Combray stayed in the foreground", SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(100)
        }
    }

    @Test
    fun aSearchQueryReachesTheServerEncodedExactlyOnce() {
        val query = "C++ & Rust #1 ü 😀 /x?y=z"
        // Same encoder as Kagi searches; the fixture echoes what it received in the title.
        val url = AddressPolicy.kagiSearchUrl(query).replace("https://kagi.com/search", "${Fixtures.BASE}/echo")
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        val state = device.awaitState { it.activeTab?.title?.startsWith("ECHO:") == true }
        assertEquals("ECHO:$query", state.activeTab?.title)
    }

    @Test
    fun pagesCanObserveWhatIsOnScreen() {
        device.browser.openUrl(Fixtures.INTERSECTION, Placement.CURRENT_TAB)
        val state = device.awaitState { it.activeTab?.title in setOf(INTERSECTION_VISIBLE, INTERSECTION_MISSING) }
        assertEquals(INTERSECTION_VISIBLE, state.activeTab?.title)
    }

    @Test
    fun pagesCanAnimateElements() {
        device.browser.openUrl(Fixtures.ANIMATE, Placement.CURRENT_TAB)
        val state = device.awaitState {
            it.activeTab?.title?.let { title -> title.startsWith(ANIMATE_PREFIX) && title != ANIMATE_WAITING } == true
        }
        assertEquals(ANIMATE_OK, state.activeTab?.title)
    }

    private fun tapPageCenter() {
        device.dismissSystemDialogs()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = compose.activity.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.35f
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP, x, y, 0))
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 10_000L
        const val INTERSECTION_VISIBLE = "IO:visible"
        const val INTERSECTION_MISSING = "IO:missing"
        const val ANIMATE_PREFIX = "WA:"
        const val ANIMATE_WAITING = "WA:waiting"
        const val ANIMATE_OK = "WA:ok"
    }
}
