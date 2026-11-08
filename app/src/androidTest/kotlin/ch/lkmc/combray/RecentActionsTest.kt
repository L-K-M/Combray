package ch.lkmc.combray

import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.WindowInsets
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.RecentItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recent regressions on the real app and engine: the local filter's Search action puts the
 * keyboard away without dropping the typed text, and a closed visit's Copy puts its exact
 * recorded address on the clipboard with the sensitive hint. Synthetic fixture pages only.
 */
@RunWith(AndroidJUnit4::class)
class RecentActionsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /**
     * The reference emulator has a hardware keyboard; Android then opens no soft keyboard unless
     * this setting asks it to. Captured and validated before the mutation, restored in [tearDown].
     */
    private var hardKeyboardSetting: String? = null

    @Before
    fun setUp() {
        val original = shell("settings get secure $SHOW_WITH_HARD_KEYBOARD").trim()
        check(original.isEmpty() || original == "null" || original == "0" || original == "1") {
            "unexpected $SHOW_WITH_HARD_KEYBOARD value: '$original'"
        }
        shell("settings put secure $SHOW_WITH_HARD_KEYBOARD 1")
        hardKeyboardSetting = original
        device.awaitEngine()
        device.closeAllTabs()
    }

    @After
    fun tearDown() {
        dismissRecent()
        hardKeyboardSetting?.let { original ->
            if (original.isEmpty() || original == "null") {
                shell("settings delete secure $SHOW_WITH_HARD_KEYBOARD")
            } else {
                shell("settings put secure $SHOW_WITH_HARD_KEYBOARD $original")
            }
        }
    }

    @Test
    fun searchPutsTheKeyboardAwayAndKeepsTheFilter() {
        val color = uniqueColor()
        val url = Fixtures.solid(color)
        val query = Fixtures.title(color)
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        device.awaitPage(url)

        openRecent()
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        awaitFilterFocused(true)
        // The IME must really be up before the Search action can put it away.
        awaitIme(visible = true)
        field.performTextInput(query)
        assertEquals(query, filterText())

        field.performImeAction()
        awaitFilterFocused(false)
        awaitIme(visible = false)
        assertEquals(query, filterText())
    }

    @Test
    fun copyPutsTheExactRecordedVisitAddressOnTheClipboard() {
        val color = uniqueColor()
        val url = Fixtures.solid(color)
        val title = Fixtures.title(color)
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        device.awaitPage(url)
        val tabId = device.browser.browserState.value.activeTab!!.id
        device.browser.closeTab(tabId)
        device.awaitState { it.activeTab == null }
        val visit = awaitVisit(url)
        assertEquals(url, visit.url)

        openRecent()
        compose.onNodeWithText(compose.activity.getString(R.string.recent_filter_all)).performClick()
        compose.onNodeWithText(title).assertIsDisplayed()
        val actionsLabel = compose.activity.getString(R.string.recent_row_actions, title)
        compose.onNodeWithContentDescription(actionsLabel).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.link_copy)).performClick()

        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        assertNotNull("the clipboard holds no clip", clip)
        val item = clip!!.getItemAt(0)
        assertEquals(visit.url, item.text.toString())
        assertEquals(compose.activity.getString(R.string.page_info_address), clip.description.label.toString())
        assertNotNull(clip.description.extras)
        assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    private fun openRecent() {
        device.dismissSystemDialogs()
        compose.onNodeWithContentDescription("Recent", substring = true).performClick()
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitVisit(url: String): RecentItem.Visit {
        val deadline = System.currentTimeMillis() + VISIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            device.recent().filterIsInstance<RecentItem.Visit>().firstOrNull { it.url == url }?.let { return it }
            Thread.sleep(FRAME_WAIT_MS)
        }
        throw AssertionError("no Recent visit for $url")
    }

    private fun filterText(): String =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun filterFocused(): Boolean =
        compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()

    private fun awaitFilterFocused(focused: Boolean) {
        val deadline = System.currentTimeMillis() + KEYBOARD_TIMEOUT_MS
        while (filterFocused() != focused && System.currentTimeMillis() < deadline) runUi(FRAME_WAIT_MS)
        assertEquals("filter focused", focused, filterFocused())
    }

    private fun awaitIme(visible: Boolean) {
        val deadline = System.currentTimeMillis() + KEYBOARD_TIMEOUT_MS
        while (filterImeVisible() != visible && System.currentTimeMillis() < deadline) runUi(FRAME_WAIT_MS)
        assertEquals("filter IME visible", visible, filterImeVisible())
    }

    /**
     * The IME of the Recent sheet's own dialog window, not the activity's: the sheet is a separate
     * window, so only its insets say whether the keyboard is up for the filter.
     */
    private fun filterImeVisible(): Boolean {
        val root = compose.onNode(hasSetTextAction()).fetchSemanticsNode().root
        val view = (root as? ViewRootForTest)?.view
            ?: throw AssertionError("the Recent filter has no Compose root view: $root")
        var visible = false
        instrumentation.runOnMainSync {
            visible = view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }
        return visible
    }

    private fun dismissRecent() {
        runCatching {
            repeat(DISMISS_ATTEMPTS) {
                if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()) return
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                runUi(DISMISS_WAIT_MS)
            }
        }
    }

    private fun runUi(durationMs: Long) {
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(FRAME_WAIT_MS)
        }
    }

    /** A color no other check uses, so its page and title are unique in this run's history. */
    private fun uniqueColor(): Int =
        (((System.currentTimeMillis() shr 4) xor System.nanoTime()) and 0xFFFFFFL or 0x100000L).toInt()

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object {
        const val SHOW_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard"
        const val VISIT_TIMEOUT_MS = 30_000L
        const val KEYBOARD_TIMEOUT_MS = 30_000L
        const val FRAME_WAIT_MS = 100L
        const val DISMISS_WAIT_MS = 500L
        const val DISMISS_ATTEMPTS = 2
    }
}
