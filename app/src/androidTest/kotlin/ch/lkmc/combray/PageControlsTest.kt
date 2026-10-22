package ch.lkmc.combray

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.engine.ControlResponse
import ch.lkmc.combray.engine.PageControlContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Page controls on the real engine: a `<select>` answered through the app's dialog, script
 * dialogs whose answers reach the page, a file upload Servo reads from a staged path, and a
 * page's question before it is left.
 */
@RunWith(AndroidJUnit4::class)
class PageControlsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    /** Opens a control page and waits until it is painted, so a tap reaches the control. */
    private fun openControls(name: String, title: String) {
        device.browser.openUrl("${Fixtures.BASE}/$name.html", Placement.CURRENT_TAB)
        device.awaitState { it.activeTab?.title == title && it.activeTab?.loading == false }
        val deadline = System.currentTimeMillis() + Device.SCREEN_TIMEOUT_MS
        while (!device.matches(device.screenColor(MARGIN_X, 0.5f), MARGIN) && System.currentTimeMillis() < deadline) {
            device.dismissSystemDialogs()
            Thread.sleep(1_000)
        }
        assertTrue("control page not painted", device.matches(device.screenColor(MARGIN_X, 0.5f), MARGIN))
    }

    private fun awaitControl() = device.awaitState { it.pageControl != null }.pageControl!!

    @Test
    fun aSelectChoiceFromTheDialogReachesThePage() {
        openControls("select", "SELECT")
        tapPage()
        val control = awaitControl()
        val select = control.content as PageControlContent.Select
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"), select.options.map { it.label })
        assertEquals(listOf(null, "Later", "Later", "Later"), select.options.map { it.group })
        assertTrue(select.options.single { it.label == "Delta" }.disabled)

        awaitText("Charlie")
        compose.onNodeWithText("Charlie").performClick()
        device.awaitState { it.activeTab?.title == "SELECT:c" }
        assertEquals(null, device.browser.browserState.value.pageControl)
    }

    @Test
    fun scriptDialogsAreAnsweredInOrderAndNameThePage() {
        openControls("dialogs", "DIALOGS")
        tapPage()

        assertTrue(awaitControl().content is PageControlContent.Confirm)
        awaitText("Proceed?")
        compose.onNodeWithText("localhost says").assertExists()
        compose.onNodeWithText("OK").performClick()

        device.awaitState { it.pageControl?.content is PageControlContent.Prompt }
        awaitText("Name?")
        compose.onNode(hasSetTextAction()).performTextReplacement("Ada")
        compose.onNodeWithText("OK").performClick()

        device.awaitState { it.pageControl?.content is PageControlContent.Alert }
        awaitText("Done")
        compose.onNodeWithText("OK").performClick()

        device.awaitState { it.activeTab?.title == "DIALOGS:true:Ada" }
    }

    /**
     * Android's document picker is another app; the check answers with a file it staged itself,
     * the same way the app answers after copying the picked document, then closes the picker.
     */
    @Test
    fun anUploadedFileIsReadByThePage() {
        val staged = File(device.app.noBackupFilesDir, "uploads/check/hello.txt").apply {
            parentFile!!.mkdirs()
            writeText("hi from the check")
        }
        openControls("upload", "UPLOAD")
        tapPage()
        val control = awaitControl()
        assertEquals(PageControlContent.Files(multiple = false, extensions = listOf("txt")), control.content)

        device.browser.answerPageControl(control.id, ControlResponse.Files(listOf(staged.path)))
        device.awaitState { it.activeTab?.title == "FILE:hello.txt:hi from the check" }
        shell("input keyevent KEYCODE_BACK")
    }

    @Test
    fun aLongPressOnALinkOffersToOpenItInANewTab() {
        val opener = "${Fixtures.BASE}/opener.html"
        device.browser.openUrl(opener, Placement.CURRENT_TAB)
        device.awaitState { it.activeTab?.title == "OPENER" && it.activeTab?.loading == false }
        val seen = device.awaitScreen(Device.VIOLET)
        assertTrue("opener not painted", device.matches(seen, Device.VIOLET))

        pressPage(LONG_PRESS_MS)
        val menu = awaitControl().content as PageControlContent.LinkMenu
        assertEquals(Fixtures.solid(Device.BLUE), menu.link)
        // The long-press did not also follow the link.
        assertEquals(1, device.browser.browserState.value.openTabCount)

        awaitText("Open in new tab")
        compose.onNodeWithText("Open in new tab").performClick()
        device.awaitPage(Fixtures.solid(Device.BLUE))
        assertEquals(2, device.browser.browserState.value.openTabCount)
    }

    /**
     * One flow: cancelling shows the server's own 401 page, then signing in through the dialog
     * loads the protected page. Servo 0.6.0 keeps the answer in the page's URL
     * (`http://ada:lovelace@...`), not in its auth cache; the tab and Recent must not show it.
     */
    @Test
    fun aSignInFromTheDialogReachesTheServer() {
        val protected = "${Fixtures.BASE}/auth.html"
        device.browser.openUrl(protected, Placement.CURRENT_TAB)
        assertEquals(PageControlContent.SignIn(Fixtures.BASE, proxy = false), awaitControl().content)
        awaitText("Sign in to ${Fixtures.BASE}")
        compose.onNodeWithText("Cancel").performClick()
        device.awaitState { it.activeTab?.title == "AUTH-REQUIRED" && it.activeTab?.loading == false }

        device.browser.openUrl(protected, Placement.CURRENT_TAB)
        assertEquals(PageControlContent.SignIn(Fixtures.BASE, proxy = false), awaitControl().content)
        awaitText("Sign in to ${Fixtures.BASE}")
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("ada")
        fields[1].performTextReplacement("lovelace")
        compose.onNodeWithText("Sign in").performClick()
        device.awaitState { it.activeTab?.title == "AUTH:ada" }
        assertEquals(null, device.browser.browserState.value.pageControl)

        assertEquals(protected, device.browser.browserState.value.activeTab?.url)
        val recent = device.recent().mapNotNull { it.url }
        assertTrue(recent.toString(), protected in recent && recent.none { "ada:" in it || "ada@localhost" in it || "lovelace" in it })
    }

    /**
     * Servo 0.6.0 runs `beforeunload` only for navigations the page starts (links, forms,
     * `location`), reloads and `window.close()`, not for loads the app starts: the checks follow
     * the page's own link. Stay keeps the same document (its handler counted one question).
     */
    @Test
    fun aTouchedPageAsksBeforeItIsLeft() {
        openControls("leave", "LEAVE")
        // The tap that follows the link is the user's first interaction with the page.
        tapPage()
        assertEquals(PageControlContent.LeavePage, awaitControl().content)
        awaitText("Leave this page?")
        compose.onNodeWithText("Asked by localhost").assertExists()
        compose.onNodeWithText("Stay").performClick()

        device.awaitState { it.activeTab?.title == "LEAVE:1" && it.pageControl == null }
        Thread.sleep(SETTLE_MS)
        assertEquals(LEAVE_PAGE, device.browser.browserState.value.activeTab?.url)

        tapPage()
        assertEquals(PageControlContent.LeavePage, awaitControl().content)
        awaitText("Leave")
        compose.onNodeWithText("Leave").performClick()
        device.awaitPage(Fixtures.solid(Device.GREEN))
    }

    /** Browsers ask only after the user interacted with the page; this one follows its link by script. */
    @Test
    fun anUntouchedPageIsLeftWithoutAsking() {
        device.browser.openUrl("$LEAVE_PAGE?auto", Placement.CURRENT_TAB)
        var asked = false
        device.awaitState {
            asked = asked || it.pageControl != null
            it.activeTab?.url == Fixtures.solid(Device.GREEN) && it.activeTab?.loading == false
        }
        assertFalse("the page asked although nobody touched it", asked)
    }

    /** The page's scripts wait for the answer; unanswered, it is answered Stay and the page kept. */
    @Test
    fun anUnansweredQuestionKeepsThePage() {
        openControls("leave", "LEAVE")
        // From before the tap, so a slow dialog does not shorten the wait measured.
        val asked = System.currentTimeMillis()
        tapPage()
        assertEquals(PageControlContent.LeavePage, awaitControl().content)

        device.awaitState { it.pageControl == null }
        val waited = System.currentTimeMillis() - asked
        assertTrue("hidden after $waited ms", waited >= UNANSWERED_MIN_MS)
        Thread.sleep(SETTLE_MS)
        assertEquals(LEAVE_PAGE, device.browser.browserState.value.activeTab?.url)
    }

    private fun awaitText(text: String) =
        compose.waitUntil(DIALOG_TIMEOUT_MS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun tapPage() = pressPage(TAP_MS)

    private fun pressPage(durationMs: Long) {
        device.dismissSystemDialogs()
        val metrics = compose.activity.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.4f
        val down = SystemClock.uptimeMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        if (durationMs > TAP_MS) Thread.sleep(durationMs)
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + durationMs, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private companion object {
        const val LEAVE_PAGE = "${Fixtures.BASE}/leave.html"

        /** Time for a refused navigation to show if it happened after all. */
        const val SETTLE_MS = 3_000L

        /** Well within the engine's 30 s wait, allowing for the delay before the dialog shows. */
        const val UNANSWERED_MIN_MS = 20_000L

        /** The control pages' left margin colour. */
        const val MARGIN = 0x1f6f78
        const val MARGIN_X = 0.05f
        const val DIALOG_TIMEOUT_MS = 30_000L
        const val TAP_MS = 80L
        /** Well past Android's long-press timeout (400–500 ms). */
        const val LONG_PRESS_MS = 1_500L
    }
}
