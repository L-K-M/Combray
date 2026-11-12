package ch.lkmc.combray.engine

import android.app.Application
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Requests the page field's bridge cannot represent are refused, not acknowledged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = Application::class)
class EngineInputConnectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun connection(caret: Int? = null): EngineInputConnection {
        val surface = EngineSurfaceView(context) { true }
        surface.prepareKeyboard("text", contextId = 1)
        surface.updateCaret(1, caret)
        return surface.onCreateInputConnection(EditorInfo()) as EngineInputConnection?
            ?: error("a prepared field offers no connection")
    }

    @Test
    fun anOversizedOrNegativeDeletionIsRefusedWithoutSendingAKey() {
        val connection = connection()
        assertFalse(connection.deleteSurroundingText(Int.MAX_VALUE, 0))
        assertFalse(connection.deleteSurroundingText(0, Int.MAX_VALUE))
        assertFalse(connection.deleteSurroundingText(-1, 0))
        assertFalse(connection.deleteSurroundingText(0, -1))
    }

    @Test
    fun aCursorPositionThePageCannotPlaceIsRefused() {
        val connection = connection()
        assertFalse(connection.commitText("x", 0))
        assertFalse(connection.setComposingText("x", 0))
        assertFalse(connection.commitText("x", -1))
        assertFalse(connection.setComposingText("x", 2))
    }

    @Test
    fun codePointDeletionsDoNotUseTheFrameworksUnrelatedEditable() {
        val connection = connection()
        assertFalse(connection.deleteSurroundingTextInCodePoints(1, 0))
        assertFalse(connection.deleteSurroundingTextInCodePoints(0, 1))
    }

    @Test
    fun anUnsupportedEditorActionCannotSubmitTheForm() {
        val connection = connection()
        assertFalse(connection.performEditorAction(EditorInfo.IME_ACTION_PREVIOUS))
        assertFalse(connection.performEditorAction(Int.MAX_VALUE))
    }

    @Test
    fun anUnhandledImeKeyIsRefusedBeforeCompositionOrJni() {
        val connection = connection()
        assertFalse(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertFalse(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)))
    }

    @Test
    @Config(sdk = [35])
    fun anExplicitReplacementRangeCannotCommitAtTheCurrentCaretInstead() {
        assertFalse(connection().replaceText(0, 1, "replacement", 1, null))
    }

    @Test
    @Config(sdk = [35])
    fun contextSnapshotsAreNotFabricatedFromTheFrameworksEditable() {
        val connection = connection()
        assertNull(connection.getSurroundingText(10, 10, 0))
        assertNull(connection.takeSnapshot())
    }

    @Test
    @Config(sdk = [35])
    fun bootstrapMetadataUsesOnlyTheRealCaretAndCopiesNoFieldText() {
        val connection = connection(caret = 3)
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)!!
        assertEquals("", extracted.text)
        assertEquals(3, extracted.startOffset)
        assertEquals(0, extracted.selectionStart)
        assertEquals(0, extracted.selectionEnd)
        val surrounding = connection.getSurroundingText(1, 1, 0)!!
        assertEquals("", surrounding.text)
        assertEquals(3, surrounding.offset)
        assertEquals(0, surrounding.selectionStart)
        assertEquals(0, surrounding.selectionEnd)
        assertNull(connection.getExtractedText(ExtractedTextRequest(), InputConnection.GET_EXTRACTED_TEXT_MONITOR))
        assertNull(connection.takeSnapshot())
        assertNull(connection.getTextBeforeCursor(10, 0))
        assertThrows(IllegalArgumentException::class.java) { connection.getSurroundingText(-1, 0, 0) }
        connection.closeConnection()
        assertNull(connection.getSurroundingText(1, 1, 0))
        assertNull(connection.getExtractedText(ExtractedTextRequest(), 0))
    }

    @Test
    fun staleCaretCallbacksAndRetiredFieldsCannotSupplyBootstrapMetadata() {
        val surface = EngineSurfaceView(context) { true }
        surface.prepareKeyboard("text", contextId = 1)
        surface.updateCaret(1, 3)
        val old = surface.onCreateInputConnection(EditorInfo())!!
        surface.updateCaret(2, 99)
        assertEquals(3, old.getExtractedText(ExtractedTextRequest(), 0)?.startOffset)
        surface.prepareKeyboard("text", contextId = 2)
        assertNull(old.getExtractedText(ExtractedTextRequest(), 0))
        val current = surface.onCreateInputConnection(EditorInfo())!!
        surface.updateCaret(1, 99)
        assertNull(current.getExtractedText(ExtractedTextRequest(), 0))
        surface.updateCaret(2, 4)
        assertNotNull(current.getExtractedText(ExtractedTextRequest(), 0))
        assertEquals(4, current.getExtractedText(ExtractedTextRequest(), 0)?.startOffset)
        surface.updateCaret(2, null)
        assertNull(current.getExtractedText(ExtractedTextRequest(), 0))
        surface.hideKeyboard()
        surface.updateCaret(2, 99)
        assertNull(current.getExtractedText(ExtractedTextRequest(), 0))
    }

    @Test
    fun selectionRegionRequestsAreNotAcknowledged() {
        val connection = connection()
        assertFalse(connection.setSelection(0, 1))
        assertFalse(connection.setComposingRegion(0, 1))
        assertNull(connection.getSelectedText(0))
        assertNull(connection.getTextBeforeCursor(10, 0))
        assertNull(connection.getTextAfterCursor(10, 0))
    }
}
