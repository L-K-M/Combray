package ch.lkmc.combray

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.engine.EngineSurfaceView
import ch.lkmc.combray.engine.NativeEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android InputConnection calls, real Servo DOM updates, and native context fencing. */
@RunWith(AndroidJUnit4::class)
class EditorImeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var previousHardKeyboard = ""

    @Before fun setUp() {
        previousHardKeyboard = shell("settings get secure show_ime_with_hard_keyboard").trim()
        shell("settings put secure show_ime_with_hard_keyboard 1")
        device.awaitEngine()
        device.closeAllTabs()
    }

    @After fun tearDown() {
        if (previousHardKeyboard.isEmpty() || previousHardKeyboard == "null") {
            shell("settings delete secure show_ime_with_hard_keyboard")
        } else {
            shell("settings put secure show_ime_with_hard_keyboard $previousHardKeyboard")
        }
    }

    @Test fun compositionIsVisibleBeforeCommitAndEachUpdateReplacesIt() {
        openEditor()
        val (connection, info) = connection()
        assertTrue(info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
        assertEquals(EditorInfo.IME_ACTION_NONE, info.imeOptions and EditorInfo.IME_MASK_ACTION)
        onUi { assertTrue(connection.setComposingText("n", 1)) }
        awaitTitle("EDITOR:input:n:insertCompositionText")
        onUi { assertTrue(connection.setComposingText("ni", 1)) }
        awaitTitle("EDITOR:input:ni:insertCompositionText")
        onUi { assertTrue(connection.setComposingText("", 1)) }
        awaitTitle("EDITOR:input::insertCompositionText")
        onUi { assertTrue(connection.setComposingText("é🦊", 1)) }
        awaitTitle("EDITOR:input:é🦊:insertCompositionText")
        onUi { assertTrue(connection.commitText("été", 1)); connection.finishComposingText() }
        awaitTitle("EDITOR:input:été:insertCompositionText")
        onUi { assertTrue(connection.commitText("!", 1)) }
        awaitTitle("EDITOR:input:été!:insertText")
    }

    @Test fun aRetiredConnectionAndAnInvalidNativeContextCannotEditTheReplacementDocument() {
        openEditor()
        val oldConnection = connection().first
        onUi { oldConnection.setComposingText("old", 1) }
        awaitTitle("EDITOR:input:old:insertCompositionText")
        device.browser.reload()
        awaitTitle("EDITOR:ready")
        tapEditor()
        awaitTitle("EDITOR:focused")
        onUi {
            assertFalse(oldConnection.commitText("late", 1))
            assertFalse(oldConnection.setComposingText("late", 1))
            assertFalse(oldConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        }
        NativeEngine.imeCommitInContext(Long.MAX_VALUE, "late")
        NativeEngine.keyInContext(NativeEngine.KEY_PRESS, KeyEvent.KEYCODE_ENTER, 0, 0, Long.MAX_VALUE)
        runUi(500)
        assertEquals("EDITOR:focused", device.browser.browserState.value.activeTab?.title)
        val replacement = connection().first
        onUi { assertTrue(replacement.commitText("new", 1)) }
        awaitTitle("EDITOR:input:new:insertText")
    }

    @Test fun shiftArrowReplacesOnlyTheSelectedCharacter() {
        openEditor()
        val connection = connection().first
        onUi { connection.commitText("abc", 1) }
        awaitTitle("EDITOR:input:abc:insertText")
        onUi {
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                connection.sendKeyEvent(KeyEvent(0, 0, action, KeyEvent.KEYCODE_DPAD_LEFT, 0, KeyEvent.META_SHIFT_ON))
            }
            connection.commitText("X", 1)
        }
        awaitTitle("EDITOR:input:abX:insertText")
    }

    private fun openEditor() {
        device.browser.openUrl("${Fixtures.BASE}/contenteditable.html", Placement.CURRENT_TAB)
        device.awaitState { it.activeTab?.title == "EDITOR:ready" && it.activeTab?.loading == false }
        val deadline = SystemClock.uptimeMillis() + Device.SCREEN_TIMEOUT_MS
        while (!device.matches(device.screenColor(0.05f, 0.5f), 0x1f6f78) && SystemClock.uptimeMillis() < deadline) {
            runUi(100)
        }
        assertTrue("editor was not painted", device.matches(device.screenColor(0.05f, 0.5f), 0x1f6f78))
        tapEditor()
        awaitTitle("EDITOR:focused")
        val keyboardDeadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!keyboardVisible() && SystemClock.uptimeMillis() < keyboardDeadline) runUi(100)
        assertTrue("editor keyboard was not shown", keyboardVisible())
    }

    private fun tapEditor() {
        device.dismissSystemDialogs()
        val metrics = compose.activity.resources.displayMetrics
        val time = SystemClock.uptimeMillis()
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.2f
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun connection(): Pair<InputConnection, EditorInfo> {
        var connection: InputConnection? = null
        val info = EditorInfo()
        onUi { connection = findSurface(compose.activity.window.decorView)?.onCreateInputConnection(info) }
        assertNotNull("engine surface supplied no input connection", connection)
        return connection!! to info
    }

    private fun findSurface(view: View): EngineSurfaceView? {
        if (view is EngineSurfaceView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) findSurface(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun keyboardVisible(): Boolean {
        var visible = false
        onUi { visible = compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        return visible
    }

    private fun awaitTitle(title: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (device.browser.browserState.value.activeTab?.title != title && SystemClock.uptimeMillis() < deadline) runUi(100)
        assertEquals(title, device.browser.browserState.value.activeTab?.title)
    }

    private fun runUi(durationMs: Long) {
        val deadline = SystemClock.uptimeMillis() + durationMs
        while (SystemClock.uptimeMillis() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(20)
        }
    }

    private fun onUi(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object { const val TIMEOUT_MS = 30_000L }
}
