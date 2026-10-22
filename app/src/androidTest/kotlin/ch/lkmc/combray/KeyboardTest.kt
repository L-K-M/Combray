package ch.lkmc.combray

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keyboard for page fields on the real engine: a field the page focuses on load waits for
 * the user's tap, a keyboard the user closed reopens on the next tap, and a form sent with
 * Enter takes the keyboard away with its page.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var hardKeyboardSetting = ""

    @Before
    fun setUp() {
        // The reference emulator has a hardware keyboard; Android then opens no soft keyboard
        // unless this setting asks it to.
        hardKeyboardSetting = shell("settings get secure $SHOW_WITH_HARD_KEYBOARD").trim()
        shell("settings put secure $SHOW_WITH_HARD_KEYBOARD 1")
        device.awaitEngine()
        device.closeAllTabs()
    }

    @After
    fun tearDown() {
        if (hardKeyboardSetting == "null" || hardKeyboardSetting.isEmpty()) {
            shell("settings delete secure $SHOW_WITH_HARD_KEYBOARD")
        } else {
            shell("settings put secure $SHOW_WITH_HARD_KEYBOARD $hardKeyboardSetting")
        }
    }

    @Test
    fun aFieldThePageFocusesOnLoadOpensTheKeyboardOnlyWhenTapped() {
        openFocusedField()
        // Time for a keyboard opened too early to appear.
        runUi(SETTLE_MS)
        assertFalse("the keyboard opened before the user touched the page", keyboardVisible())

        tapField()
        awaitKeyboard(visible = true)
    }

    @Test
    fun aKeyboardTheUserClosedReopensWhenTheFieldIsTappedAgain() {
        openFocusedField()
        tapField()
        awaitKeyboard(visible = true)

        shell("input keyevent KEYCODE_BACK")
        awaitKeyboard(visible = false)
        // The page heard of it: the field gave up focus, so focusing it again asks for a keyboard.
        awaitTitle(BLURRED_TITLE)
        // Back closed the keyboard only: the page stays.
        assertEquals(AUTOFOCUS_URL, device.browser.browserState.value.activeTab?.url)

        tapField()
        awaitKeyboard(visible = true)
        awaitTitle(FOCUSED_TITLE)

        // The reopened keyboard is the field's too: closing it again reaches the page.
        shell("input keyevent KEYCODE_BACK")
        awaitKeyboard(visible = false)
        awaitTitle(BLURRED_TITLE)
    }

    @Test
    fun sendingAFormWithEnterClosesTheKeyboard() {
        openFocusedField()
        tapField()
        awaitKeyboard(visible = true)

        instrumentation.sendStringSync("abc")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        device.awaitState { it.activeTab?.title == "ECHO:abc" }
        awaitKeyboard(visible = false)
    }

    /** Opens the page whose search field focuses itself on load and waits until it is painted. */
    private fun openFocusedField() {
        device.browser.openUrl(AUTOFOCUS_URL, Placement.CURRENT_TAB)
        device.awaitState { it.activeTab?.title == FOCUSED_TITLE && it.activeTab?.loading == false }
        val deadline = System.currentTimeMillis() + Device.SCREEN_TIMEOUT_MS
        while (!device.matches(device.screenColor(MARGIN_X, 0.5f), MARGIN) && System.currentTimeMillis() < deadline) {
            device.dismissSystemDialogs()
            Thread.sleep(1_000)
        }
        assertTrue("field page not painted", device.matches(device.screenColor(MARGIN_X, 0.5f), MARGIN))
    }

    /** A tap in the middle of the page area, where the field is. */
    private fun tapField() {
        device.dismissSystemDialogs()
        val metrics = compose.activity.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.3f
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + TAP_MS, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun keyboardVisible(): Boolean {
        var visible = false
        instrumentation.runOnMainSync {
            visible = compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }
        return visible
    }

    /**
     * Lets the app run for [durationMs]. Under the Compose test rule the UI recomposes, and acts
     * on keyboard requests and keyboard visibility, only when the test advances its clock.
     */
    private fun runUi(durationMs: Long) {
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(FRAME_WAIT_MS)
        }
    }

    private fun awaitTitle(title: String) {
        val deadline = System.currentTimeMillis() + KEYBOARD_TIMEOUT_MS
        while (device.browser.browserState.value.activeTab?.title != title && System.currentTimeMillis() < deadline) runUi(FRAME_WAIT_MS)
        assertEquals(title, device.browser.browserState.value.activeTab?.title)
    }

    private fun awaitKeyboard(visible: Boolean) {
        val deadline = System.currentTimeMillis() + KEYBOARD_TIMEOUT_MS
        while (keyboardVisible() != visible && System.currentTimeMillis() < deadline) runUi(FRAME_WAIT_MS)
        assertEquals("keyboard visible", visible, keyboardVisible())
    }

    private fun shell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private companion object {
        const val AUTOFOCUS_URL = "${Fixtures.BASE}/autofocus.html"
        const val FOCUSED_TITLE = "AUTOFOCUS:focused"
        const val BLURRED_TITLE = "AUTOFOCUS:blurred"
        const val SHOW_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard"

        /** The control pages' left margin colour. */
        const val MARGIN = 0x1f6f78
        const val MARGIN_X = 0.05f
        const val TAP_MS = 80L
        const val SETTLE_MS = 3_000L
        const val FRAME_WAIT_MS = 100L
        const val KEYBOARD_TIMEOUT_MS = 30_000L
    }
}
