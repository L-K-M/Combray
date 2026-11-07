package ch.lkmc.combray.engine

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorActionsTest {
    /** The IME's Next action moves focus to the next field, like Tab. */
    @Test
    fun nextMovesFocusLikeTab() {
        assertEquals(KeyEvent.KEYCODE_TAB, editorActionKey(EditorInfo.IME_ACTION_NEXT))
    }

    /** Go, Done, Search, Send and the rest submit like Enter. */
    @Test
    fun theOtherActionsSubmitLikeEnter() {
        for (action in listOf(
            EditorInfo.IME_ACTION_NONE,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_PREVIOUS,
        )) {
            assertEquals("action $action", KeyEvent.KEYCODE_ENTER, editorActionKey(action))
        }
    }
}
