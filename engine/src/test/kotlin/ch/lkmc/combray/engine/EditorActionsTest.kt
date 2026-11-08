package ch.lkmc.combray.engine

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorActionsTest {
    /** The IME's Next action moves focus to the next field, like Tab. */
    @Test
    fun nextMovesFocusLikeTab() {
        assertEquals(KeyEvent.KEYCODE_TAB, editorActionKey(EditorInfo.IME_ACTION_NEXT))
    }

    /** Go, Done, Search, Send and ordinary newline/default actions use Enter. */
    @Test
    fun theOtherActionsSubmitLikeEnter() {
        for (action in listOf(
            EditorInfo.IME_ACTION_UNSPECIFIED,
            EditorInfo.IME_ACTION_NONE,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_SEARCH,
        )) {
            assertEquals("action $action", KeyEvent.KEYCODE_ENTER, editorActionKey(action))
        }
    }

    @Test
    fun unmappedActionsNeverBecomeEnter() {
        assertNull(editorActionKey(EditorInfo.IME_ACTION_PREVIOUS))
        assertNull(editorActionKey(Int.MAX_VALUE))
    }
}
