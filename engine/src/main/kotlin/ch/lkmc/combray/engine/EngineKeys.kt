package ch.lkmc.combray.engine

import android.view.KeyEvent

/**
 * Which Android keys the engine turns into DOM keys. Mirrors `servo_key` in
 * `native/src/engine.rs`: change both together. The page surface consumes only these, so
 * every other key (volume, media, headset, camera, call, shortcuts Servo ignores) falls
 * through to Android instead of being dropped.
 */
internal object EngineKeys {
    /** Keys servo_key maps by code because they produce no printable character. */
    private val namedKeyCodes = setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DEL,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_FORWARD_DEL,
        KeyEvent.KEYCODE_MOVE_HOME,
        KeyEvent.KEYCODE_MOVE_END,
    )

    private val surrogates = Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code

    /** [unicodeChar] is [KeyEvent.getUnicodeChar], as passed to [NativeEngine.key]. */
    fun handles(keyCode: Int, unicodeChar: Int): Boolean =
        isPrintable(unicodeChar) || keyCode in namedKeyCodes

    /**
     * Rust's `char::from_u32` accepts only Unicode scalar values (so not a dead key's negative
     * COMBINING_ACCENT value), and `char::is_control` is the Cc category, which is
     * [Character.isISOControl]. NUL is a control character too.
     */
    private fun isPrintable(unicodeChar: Int): Boolean =
        Character.isValidCodePoint(unicodeChar) &&
            unicodeChar !in surrogates &&
            !Character.isISOControl(unicodeChar)
}
