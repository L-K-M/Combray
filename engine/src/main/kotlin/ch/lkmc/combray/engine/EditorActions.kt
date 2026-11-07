package ch.lkmc.combray.engine

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/**
 * The key an IME action button sends to the page. Next moves focus like Tab; every other action
 * submits like Enter, which is what a page's Go, Done, Search or Send button expects. Previous
 * cannot be told from Next without a modifier channel, so it stays with Enter.
 */
internal fun editorActionKey(actionCode: Int): Int =
    if (actionCode == EditorInfo.IME_ACTION_NEXT) KeyEvent.KEYCODE_TAB else KeyEvent.KEYCODE_ENTER
