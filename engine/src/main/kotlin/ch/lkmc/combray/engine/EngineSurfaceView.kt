package ch.lkmc.combray.engine

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * The one visible rendering surface. All tabs render into it; the engine shows only the
 * selected one. Surface loss (background, rotation) unbinds rendering without closing tabs.
 *
 * [engineRunning] gates every native call: without a started engine (or without the native
 * library) the view stays an empty surface instead of crashing.
 */
class EngineSurfaceView(
    context: Context,
    private val engineRunning: () -> Boolean,
) : SurfaceView(context), SurfaceHolder.Callback {

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!engineRunning()) return
        val frame = holder.surfaceFrame
        val width = if (width > 0) width else frame.width()
        val height = if (height > 0) height else frame.height()
        // Queued, not awaited: the engine reports surface_bound (or an error) when done.
        val queued = NativeEngine.surfaceCreated(holder.surface, width, height)
        Log.i(LOG_TAG, "surfaceCreated ${width}x$height queued=$queued")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!engineRunning()) return
        NativeEngine.surfaceChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!engineRunning()) return
        // Blocks until the engine released the surface, as SurfaceHolder.Callback requires.
        val released = NativeEngine.surfaceDestroyed()
        Log.i(LOG_TAG, "surfaceDestroyed released=$released")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!engineRunning()) return false
        requestFocus()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                sendPointer(event, event.actionIndex, NativeEngine.TOUCH_DOWN)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                sendPointer(event, event.actionIndex, NativeEngine.TOUCH_UP)
            MotionEvent.ACTION_CANCEL ->
                for (index in 0 until event.pointerCount) sendPointer(event, index, NativeEngine.TOUCH_CANCEL)
            MotionEvent.ACTION_MOVE ->
                // Every pointer may have moved, not only the one at actionIndex.
                for (index in 0 until event.pointerCount) sendPointer(event, index, NativeEngine.TOUCH_MOVE)
        }
        return true
    }

    private fun sendPointer(event: MotionEvent, index: Int, phase: Int) {
        NativeEngine.touch(phase, event.getPointerId(index), event.getX(index), event.getY(index))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = forwardKey(event, NativeEngine.KEY_PRESS)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = forwardKey(event, NativeEngine.KEY_RELEASE)

    private fun forwardKey(event: KeyEvent, action: Int): Boolean {
        // Back stays with Android so the Activity can handle navigation and dismissal.
        if (event.keyCode == KeyEvent.KEYCODE_BACK || !engineRunning()) return false
        NativeEngine.key(action, event.keyCode, event.unicodeChar)
        return true
    }

    /** Type of the focused page field ("password", "email", ...), from the engine's ime_shown. */
    private var fieldKind = TEXT

    override fun onCheckIsTextEditor(): Boolean = engineRunning()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!engineRunning()) return null
        outAttrs.inputType = when (fieldKind) {
            // Never suggestions or a learned dictionary for passwords.
            PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            "number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            "tel" -> InputType.TYPE_CLASS_PHONE
            else -> InputType.TYPE_CLASS_TEXT
        }
        var options = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        if (fieldKind == PASSWORD) options = options or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        if (fieldKind == "search") options = options or EditorInfo.IME_ACTION_SEARCH
        outAttrs.imeOptions = options
        return EngineInputConnection(this)
    }

    /** Shows the keyboard for a page field of [kind] (as reported by the engine). */
    fun showKeyboard(kind: String = TEXT) {
        val keyboard = context.getSystemService(InputMethodManager::class.java)
        requestFocus()
        if (kind != fieldKind) {
            fieldKind = kind
            keyboard.restartInput(this)
        }
        keyboard.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    private companion object {
        const val LOG_TAG = "CombrayEngine"
        const val TEXT = "text"
        const val PASSWORD = "password"
    }
}

/**
 * Minimal IME bridge: committed text becomes a composition end in the focused element;
 * deletions and editor actions become key events. A word being composed reaches the page when
 * the keyboard commits or finishes it (the page shows no composition preview); selection sync
 * with the page is not implemented.
 */
class EngineInputConnection(view: EngineSurfaceView) : BaseInputConnection(view, false) {
    /** The keyboard's current composition, not yet sent to the page. */
    private var composing = ""

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        composing = text.toString()
        return true
    }

    override fun finishComposingText(): Boolean {
        sendComposing()
        return true
    }

    /** Replaces the composition, as the IME contract defines. */
    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        composing = ""
        NativeEngine.imeCommit(text.toString())
        return true
    }

    override fun closeConnection() {
        sendComposing()
        super.closeConnection()
    }

    private fun sendComposing() {
        if (composing.isEmpty()) return
        NativeEngine.imeCommit(composing)
        composing = ""
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength) { tapKey(KeyEvent.KEYCODE_DEL) }
        repeat(afterLength) { tapKey(KeyEvent.KEYCODE_FORWARD_DEL) }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        val action = if (event.action == KeyEvent.ACTION_DOWN) NativeEngine.KEY_PRESS else NativeEngine.KEY_RELEASE
        NativeEngine.key(action, event.keyCode, event.unicodeChar)
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        tapKey(KeyEvent.KEYCODE_ENTER)
        return true
    }

    private fun tapKey(keyCode: Int) {
        NativeEngine.key(NativeEngine.KEY_PRESS, keyCode, 0)
        NativeEngine.key(NativeEngine.KEY_RELEASE, keyCode, 0)
    }
}
