package ch.lkmc.combray.engine

import android.app.Application
import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Requests the page field's bridge cannot represent are refused, not acknowledged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = Application::class)
class EngineInputConnectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun connection(): EngineInputConnection {
        val surface = EngineSurfaceView(context) { true }
        surface.prepareKeyboard("text", contextId = 1)
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
    fun selectionRegionRequestsAreNotAcknowledged() {
        val connection = connection()
        assertFalse(connection.setSelection(0, 1))
        assertFalse(connection.setComposingRegion(0, 1))
        assertNull(connection.getSelectedText(0))
        assertNull(connection.getTextBeforeCursor(10, 0))
        assertNull(connection.getTextAfterCursor(10, 0))
    }
}
