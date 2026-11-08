package ch.lkmc.combray.engine

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/**
 * The key a supported IME action sends to the page. Next moves focus like Tab; ordinary
 * submit/newline actions use Enter. Previous is not mapped here, and must not submit a form
 * instead of moving backwards. Unknown actions are refused as well.
 */
internal fun editorActionKey(actionCode: Int): Int? = when (actionCode) {
    EditorInfo.IME_ACTION_NEXT -> KeyEvent.KEYCODE_TAB
    EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE,
    EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_DONE,
    EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND -> KeyEvent.KEYCODE_ENTER
    else -> null
}
