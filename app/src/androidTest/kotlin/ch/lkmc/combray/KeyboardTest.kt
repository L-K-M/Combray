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
import org.json.JSONObject
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
    fun aRichTextEditorOpensTheKeyboardAndTakesTyping() {
        val key = editorKey("plain")
        openFocusedField("contenteditable?k=$key", "EDITOR:ready")
        awaitEditorReports(key, 1)
        tapField()
        awaitTitle("EDITOR:focused")
        awaitKeyboard(visible = true)

        // What the soft keyboard sends: committed text, Backspace, and Enter for a new line.
        NativeEngine.imeCommit("été")
        awaitTitle("EDITOR:input:été:insertText")
        assertInputPair(key, "insertText", "été", target = "editor")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL)
        awaitTitle("EDITOR:input:ét:deleteContentBackward")
        assertInputPair(key, "deleteContentBackward", null, target = "editor")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        awaitTitle("EDITOR:input:ét:insertParagraph")
        val enter = assertInputPair(key, "insertParagraph", null, target = "editor")
        // Enter inserted a real paragraph: a new div after the text, with the caret inside it.
        assertEquals("ét<div><br></div>", enter.getJSONObject("dom").getString("html"))
        assertEquals("div[<br>]@0", enter.getJSONObject("selection").getString("anchor"))
        NativeEngine.imeCommit("b")
        awaitTitle("EDITOR:input:étb:insertText")
        val typed = assertInputPair(key, "insertText", "b", target = "editor")
        // The typed text landed in the new paragraph, not at the end of the old line.
        assertEquals("ét<div>b<br></div>", typed.getJSONObject("dom").getString("html"))
        assertEquals("t:b@1", typed.getJSONObject("selection").getString("anchor"))

        shell("input keyevent KEYCODE_BACK")
        awaitKeyboard(visible = false)
        awaitTitle("EDITOR:blurred")
    }

    @Test
    fun cancelingTheEnterBeforeinputLeavesTheEditorUnchanged() {
        val key = editorKey("cancel")
        openFocusedField("contenteditable?k=$key&cancel=insertParagraph", "EDITOR:ready")
        awaitEditorReports(key, 1)
        tapField()
        awaitTitle("EDITOR:focused")
        awaitKeyboard(visible = true)

        NativeEngine.imeCommit("é")
        awaitTitle("EDITOR:input:é:insertText")
        assertInputPair(key, "insertText", "é", target = "editor")

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        val canceled = awaitEditorReport(key, "beforeinput", "insertParagraph", null)
        assertTrue("beforeinput cancelable", canceled.optBoolean("cancelable"))
        assertTrue("beforeinput defaultPrevented", canceled.optBoolean("defaultPrevented"))
        assertEquals("é", canceled.getJSONObject("dom").getString("html"))
        assertEquals("t:é@1", canceled.getJSONObject("selection").getString("anchor"))
        // The canceled default action inserted nothing: no input event and no title change.
        runUi(SETTLE_MS)
        assertEquals("EDITOR:input:é:insertText", device.browser.browserState.value.activeTab?.title)
        assertTrue(
            "no input event followed the canceled beforeinput",
            editorReports(key).none {
                it.optString("phase") == "input" && it.optString("inputType") == "insertParagraph"
            }
        )

        // The editor still takes text: the cancel blocked only the paragraph.
        NativeEngine.imeCommit("b")
        awaitTitle("EDITOR:input:éb:insertText")
        val typed = assertInputPair(key, "insertText", "b", target = "editor")
        assertEquals("éb", typed.getJSONObject("dom").getString("html"))

        shell("input keyevent KEYCODE_BACK")
        awaitKeyboard(visible = false)
        awaitTitle("EDITOR:blurred")
    }

    @Test
    fun keysSentWhileTheEditorIsUnfocusedDoNotReachIt() {
        val key = editorKey("unfocused")
        openFocusedField("contenteditable?k=$key", "EDITOR:ready")
        val ready = awaitEditorReports(key, 1).single()
        assertEquals("", ready.getJSONObject("dom").getString("html"))

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_B)
        NativeEngine.imeCommit("x")
        runUi(SETTLE_MS)

        assertEquals("EDITOR:ready", device.browser.browserState.value.activeTab?.title)
        val reports = editorReports(key)
        assertEquals(listOf("ready"), reports.map { it.optString("phase") })
        assertEquals("", reports.single().getJSONObject("dom").getString("html"))

        // The silence is not a broken reporter: a focused editor still reports.
        tapField()
        awaitTitle("EDITOR:focused")
        NativeEngine.imeCommit("ok")
        awaitTitle("EDITOR:input:ok:insertText")
        assertInputPair(key, "insertText", "ok", target = "editor")
    }

    @Test
    fun anEditingHostNestedInANonEditableRegionTakesTypingAndEnter() {
        val key = editorKey("nested")
        openFocusedField("nested-editable?k=$key", "NESTED:ready")
        val ready = awaitEditorReports(key, 1).single()
        assertEquals("false", ready.getString("outerAttribute"))
        assertFalse(ready.getBoolean("outerEditable"))
        assertTrue(ready.getBoolean("innerEditable"))
        assertEquals("", ready.getJSONObject("dom").getString("html"))

        tapField()
        awaitTitle("NESTED:focused")
        awaitKeyboard(visible = true)

        NativeEngine.imeCommit("é")
        awaitTitle("NESTED:input:é:insertText")
        assertInputPair(key, "insertText", "é", target = "inner")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        awaitTitle("NESTED:input:é:insertParagraph")
        val enter = assertInputPair(key, "insertParagraph", null, target = "inner")
        assertEquals("é<div><br></div>", enter.getJSONObject("dom").getString("html"))
        assertEquals("div[<br>]@0", enter.getJSONObject("selection").getString("anchor"))
        NativeEngine.imeCommit("b")
        awaitTitle("NESTED:input:éb:insertText")
        val typed = assertInputPair(key, "insertText", "b", target = "inner")
        assertEquals("é<div>b<br></div>", typed.getJSONObject("dom").getString("html"))
        assertEquals("t:b@1", typed.getJSONObject("selection").getString("anchor"))
        // The non-editable region stayed non-editable.
        assertEquals("false", typed.getString("outerAttribute"))
        assertFalse(typed.getBoolean("outerEditable"))
        assertTrue(typed.getBoolean("innerEditable"))

        shell("input keyevent KEYCODE_BACK")
        awaitKeyboard(visible = false)
        awaitTitle("NESTED:blurred")
    }

    @Test
    fun aRichEditorInsideAClosedShadowRootTakesTypingAndEnter() {
        val key = editorKey("shadow")
        openFocusedField("shadow-editor?k=$key", "SHADOWEDITOR:ready")
        val ready = awaitEditorReports(key, 1).single()
        assertTrue(ready.getBoolean("shadowRoot"))
        assertEquals("closed", ready.getString("rootMode"))
        assertEquals("host", ready.getString("hostId"))
        assertTrue(ready.getBoolean("encapsulated"))

        tapField()
        awaitTitle("SHADOWEDITOR:focused")
        awaitKeyboard(visible = true)

        NativeEngine.imeCommit("é")
        awaitTitle("SHADOWEDITOR:input:é:insertText")
        assertInputPair(key, "insertText", "é", target = "editor")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        awaitTitle("SHADOWEDITOR:input:é:insertParagraph")
        val enter = assertInputPair(key, "insertParagraph", null, target = "editor")
        assertEquals("é<div><br></div>", enter.getJSONObject("dom").getString("html"))
        assertEquals("div[<br>]@0", enter.getJSONObject("selection").getString("anchor"))
        NativeEngine.imeCommit("b")
        awaitTitle("SHADOWEDITOR:input:éb:insertText")
        val typed = assertInputPair(key, "insertText", "b", target = "editor")
        assertEquals("é<div>b<br></div>", typed.getJSONObject("dom").getString("html"))
        assertEquals("t:b@1", typed.getJSONObject("selection").getString("anchor"))

        shell("input keyevent KEYCODE_BACK")
        awaitKeyboard(visible = false)
        awaitTitle("SHADOWEDITOR:blurred")
    }

    @Test
    fun aLongPressPastesClipboardTextIntoAClosedShadowPasswordField() = pasteIntoShadowField(KeyboardBeforePaste.CLOSED)

    @Test
    fun pastingWithTheKeyboardOpenKeepsTheFieldUsable() = pasteIntoShadowField(KeyboardBeforePaste.OPEN)

    @Test
    fun backDismissesThePasteMenuWithoutLeavingThePage() {
        openFocusedField("shadow-input", "SHADOW:focused")
        tapField(LONG_PRESS_MS)
        device.awaitState { it.pageControl != null }
        val pasteLabel = compose.activity.getString(android.R.string.paste)
        compose.waitUntil(KEYBOARD_TIMEOUT_MS) { compose.onAllNodesWithText(pasteLabel).fetchSemanticsNodes().isNotEmpty() }
        shell("input keyevent KEYCODE_BACK")
        runUi(SETTLE_MS)
        instrumentation.runOnMainSync {
            assertTrue("Back left the browser", compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        }
        device.awaitState { it.pageControl == null }
        assertEquals("${Fixtures.BASE}/shadow-input.html", device.browser.browserState.value.activeTab?.url)
    }

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

    /** A page-reported editor event key, unique per test so an earlier run cannot answer. */
    private fun editorKey(name: String) = "$name${System.currentTimeMillis()}"

    /** The page's editor reports for [key], in the order the fixture server stored them. */
    private fun editorReports(key: String): List<JSONObject> {
        val reports = Fixtures.state().optJSONObject("editor")?.optJSONArray(key) ?: return emptyList()
        return (0 until reports.length()).map(reports::getJSONObject)
    }

    private fun awaitEditorReports(key: String, count: Int): List<JSONObject> {
        val deadline = System.currentTimeMillis() + KEYBOARD_TIMEOUT_MS
        var reports = editorReports(key)
        while (reports.size < count && System.currentTimeMillis() < deadline) {
            runUi(FRAME_WAIT_MS)
            reports = editorReports(key)
        }
        assertTrue("expected $count editor reports for $key, saw ${reports.size}", reports.size >= count)
        return reports
    }

    private fun awaitEditorReport(key: String, phase: String, inputType: String, data: String?): JSONObject {
        val deadline = System.currentTimeMillis() + KEYBOARD_TIMEOUT_MS
        while (true) {
            editorReports(key).lastOrNull {
                it.optString("phase") == phase &&
                    it.optString("inputType") == inputType &&
                    (if (data == null) it.isNull("data") else it.optString("data") == data)
            }?.let { return it }
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "no $phase $inputType report with data $data for $key among " +
                        editorReports(key).map { "${it.optString("phase")}/${it.optString("inputType")}" }
                )
            }
            runUi(FRAME_WAIT_MS)
        }
    }

    /**
     * Asserts the exact beforeinput and input payloads for one editing action; returns the input
     * report, which carries the DOM and selection after the action.
     */
    private fun assertInputPair(key: String, inputType: String, data: String?, target: String): JSONObject {
        val before = awaitEditorReport(key, "beforeinput", inputType, data)
        val input = awaitEditorReport(key, "input", inputType, data)
        for ((phase, report) in listOf("beforeinput" to before, "input" to input)) {
            assertEquals("$phase target", target, report.optString("target"))
            assertEquals("$phase isComposing", false, report.optBoolean("isComposing"))
            assertTrue("$phase dataTransfer", report.isNull("dataTransfer"))
            assertTrue("$phase isTrusted", report.getBoolean("isTrusted"))
            assertTrue("$phase composed", report.getBoolean("composed"))
        }
        assertTrue("beforeinput cancelable", before.optBoolean("cancelable"))
        assertFalse("beforeinput defaultPrevented", before.optBoolean("defaultPrevented"))
        assertFalse("input cancelable", input.optBoolean("cancelable"))
        assertEquals("input targetRanges", 0, input.getInt("targetRanges"))
        val selection = input.getJSONObject("selection")
        assertTrue("input left a collapsed caret", selection.getBoolean("collapsed"))
        assertEquals(selection.getString("anchor"), selection.getString("focus"))
        return input
    }

    /** Opens the page whose search field focuses itself on load and waits until it is painted. */
    private fun openFocusedField(name: String = "autofocus", title: String = FOCUSED_TITLE) {
        val path = name.substringBefore('?')
        val query = name.substringAfter('?', missingDelimiterValue = "").let { if (it.isEmpty()) "" else "?$it" }
        device.browser.openUrl("${Fixtures.BASE}/$path.html$query", Placement.CURRENT_TAB)
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
