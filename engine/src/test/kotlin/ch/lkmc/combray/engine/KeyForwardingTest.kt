package ch.lkmc.combray.engine

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The surface must not take volume, menu or other system keys away from Android. */
class KeyForwardingTest {
    @Test
    fun systemKeysStayWithAndroid() {
        listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SEARCH,
            // The launcher key, not the text-cursor Home: that is MOVE_HOME below.
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_F1,
            KeyEvent.KEYCODE_TV,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_ALT_LEFT,
        ).forEach { keyCode ->
            assertFalse("key $keyCode must not be forwarded", isPageKey(keyCode, unicodeChar = 0))
        }
    }

    @Test
    fun navigationAndEditingKeysReachThePage() {
        listOf(
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
        ).forEach { keyCode ->
            assertTrue("key $keyCode must be forwarded", isPageKey(keyCode, unicodeChar = 0))
        }
    }

    @Test
    fun printableCharactersReachThePage() {
        assertTrue(isPageKey(KeyEvent.KEYCODE_A, unicodeChar = 'a'.code))
        assertTrue(isPageKey(KeyEvent.KEYCODE_A, unicodeChar = 'A'.code))
        assertTrue(isPageKey(KeyEvent.KEYCODE_SPACE, unicodeChar = ' '.code))
        assertTrue(isPageKey(KeyEvent.KEYCODE_SPACE, unicodeChar = 0).not())
        // Shifted punctuation arrives with its own unicode, no modifier mapping needed.
        assertTrue(isPageKey(KeyEvent.KEYCODE_COMMA, unicodeChar = '<'.code))
    }
}
