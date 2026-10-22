package ch.lkmc.combray.engine

import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineKeysTest {
    /** Volume, media, headset, camera and call keys must reach Android, not the page. */
    @Test
    fun systemKeysStayWithAndroid() {
        val systemKeys = listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_FOCUS,
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ENDCALL,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_SEARCH,
            // The launcher key, not the text-cursor Home: that is MOVE_HOME.
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_F1,
            KeyEvent.KEYCODE_TV,
            // Bare modifiers: see the known limit on EngineKeys.
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_ALT_LEFT,
        )
        for (keyCode in systemKeys) {
            assertFalse("key code $keyCode", EngineKeys.handles(keyCode, NO_CHARACTER))
        }
    }

    @Test
    fun printableCharactersGoToThePage() {
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_A, 'a'.code))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_A, 'A'.code))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_SPACE, ' '.code))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_1, '1'.code))
        // Shifted punctuation arrives with its own character; no modifier mapping is needed.
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_COMMA, '<'.code))
        assertFalse(EngineKeys.handles(KeyEvent.KEYCODE_SPACE, NO_CHARACTER))
        // The character decides, whatever key produced it.
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_UNKNOWN, 'é'.code))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_UNKNOWN, EMOJI))
    }

    @Test
    fun editingAndNavigationKeysGoToThePage() {
        // Enter and Tab carry control characters ('\n', '\t'); their key codes still map.
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_ENTER, '\n'.code))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_TAB, '\t'.code))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_DPAD_UP, NO_CHARACTER))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_DPAD_DOWN, NO_CHARACTER))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_DPAD_LEFT, NO_CHARACTER))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_DPAD_RIGHT, NO_CHARACTER))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_DEL, NO_CHARACTER))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_FORWARD_DEL, NO_CHARACTER))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_ESCAPE, NO_CHARACTER))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_MOVE_HOME, NO_CHARACTER))
        assertTrue(EngineKeys.handles(KeyEvent.KEYCODE_MOVE_END, NO_CHARACTER))
    }

    /** servo_key drops control characters and values that are not Unicode scalar values. */
    @Test
    fun charactersServoDropsDoNotMakeAKeyHandled() {
        // servo_key filters NUL explicitly: it is no printable character here either.
        assertFalse(EngineKeys.handles(KeyEvent.KEYCODE_UNKNOWN, NO_CHARACTER))
        assertFalse(EngineKeys.handles(KeyEvent.KEYCODE_NUMPAD_ENTER, '\n'.code))
        assertFalse(EngineKeys.handles(KeyEvent.KEYCODE_UNKNOWN, DELETE_CONTROL))
        assertFalse(EngineKeys.handles(KeyEvent.KEYCODE_UNKNOWN, C1_CONTROL))
        assertFalse(EngineKeys.handles(KeyEvent.KEYCODE_UNKNOWN, SURROGATE))
        assertFalse(EngineKeys.handles(KeyEvent.KEYCODE_UNKNOWN, BEYOND_UNICODE))
        assertFalse(EngineKeys.handles(KeyEvent.KEYCODE_UNKNOWN, DEAD_ACUTE_ACCENT))
    }

    /** Guards the mirror: exactly the key codes servo_key in native/src/engine.rs names. */
    @Test
    fun namedKeyCodesMatchServoKey() {
        val handled = (0..HIGHEST_PROBED_KEY_CODE).filter { EngineKeys.handles(it, NO_CHARACTER) }.toSet()
        assertEquals(SERVO_KEY_NAMED_CODES, handled)
    }

    private companion object {
        const val NO_CHARACTER = 0
        const val EMOJI = 0x1F600
        const val DELETE_CONTROL = 0x7F
        const val C1_CONTROL = 0x85
        const val SURROGATE = 0xD800
        const val BEYOND_UNICODE = 0x110000

        /** A dead key's unicodeChar carries the COMBINING_ACCENT flag, which makes it negative. */
        const val DEAD_ACUTE_ACCENT = KeyCharacterMap.COMBINING_ACCENT or 0x301

        /** Above every KEYCODE_* constant (the highest is 340 in API 37). */
        const val HIGHEST_PROBED_KEY_CODE = 1_000

        /** The literal codes in servo_key's match, copied so a one-sided edit fails here. */
        val SERVO_KEY_NAMED_CODES = setOf(19, 20, 21, 22, 61, 66, 67, 111, 112, 122, 123)
    }
}
