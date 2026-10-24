package ch.lkmc.combray

import android.view.KeyEvent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.espresso.IdlingPolicies
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.HomePinResult
import ch.lkmc.combray.core.browser.HomeSectionVisibility
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.integration.Configured
import ch.lkmc.combray.core.integration.SensitiveUrls
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Acceptance coverage for the new-tab Home surface: the launchpad itself, Continue's tab
 * identity, pinned links, the Edit surface, the service dock and local suggestions.
 *
 * Automatic-link ranking and opening stay covered by BrowsingTest; these checks cover
 * what the new surface adds, with synthetic pages and fixture services only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HomeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    /** `AppGraph.home`, the durable manual-link store the surface edits. */
    private val home get() = device.app.graph.home

    @Before
    fun setUp() {
        Fixtures.reset()
        device.awaitEngine()
        device.closeAllTabs()
        clearHome()
        device.settings.setHomeContinue(HomeSectionVisibility.SHOWN)
        device.settings.setHomeFrequentSites(HomeSectionVisibility.SHOWN)
        runBlocking { device.integrations.hister.setCaptureEnabled(false) }
        // Sheets can outstay Espresso's idle window on the software emulator (docs/BUILDING.md).
        IdlingPolicies.setIdlingResourceTimeout(Device.LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        IdlingPolicies.setMasterPolicyTimeout(Device.LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @After
    fun tearDown() {
        // A check that stopped halfway must not leak pins, hidden sites or hidden sections.
        device.settings.setHomeContinue(HomeSectionVisibility.SHOWN)
        device.settings.setHomeFrequentSites(HomeSectionVisibility.SHOWN)
        clearHome()
    }

    /** + takes one tap anywhere; More no longer lists it. Home opens ready, keyboard closed. */
    @Test
    fun newTabOpensHomeWithTheKeyboardClosedAndReusesOneBlank() {
        open(Device.GREEN)

        device.dismissSystemDialogs()
        compose.onNodeWithContentDescription("More").performClick()
        awaitText("Settings")
        compose.onNodeWithText("New tab").assertDoesNotExist()
        back()
        compose.waitUntilDoesNotExist(hasText("Settings"), Device.LOAD_TIMEOUT_MS)

        compose.onNodeWithContentDescription("New tab").performClick()
        val state = device.awaitState { it.activeTab?.isHome == true }
        assertEquals(2, state.openTabCount)
        awaitText(compose.activity.getString(R.string.home_heading))
        // Search is a deliberate second tap: no editable field may take focus on its own.
        compose.onNode(hasSetTextAction() and isFocused()).assertDoesNotExist()

        // + on Home finds the already open blank instead of piling up tabs.
        compose.onNodeWithContentDescription("New tab").performClick()
        device.idle()
        val reused = device.browser.browserState.value
        assertEquals(2, reused.openTabCount)
        assertEquals(true, reused.activeTab?.isHome)
    }

    @Test
    fun newTabIsAvailableWhileFindingInAPage() {
        open(Device.GREEN)
        device.browser.openFind()
        device.awaitState { it.find != null }
        compose.onNodeWithContentDescription("New tab").performClick()
        val state = device.awaitState { it.activeTab?.isHome == true && it.find == null }
        assertEquals(2, state.openTabCount)
        awaitText(compose.activity.getString(R.string.home_heading))
    }

    @Test
    fun anEmptyHomeAddsALinkDirectlyAndCancelWritesNothing() {
        device.settings.setHomeFrequentSites(HomeSectionVisibility.HIDDEN)
        openHome()
        awaitText(compose.activity.getString(R.string.home_add_link))
        compose.onNodeWithText(compose.activity.getString(R.string.home_add_link)).performClick()
        compose.waitUntilAtLeastOneExists(hasSetTextAction(), Device.LOAD_TIMEOUT_MS)
        compose.onNodeWithText(compose.activity.getString(R.string.home_link_address)).assertIsDisplayed()
        sheetRow(hasText("Cancel")).performClick()
        assertTrue(homeState().pins.isEmpty())
        sheetRow(hasText("Done")).performClick()
    }

    @Test
    fun aCloseNoticeDoesNotCoverHomeLinks() {
        runBlocking { home.savePin(null, Fixtures.solid(PINNED), "Shortcut") }
        open(Device.GREEN)
        device.browser.closeTab(device.browser.browserState.value.activeTab!!.id)
        device.awaitState { it.activeTab == null }
        awaitText("Tab closed")
        val tile = awaitTile("Shortcut").getUnclippedBoundsInRoot()
        val notice = compose.onNodeWithText("Tab closed").getUnclippedBoundsInRoot()
        assertTrue("a close notice must not cover a Home destination", tile.bottom <= notice.top)
    }

    @Test
    fun reopeningTheEditorUsesTheSelectedPinInsteadOfADiscardedDraft() {
        val first = Fixtures.solid(PIN_FIRST)
        val second = Fixtures.solid(PIN_SECOND)
        runBlocking {
            home.savePin(null, first, "First")
            home.savePin(null, second, "Second")
        }
        openHome()
        awaitText("Edit")
        compose.onNodeWithText("Edit").performClick()
        sheetRow(hasContentDescription("Edit First")).performClick()
        compose.waitUntilAtLeastOneExists(hasSetTextAction(), Device.LOAD_TIMEOUT_MS)
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("Discarded draft")
        compose.onNodeWithContentDescription("Close sheet").performClick()
        compose.waitUntilDoesNotExist(hasSetTextAction(), Device.LOAD_TIMEOUT_MS)

        compose.onNodeWithText("Edit").performClick()
        sheetRow(hasText("Edit new tab")).assertIsDisplayed()
        sheetRow(hasContentDescription("Edit Second")).performClick()
        compose.waitUntilAtLeastOneExists(hasSetTextAction(), Device.LOAD_TIMEOUT_MS)
        val fields = compose.onAllNodes(hasSetTextAction())
        assertEquals("Second", fields[0].fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(second, fields[1].fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(listOf("First", "Second"), homeState().pins.map { it.title })
        sheetRow(hasText("Cancel")).performClick()
        sheetRow(hasText("Done")).performClick()
    }

    /**
     * Two open tabs can share one address: Continue must select the exact tab that was
     * focused most recently, not whichever tab happens to show that page first. And after
     * the renderers behind every hidden tab are released, the same selection must still
     * work — the engine recreates the view from the tab's own address.
     */
    @Test
    fun continueSelectsTheMostRecentSameUrlTabByIdAndRestoresIt() {
        val url = open(Device.BLUE)
        val first = device.browser.browserState.value.activeTab!!.id
        open(Device.BLUE, Placement.NEW_TAB)
        val second = device.browser.browserState.value.activeTab!!.id
        assertNotEquals(first, second)

        openHome()
        tapContinue(Fixtures.title(Device.BLUE))
        val continued = device.awaitState { it.activeTab?.id == second }
        assertEquals(url, continued.activeTab?.url)

        // A sign-in address is ineligible for Continue: the card offers the older eligible
        // tab, whose renderer the release just evicted, and selecting it restores the page.
        val sensitive = "${Fixtures.solid(SENSITIVE_TAB)}?access_token=secret"
        device.browser.openUrl(sensitive, Placement.NEW_TAB)
        device.awaitState { it.activeTab?.url == sensitive }
        openHome()
        device.browser.releaseHiddenViews()
        device.idle()

        tapContinue(Fixtures.title(Device.BLUE))
        device.awaitPage(url)
        assertEquals(second, device.browser.browserState.value.activeTab?.id)
        device.awaitScreen(Device.BLUE)
    }

    /**
     * A pin keeps the address it was saved with, opens in the blank it was tapped on, and is
     * still there after the activity is recreated. The same store rejects what it may not
     * keep: non-addresses and credential-carrying links.
     */
    @Test
    fun aPinnedLinkOpensItsExactAddressAndSurvivesRecreation() {
        val pinned = Fixtures.solid(PINNED)
        runBlocking {
            assertEquals(HomePinResult.SAVED, home.savePin(null, pinned, "Pinned place"))
            assertEquals(HomePinResult.INVALID_ADDRESS, home.savePin(null, "not a web address", "invalid"))
            assertEquals(HomePinResult.SENSITIVE_ADDRESS, home.savePin(null, "$pinned?access_token=secret", "sensitive"))
        }

        openHome()
        val blank = device.browser.browserState.value.activeTab!!.id
        awaitTile("Pinned place").performClick()
        device.awaitPage(pinned)
        assertEquals(blank, device.browser.browserState.value.activeTab?.id)

        compose.activityRule.scenario.recreate()
        openHome()
        awaitTile("Pinned place")
        assertTrue(homeState().pins.any { it.url == pinned })
    }

    /**
     * The Edit surface is where Home's lists change: pinned links reorder and unpin there,
     * hidden sites restore there, and its section switches write the same preferences the
     * surface reads. Adding a link goes through its own Name and Web address fields.
     */
    @Test
    fun theEditSurfaceManagesPinsHiddenSitesAndSections() {
        runBlocking {
            home.savePin(null, Fixtures.solid(PIN_FIRST), "First")
            home.savePin(null, Fixtures.solid(PIN_SECOND), "Second")
        }
        open(Device.GREEN) // a frequent site for the automatic "localhost" tile
        openHome()
        awaitText(compose.activity.getString(R.string.home_links_title))

        compose.onNodeWithText(compose.activity.getString(R.string.home_edit)).performClick()
        awaitText(compose.activity.getString(R.string.home_edit_title))

        // Reordering lands in the stored order.
        sheetRow(hasContentDescription(compose.activity.getString(R.string.home_move_later, "First"))).performClick()
        awaitPins("Second", "First")

        sheetRow(hasContentDescription(compose.activity.getString(R.string.home_unpin_named, "Second"))).performClick()
        awaitPins("First")

        // A site hidden by the API is offered for restore; the surface restores it.
        runBlocking { home.hideSite("localhost") }
        val restore = compose.activity.getString(R.string.home_restore_site, "localhost")
        sheetRow(hasContentDescription(restore)).performClick()
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) { homeState().hiddenSites.isEmpty() }

        // The section switches are bound to the settings Home reads.
        val frequent = compose.activity.getString(R.string.home_show_frequent)
        sheetRow(hasText(frequent)).performClick()
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) { device.settings.homeFrequentSites.value == HomeSectionVisibility.HIDDEN }
        sheetRow(hasText(frequent)).performClick()
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) { device.settings.homeFrequentSites.value == HomeSectionVisibility.SHOWN }

        // Adding a link uses the sheet's own fields, in the order the dialog lists them.
        sheetRow(hasText(compose.activity.getString(R.string.home_add_link))).performClick()
        compose.waitUntilAtLeastOneExists(hasSetTextAction(), Device.LOAD_TIMEOUT_MS)
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Added")
        fields[1].performTextInput(Fixtures.solid(ADDED))
        awaitText(compose.activity.getString(R.string.home_save_link))
        compose.onNodeWithText(compose.activity.getString(R.string.home_save_link)).performClick()
        awaitText("Added")
        assertTrue(homeState().pins.any { it.url == Fixtures.solid(ADDED) })
        sheetRow(hasText("Done")).performClick()
    }

    /**
     * Home itself must not call a configured service: opening it is free. The dock's Hister
     * and Karakeep actions open their Recent scopes directly, and only Karakeep's explicit
     * open may list bookmarks; Hister still searches only on a typed query.
     */
    @Test
    fun theDockOpensServiceScopesWhileHomeItselfCallsNoServer() {
        runBlocking {
            assertEquals(Configured.Saved, device.integrations.configureKarakeep(Fixtures.KARAKEEP_ROOT, Fixtures.KARAKEEP_KEY))
            assertEquals(Configured.Saved, device.integrations.hister.configure(Fixtures.HISTER_ROOT, Fixtures.HISTER_TOKEN))
        }
        Fixtures.reset() // zero the request log after any configure-time traffic

        openHome()
        awaitText(compose.activity.getString(R.string.home_heading))
        device.idle()
        compose.waitForIdle()
        val calls = serviceCalls()
        assertEquals("opening Home called a configured service: $calls", 0, calls.size)

        dock("Hister").performClick()
        awaitText(compose.activity.getString(R.string.search_all_hint))
        assertTrue("opening the Hister scope searched already", serviceCalls().none { it.getString("service") == "hister" })
        back()
        compose.waitUntilDoesNotExist(hasText(compose.activity.getString(R.string.search_all_hint)), Device.LOAD_TIMEOUT_MS)

        dock("Karakeep").performClick()
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) {
            serviceCalls().any { it.getString("service") == "karakeep" && it.getString("method") == "GET" }
        }
        back()
    }

    /**
     * Typing in the editor lists local matches above the field — the same Recent rows the
     * editor always shows, nothing fetched. A closed page answers a match by opening in the
     * tab being edited: the blank, not a new tab.
     */
    @Test
    fun typingListsLocalMatchesAboveTheFieldAndOpensTheChoice() {
        val url = open(SUGGESTED)
        device.browser.closeTab(device.browser.browserState.value.activeTab!!.id)
        openHome()

        compose.onNodeWithText(compose.activity.getString(R.string.home_search_hint)).performClick()
        compose.waitUntilAtLeastOneExists(hasSetTextAction() and isFocused(), Device.LOAD_TIMEOUT_MS)
        // The editor keeps + beside it: a new tab is one tap even while searching.
        compose.onNode(hasContentDescription("New tab") and hasAnyAncestor(isDialog())).assertIsDisplayed()

        val field = compose.onNode(hasSetTextAction() and isFocused())
        field.performTextInput("%06x".format(SUGGESTED and 0xFFFFFF))
        val title = Fixtures.title(SUGGESTED)
        val match = (hasText(title) and hasClickAction() or (hasClickAction() and hasAnyDescendant(hasText(title)))) and hasAnyAncestor(isDialog())
        compose.waitUntilAtLeastOneExists(match, Device.LOAD_TIMEOUT_MS)
        val row = compose.onAllNodes(match).onFirst()
        assertTrue(
            "matches must sit above the field",
            row.getUnclippedBoundsInRoot().bottom <= field.getUnclippedBoundsInRoot().top,
        )
        val blank = device.browser.browserState.value.activeTab!!.id
        row.performClick()
        device.awaitPage(url)
        assertEquals(blank, device.browser.browserState.value.activeTab?.id)
    }

    private fun open(color: Int, placement: Placement = Placement.CURRENT_TAB): String {
        val url = Fixtures.solid(color)
        val previous = device.browser.browserState.value.activeTab?.id
        device.browser.openUrl(url, placement)
        // An equal address may already be loaded: a new tab must report its own identity/title.
        device.awaitState { state ->
            state.activeTab?.let { tab ->
                tab.url == url && tab.title == Fixtures.title(color) && !tab.loading &&
                    (placement != Placement.NEW_TAB || tab.id != previous)
            } == true
        }
        return url
    }

    /** The + path: focuses the one unused blank tab, which shows Home. */
    private fun openHome() {
        device.browser.newTab()
        device.awaitState { it.activeTab?.isHome == true }
    }

    private fun homeState() = runBlocking {
        home.state(device.browser.topSites(SensitiveUrls::looksSensitive, limit = Int.MAX_VALUE)).first()
    }

    private fun clearHome() = runBlocking {
        val state = homeState()
        state.pins.forEach { home.unpin(it.id) }
        state.hiddenSites.forEach { home.restoreSite(it) }
    }

    /** What the fake services recorded, newest last. */
    private fun serviceCalls(): List<JSONObject> =
        Fixtures.state().getJSONArray("log").let { log -> (0 until log.length()).map(log::getJSONObject) }

    private fun awaitPins(vararg titles: String) {
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) { homeState().pins.map { it.title } == titles.toList() }
    }

    /** A labeled Home tile: its name is its text when the tile merges, its parent otherwise. */
    private fun awaitTile(name: String) =
        (hasText(name) and hasClickAction() or (hasClickAction() and hasAnyDescendant(hasText(name)))).let { tile ->
            compose.waitUntilAtLeastOneExists(tile, Device.LOAD_TIMEOUT_MS)
            compose.onAllNodes(tile).onFirst()
        }

    private fun tapContinue(title: String) {
        val card = compose.activity.getString(R.string.home_continue_tab, title)
        compose.waitUntilAtLeastOneExists(hasContentDescription(card), Device.LOAD_TIMEOUT_MS)
        compose.onNodeWithContentDescription(card).performClick()
    }

    /** A dock action: worded labels match as text, icon labels as content description. */
    private fun dock(name: String) =
        (hasClickAction() and (hasText(name) or hasContentDescription(name))).let { action ->
            compose.waitUntilAtLeastOneExists(action, Device.LOAD_TIMEOUT_MS)
            compose.onAllNodes(action).onFirst()
        }

    private fun awaitText(text: String) {
        compose.waitUntilAtLeastOneExists(hasText(text), Device.LOAD_TIMEOUT_MS)
    }

    /** A row deep in the scrollable Edit sheet: waits for it, then brings it on screen. */
    private fun sheetRow(matcher: SemanticsMatcher) = matcher.let { row ->
        try {
            compose.waitUntilAtLeastOneExists(row, EDITOR_TIMEOUT_MS)
        } catch (failure: ComposeTimeoutException) {
            val targets = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().joinToString("\n") { it.config.toString() }
            throw AssertionError("Editor control missing: $row\n$targets", failure)
        }
        compose.onNode(row).performScrollTo()
    }

    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    private companion object {
        const val EDITOR_TIMEOUT_MS = 30_000L
        // Pages only these checks use, so no other check's history answers their queries.
        const val PINNED = 0x715f2e
        const val PIN_FIRST = 0x2e715f
        const val PIN_SECOND = 0x5f2e71
        const val ADDED = 0x47712e
        const val SENSITIVE_TAB = 0x864a2e
        const val SUGGESTED = 0x3c5a71
    }
}
