package ch.lkmc.combray

import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.RecentItem
import ch.lkmc.combray.core.browser.ReopenFocus
import ch.lkmc.combray.core.browser.ThemeMode
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.engine.Appearance
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
 * links, query encoding and cancelled media. Screens are checked with real screenshots of
 * solid-color pages.
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
    fun aNewTabOffersTheSiteVisitedMostAndOpensItsNewestPage() {
        device.resetHome()
        open(Device.OCHRE)
        open(Device.GREEN)
        device.browser.newTab()
        val newTab = device.awaitState { it.activeTab?.isHome == true }.activeTab!!.id

        // The tile names the fixture server's site; the new-tab page reads it from history as it shows.
        val tile = compose.activity.getString(R.string.home_open_site, "localhost")
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) { compose.onAllNodesWithContentDescription(tile).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(tile).performClick()

        // The site's newest page, in the new tab: the tab that already shows it stays as it was.
        val state = device.awaitPage(Fixtures.solid(Device.GREEN))
        assertEquals(newTab, state.activeTab?.id)
        assertEquals(2, state.openTabCount)
        assertScreen(Device.GREEN)
    }

    @Test
    fun closingTheActiveTabShowsTheNextPageInsteadOfAnUnusedHomeTab() {
        open(Device.RED)
        val next = device.browser.browserState.value.activeTab!!.id
        open(Device.GREEN, Placement.NEW_TAB)
        val closing = device.browser.browserState.value.activeTab!!.id
        device.browser.newTab()
        val blank = device.awaitState { it.activeTab?.isHome == true }.activeTab!!.id
        device.browser.selectTab(closing)
        device.awaitState { it.activeTab?.id == closing }
        assertScreen(Device.GREEN)

        device.browser.closeTab(closing)
        val state = device.awaitState { it.openTabCount == 2 }

        assertEquals(next, state.activeTab?.id)
        assertScreen(Device.RED)
        assertTrue(device.recent().any { it is RecentItem.OpenTab && it.tabId == blank })
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
        deliverIncomingPage(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(compose.activity.packageName), url)
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
        deliverIncomingPage(share, url)
        assertEquals(2, device.browser.browserState.value.openTabCount)
        assertTrue(device.browser.browserState.value.activeTab!!.openedByOtherApp)
        assertScreen(Device.OCHRE)
    }

    @Test
    fun backFromAnotherAppsLinkLeavesTheAppAndKeepsThePageInHistoryOnly() {
        open(Device.GREEN)
        val url = Fixtures.solid(Device.OCHRE)
        deliverIncomingPage(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(compose.activity.packageName), url)
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

    private fun deliverIncomingPage(intent: Intent, url: String) {
        val activity = compose.activity
        val launchIntent = activity.intent

        try {
            activity.startActivity(intent)
            device.awaitPage(url)
        } finally {
            // ActivityScenario matches lifecycle callbacks by the launch intent. Restore it
            // after real delivery so it can observe Back and its own teardown.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.intent = launchIntent }
        }
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
    fun pagesCanUseTheEnabledExperimentalApis() {
        device.browser.openUrl(Fixtures.APIS, Placement.CURRENT_TAB)
        val state = device.awaitState {
            it.activeTab?.title?.let { title -> title.startsWith(APIS_PREFIX) && title != APIS_WAITING } == true
        }
        assertEquals(APIS_OK, state.activeTab?.title)
    }

    @Test
    fun pagesCanAnimateElements() {
        device.browser.openUrl(Fixtures.ANIMATE, Placement.CURRENT_TAB)
        val state = device.awaitState {
            it.activeTab?.title?.let { title -> title.startsWith(ANIMATE_PREFIX) && title != ANIMATE_WAITING } == true
        }
        assertEquals(ANIMATE_OK, state.activeTab?.title)
    }

    @Test
    fun pagesSeeAScreenAtLeastAsWideAsTheirWindow() {
        device.browser.openUrl(Fixtures.SCREEN, Placement.CURRENT_TAB)
        val state = device.awaitState { it.activeTab?.title?.startsWith(SCREEN_PREFIX) == true }
        val title = state.activeTab?.title.orEmpty()
        val widths = title.removePrefix(SCREEN_PREFIX).split(":").map { it.toIntOrNull() ?: 0 }
        assertEquals("four widths in $title", SCREEN_WIDTHS, widths.size)

        val (screenWidth, availWidth, outerWidth, innerWidth) = widths
        assertTrue("every width is positive: $title", widths.all { it > 0 })
        assertTrue("screen.width >= innerWidth: $title", screenWidth >= innerWidth)
        assertTrue("screen.availWidth >= innerWidth: $title", availWidth >= innerWidth)
        assertTrue("outerWidth >= innerWidth: $title", outerWidth >= innerWidth)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun mediaIsCancelledBeforeDownloadingAndThePageIsTold() {
        Fixtures.reset()
        device.browser.openUrl(Fixtures.MEDIA, Placement.CURRENT_TAB)
        compose.waitUntilAtLeastOneExists(hasText(MEDIA_NOTICE), Device.LOAD_TIMEOUT_MS)

        // Both elements fired `error`, and they no longer hold back the page's load event.
        device.awaitState { it.activeTab?.title == MEDIA_FAILED }
        val loaded = device.awaitPage(Fixtures.MEDIA)
        // Reported before the elements' `error` handlers ran: the video's and the audio's address
        // (they differ in their query).
        assertEquals("media entries", 2, loaded.activeTab?.media?.size)
        val media = Fixtures.state().getJSONObject("media")
        assertEquals("media requests that reached the server", 0, media.getInt("requests"))
        assertEquals("media responses sent in full", 0, media.getInt("completed"))
    }

    @Test
    fun aLinkForAnotherAppKeepsThePageAndSaysWhenNoAppTakesIt() {
        openExternal(Fixtures.external("market"))

        // No app takes market: links on the emulator. Combray says so; the page stays as it was.
        val noApp = compose.activity.getString(R.string.no_app_for_link)
        tapLinkUntil { compose.onAllNodesWithText(noApp).fetchSemanticsNodes().isNotEmpty() }
        val state = device.awaitState { it.activeTab?.title == Fixtures.EXTERNAL_STILL_HERE }
        assertEquals(Fixtures.external("market"), state.activeTab?.url)
        assertFalse(state.activeTab!!.loadFailed)
    }

    @Test
    fun anAppLinkForANewWindowKeepsOneTabAndSaysWhenNoAppTakesIt() {
        val url = Fixtures.external("market", target = LinkTarget.NEW_WINDOW)
        val tab = openExternal(url)

        // The window the link opened closes again; the notice is for the page the user tapped.
        val noApp = compose.activity.getString(R.string.no_app_for_link)
        tapLinkUntil { compose.onAllNodesWithText(noApp).fetchSemanticsNodes().isNotEmpty() }
        val state = device.awaitState { it.openTabCount == 1 && it.activeTab?.title == Fixtures.EXTERNAL_STILL_HERE }
        assertEquals(tab, state.activeTab?.id)
        assertEquals(url, state.activeTab?.url)
        assertFalse(state.activeTab!!.loadFailed)
    }

    @Test
    fun anAppLinkWhoseFallbackIsNoPageSaysNoAppTakesIt() {
        openExternal(Fixtures.external("badfallback"))

        val noApp = compose.activity.getString(R.string.no_app_for_link)
        tapLinkUntil { compose.onAllNodesWithText(noApp).fetchSemanticsNodes().isNotEmpty() }
        val state = device.awaitState { it.activeTab?.title == Fixtures.EXTERNAL_STILL_HERE }
        assertEquals(Fixtures.external("badfallback"), state.activeTab?.url)
    }

    @Test
    fun anAppLinkWithoutItsAppOpensItsFallbackPageInTheSameTab() {
        val tab = openExternal(Fixtures.external("intent"))
        tapLinkUntil { device.browser.browserState.value.activeTab?.url == Fixtures.solid(Device.GREEN) }

        val state = device.awaitPage(Fixtures.solid(Device.GREEN))
        assertEquals(tab, state.activeTab?.id)
        assertScreen(Device.GREEN)
    }

    @Test
    fun pagesCannotOpenFilesOrHandLinksToAppsByThemselves() {
        openExternal(Fixtures.external("file"))
        // The title changes only after the page's own click handler ran and the page stayed.
        tapLinkUntil { device.browser.browserState.value.activeTab?.title == Fixtures.EXTERNAL_STILL_HERE }
        var state = device.awaitState { it.activeTab?.title == Fixtures.EXTERNAL_STILL_HERE }
        assertEquals(Fixtures.external("file"), state.activeTab?.url)

        // Nor by the page's own script: the page stays, without an error page.
        val scriptedFile = Fixtures.external("file", LinkTrigger.SCRIPT)
        device.browser.openUrl(scriptedFile, Placement.CURRENT_TAB)
        state = device.awaitState { it.activeTab?.url == scriptedFile && it.activeTab?.title == Fixtures.EXTERNAL_STILL_HERE }
        assertFalse(state.activeTab!!.loadFailed)

        // Without a tap, the page's own script cannot send the user to another app.
        val scripted = Fixtures.external("market", LinkTrigger.SCRIPT)
        device.browser.openUrl(scripted, Placement.CURRENT_TAB)
        state = device.awaitState { it.activeTab?.url == scripted && it.activeTab?.title == Fixtures.EXTERNAL_STILL_HERE }
        assertFalse(state.activeTab!!.loadFailed)
        val noApp = compose.activity.getString(R.string.no_app_for_link)
        assertTrue(compose.onAllNodesWithText(noApp).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aPageCannotOpenWindowsWithoutATap() {
        val popup = "${Fixtures.BASE}/popup.html"
        device.browser.openUrl(popup, Placement.CURRENT_TAB)
        // The notice comes at load, before the title's 3 s: look for it first, while it shows.
        val blocked = compose.activity.getString(R.string.notice_popup_blocked)
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) { compose.onAllNodesWithText(blocked).fetchSemanticsNodes().isNotEmpty() }
        val state = device.awaitState { it.activeTab?.url == popup && it.activeTab?.title == "POPUP:still-here" }

        assertEquals(1, state.openTabCount)
    }

    /**
     * Taps the page-filling fixture link until [done]. Under software emulation a tap right after
     * the first paint can arrive before the page can hit-test it, and is then lost.
     */
    private fun tapLinkUntil(done: () -> Boolean) {
        val question = compose.activity.getString(R.string.app_link_question)
        val asked = { compose.onAllNodesWithText(question).fetchSemanticsNodes().isNotEmpty() }
        val deadline = SystemClock.uptimeMillis() + Device.LOAD_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            tapPageCenter()
            // Not waitForIdle: it fast-forwards the clock past a snackbar's display time.
            try {
                compose.waitUntil(TAP_OUTCOME_MS) { done() || asked() }
                // A link for another app asks first (owner decision); these checks open it.
                if (!done()) compose.onNodeWithText(compose.activity.getString(R.string.action_open_app_link)).performClick()
                compose.waitUntil(TAP_OUTCOME_MS) { done() }
                return
            } catch (noEffect: ComposeTimeoutException) {
                continue
            }
        }
        throw AssertionError("tapping the link had no effect")
    }

    /** Opens an external-link fixture ([Fixtures.external]) and waits until it is painted; returns its tab. */
    private fun openExternal(url: String): String {
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        val state = device.awaitPage(url)
        assertScreen(Device.OCHRE)
        return state.activeTab!!.id
    }

    @Test
    fun pagesFollowTheDarkAppearanceAndItsChanges() {
        try {
            device.browser.setAppearance(Appearance.DARK)
            device.browser.openUrl(Fixtures.SCHEME, Placement.CURRENT_TAB)
            device.awaitPage(Fixtures.SCHEME)
            assertScreen(Device.BLUE)

            // A change reaches the page already shown, without a reload.
            device.browser.setAppearance(Appearance.LIGHT)
            assertScreen(Device.GREEN)
        } finally {
            // Back to what BrowserScreen told the engine: the app's theme as it resolved it.
            device.browser.setAppearance(appTheme())
        }
    }

    /** The app's theme: the in-app choice, or the system's night mode when it follows the system. */
    private fun appTheme(): Appearance {
        val night = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (device.settings.themeMode.value) {
            ThemeMode.SYSTEM -> night
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        return if (dark) Appearance.DARK else Appearance.LIGHT
    }

    @Test
    fun pagesCanUseRelationalSelectors() {
        device.browser.openUrl(Fixtures.HAS_SELECTOR, Placement.CURRENT_TAB)
        val state = device.awaitState {
            it.activeTab?.title?.let { title -> title.startsWith(HAS_PREFIX) && title != HAS_WAITING } == true
        }
        assertEquals(HAS_OK, state.activeTab?.title)
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

    @Test
    fun aSlowPageShowsLoadingBeforeItsServerAnswers() {
        open(Device.GREEN)
        device.browser.openUrl(Fixtures.SLOW, Placement.CURRENT_TAB)

        // The server has not answered: the tab still shows the green page, and says it loads.
        val waiting = device.awaitState(SLOW_ANSWER_MS) { it.activeTab?.loading == true }
        assertEquals(Fixtures.solid(Device.GREEN), waiting.activeTab!!.url)
        device.awaitPage(Fixtures.SLOW)
    }

    private companion object {
        /** Under the fixture's SLOW_PAGE_SECONDS, so the check runs before the answer. */
        const val SLOW_ANSWER_MS = 5_000L
        const val STOP_TIMEOUT_MS = 10_000L
        /** How long one tap may take to show its effect before the next try. */
        const val TAP_OUTCOME_MS = 15_000L
        const val INTERSECTION_VISIBLE = "IO:visible"
        const val INTERSECTION_MISSING = "IO:missing"
        const val ANIMATE_PREFIX = "WA:"
        const val ANIMATE_WAITING = "WA:waiting"
        const val ANIMATE_OK = "WA:ok"
        const val APIS_PREFIX = "XP:"
        const val APIS_WAITING = "XP:waiting"
        const val APIS_OK = "XP:ok"
        const val SCREEN_PREFIX = "SCREEN:"
        const val SCREEN_WIDTHS = 4
        const val MEDIA_FAILED = "MEDIA:errors:2"
        const val MEDIA_NOTICE = "This page’s audio or video can’t play in Combray."
        const val HAS_PREFIX = "HAS:"
        const val HAS_WAITING = "HAS:waiting"
        const val HAS_OK = "HAS:ok"
    }
}
