package ch.lkmc.combray

import android.content.ClipData
import android.content.ClipboardManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.engine.NativeEngine
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

    @Test
    fun aClosedShadowPasswordFieldOpensTheKeyboardAndAcceptsImeText() {
        openFocusedField("shadow-input", "SHADOW:focused")
        runUi(SETTLE_MS)
        assertFalse("autofocus opened the keyboard", keyboardVisible())

        tapField()
        awaitTitle("SHADOW:tapped")
        awaitKeyboard(visible = true)
        // The same commit the soft keyboard sends, including non-ASCII characters.
        NativeEngine.imeCommit("été")
        awaitTitle("SHADOW:input:été:0")

        shell("input keyevent KEYCODE_BACK")
        awaitKeyboard(visible = false)
        awaitTitle("SHADOW:blurred")
        tapField()
        awaitKeyboard(visible = true)
    }

    @Test
    fun aLongPressPastesClipboardTextIntoAClosedShadowPasswordField() = pasteIntoShadowField(KeyboardBeforePaste.CLOSED)

    @Test
    fun pastingWithTheKeyboardOpenKeepsTheFieldUsable() = pasteIntoShadowField(KeyboardBeforePaste.OPEN)

    private fun pasteIntoShadowField(keyboardBefore: KeyboardBeforePaste) {
        openFocusedField("shadow-input", "SHADOW:focused")
        if (keyboardBefore == KeyboardBeforePaste.OPEN) {
            tapField()
            awaitTitle("SHADOW:tapped")
            awaitKeyboard(visible = true)
        }
        val pasteLabel = compose.activity.getString(android.R.string.paste)
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        var previous: ClipData? = null
        instrumentation.runOnMainSync {
            previous = clipboard.primaryClip
            clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "Ada été"))
        }
        try {
            tapField(LONG_PRESS_MS)
            device.awaitState { it.pageControl != null }
            compose.waitUntil(KEYBOARD_TIMEOUT_MS) { compose.onAllNodesWithText(pasteLabel).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(pasteLabel).performClick()
            // Both the native paste event and its default insertion must reach the field.
            awaitTitle("SHADOW:input:Ada été:1")
            tapField()
            awaitTitle("SHADOW:tapped")
            awaitKeyboard(visible = true)
            NativeEngine.imeCommit("!")
            awaitTitle("SHADOW:input:Ada été!:1")
        } finally {
            instrumentation.runOnMainSync {
                previous?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
            }
        }
    }

    /** Opens the page whose search field focuses itself on load and waits until it is painted. */
    private fun openFocusedField(name: String = "autofocus", title: String = FOCUSED_TITLE) {
        device.browser.openUrl("${Fixtures.BASE}/$name.html", Placement.CURRENT_TAB)
        device.awaitState { it.activeTab?.title == title && it.activeTab?.loading == false }
        val deadline = System.currentTimeMillis() + Device.SCREEN_TIMEOUT_MS
        while (!device.matches(device.screenColor(MARGIN_X, 0.5f), MARGIN) && System.currentTimeMillis() < deadline) {
            device.dismissSystemDialogs()
            Thread.sleep(1_000)
        }
        assertTrue("field page not painted", device.matches(device.screenColor(MARGIN_X, 0.5f), MARGIN))
    }

    /** A tap in the middle of the page area, where the field is. */
    private fun tapField(durationMs: Long = TAP_MS) {
        device.dismissSystemDialogs()
        val metrics = compose.activity.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.2f
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        if (durationMs > TAP_MS) runUi(durationMs)
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + durationMs, MotionEvent.ACTION_UP, x, y, 0))
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

    private enum class KeyboardBeforePaste { CLOSED, OPEN }

    private companion object {
        const val AUTOFOCUS_URL = "${Fixtures.BASE}/autofocus.html"
        const val FOCUSED_TITLE = "AUTOFOCUS:focused"
        const val BLURRED_TITLE = "AUTOFOCUS:blurred"
        const val SHOW_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard"

        /** The control pages' left margin colour. */
        const val MARGIN = 0x1f6f78
        const val MARGIN_X = 0.05f
        const val TAP_MS = 80L
        const val LONG_PRESS_MS = 1_500L
        const val SETTLE_MS = 3_000L
        const val FRAME_WAIT_MS = 100L
        const val KEYBOARD_TIMEOUT_MS = 30_000L
    }
}
