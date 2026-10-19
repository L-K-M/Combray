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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Page controls on the real engine: a `<select>` answered through the app's dialog, script
 * dialogs whose answers reach the page, and a file upload Servo reads from a staged path.
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

    private fun awaitText(text: String) =
        compose.waitUntil(DIALOG_TIMEOUT_MS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun tapPage() {
        device.dismissSystemDialogs()
        val metrics = compose.activity.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.4f
        val down = SystemClock.uptimeMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private companion object {
        /** The control pages' left margin colour. */
        const val MARGIN = 0x1f6f78
        const val MARGIN_X = 0.05f
        const val DIALOG_TIMEOUT_MS = 30_000L
    }
}
