package ch.lkmc.combray.engine

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditDeletionTest {
    /**
     * A negative or oversized request is refused, not clipped: the IME must not be told an edit
     * succeeded when only part of it could be sent.
     */
    @Test
    fun oversizedAndNegativeDeletionsAreRefused() {
        assertNull(editDeletion(Int.MAX_VALUE, 0))
        assertNull(editDeletion(0, Int.MAX_VALUE))
        assertNull(editDeletion(-1, 0))
        assertNull(editDeletion(0, -1))
        assertNull(editDeletion(MAX_EDIT_CHARS + 1, 0))
        assertNull(editDeletion(0, MAX_EDIT_CHARS + 1))
    }

    /** An ordinary deletion is still sent, backspaces first, then forward-deletes. */
    @Test
    fun anOrdinaryDeletionSendsBackspacesThenForwardDeletes() {
        assertEquals(
            listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL),
            editDeletion(2, 1),
        )
        assertEquals(emptyList<Int>(), editDeletion(0, 0))
    }

    /** The bound itself is accepted, so a large but representable deletion still works. */
    @Test
    fun theBoundItselfIsAccepted() {
        val keys = editDeletion(MAX_EDIT_CHARS, MAX_EDIT_CHARS)
        assertEquals(MAX_EDIT_CHARS * 2, keys?.size)
    }
}
