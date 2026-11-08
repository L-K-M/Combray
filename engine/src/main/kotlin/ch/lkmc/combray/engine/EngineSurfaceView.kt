package ch.lkmc.combray.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowInsets
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

    /**
     * A long-press becomes a secondary click (Servo's context menu). The touches already sent
     * are cancelled first, and the rest of that gesture stays with the app: otherwise the
     * lift would also count as a tap on the link.
     */
    private var longPressed = false
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPress = LongPressTracker(longPressTimeoutMs, ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private var pressPointer = 0
    private var pressX = 0f
    private var pressY = 0f
    private val scrollGesture = ScrollGestureTracker(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private var scrollPage: PageRef? = null
    private var onScroll: (ScrollEdge) -> Unit = {}
    private val longPressCheck = Runnable {
        // The engine may have stopped since the press.
        if (!engineRunning() || !longPress.timerFired(SystemClock.uptimeMillis())) return@Runnable
        longPressed = true
        // Felt the moment the press turns into a long-press, as elsewhere on Android: from here on
        // the finger no longer scrolls or taps. The system's touch feedback setting applies.
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        NativeEngine.touch(NativeEngine.TOUCH_CANCEL, pressPointer, pressX, pressY)
        NativeEngine.contextClick(pressX, pressY)
    }

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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(keyboardWatch)
    }

    override fun onDetachedFromWindow() {
        retireInputConnection()
        pageFieldActive = false
        removeCallbacks(longPressCheck)
        scrollGesture.release()
        onScroll = {}
        viewTreeObserver.removeOnGlobalLayoutListener(keyboardWatch)
        // The keyboard goes with the window (rotation recreates the Activity): the page must not
        // keep a field that thinks it has one, or a tap on that field could not reopen it.
        if (pageKeyboard.reset()) reportDismissal(Dismissal.WINDOW_GONE)
        super.onDetachedFromWindow()
    }

    /** Rebinding a document cancels an old gesture, so it cannot reveal the new page's button. */
    fun observeScroll(page: PageRef?, onScroll: (ScrollEdge) -> Unit) {
        if (page != scrollPage) {
            scrollGesture.release()
            // The old page's field is gone with it. Retire the connection so a delayed word or
            // key cannot reach a context the page no longer owns; the engine still rejects a
            // stale native context, but the IME gets no connection to call. A replacement field
            // prepares its own connection and context, so edits are never retargeted.
            retireInputConnection()
            pageFieldActive = false
        }
        scrollPage = page
        this.onScroll = onScroll
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!engineRunning()) return false
        requestFocus()
        trackLongPress(event)
        if (longPressed) return true
        trackScroll(event)
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

    private fun trackScroll(event: MotionEvent) {
        if (scrollPage == null) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> scrollGesture.press(event.x, event.y)
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) scrollGesture.move(event.x, event.y)?.let(onScroll)
            else -> scrollGesture.release()
        }
    }

    private fun trackLongPress(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                pressPointer = event.getPointerId(0)
                pressX = event.x
                pressY = event.y
                longPress.press(event.x, event.y, SystemClock.uptimeMillis())
                removeCallbacks(longPressCheck)
                postDelayed(longPressCheck, longPressTimeoutMs)
            }
            MotionEvent.ACTION_MOVE -> longPress.move(event.x, event.y)
            else -> longPress.release()
        }
    }

    private fun sendPointer(event: MotionEvent, index: Int, phase: Int) {
        NativeEngine.touch(phase, event.getPointerId(index), event.getX(index), event.getY(index))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = forwardKey(event, NativeEngine.KEY_PRESS)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = forwardKey(event, NativeEngine.KEY_RELEASE)

    private fun forwardKey(event: KeyEvent, action: Int): Boolean {
        // Back stays with Android so the Activity can handle navigation and dismissal.
        if (event.keyCode == KeyEvent.KEYCODE_BACK || !engineRunning()) return false
        // So do keys the engine would drop: volume, media and call keys must still work.
        if (!EngineKeys.handles(event.keyCode, event.unicodeChar)) return false
        if (action == NativeEngine.KEY_PRESS) inputConnection?.finishComposingText()
        NativeEngine.keyWithModifiers(action, event.keyCode, event.unicodeChar, event.metaState)
        return true
    }

    /** Type of the focused page field ("password", "email", ...), from the engine's ime_shown. */
    private var fieldKind = TEXT
    private var fieldMultiline = false
    private var fieldContext = 0L
    private var inputConnection: EngineInputConnection? = null

    /**
     * Whether a page field has focus, so [onCheckIsTextEditor] and the offered connection are
     * true only for a real field. Set before [useFieldKind] restarts input (restartInput
     * consults the flag), cleared when the field loses the keyboard, the window goes, or the
     * page changes.
     */
    private var pageFieldActive = false

    /** Whether the keyboard is a page field's, so that its closing can be told apart. */
    private val pageKeyboard = PageKeyboardTracker()

    /** An inset change lays the window out; insets dispatched to this view are a second cue. */
    private val keyboardWatch = ViewTreeObserver.OnGlobalLayoutListener { checkKeyboardOnScreen() }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        checkKeyboardOnScreen()
        return super.onApplyWindowInsets(insets)
    }

    /**
     * Reads whether the window shows the keyboard. Not from Compose's insets: a recomposition
     * reads those only on its next frame, and a keyboard that came and went in between left the
     * page's field thinking it still had one.
     */
    private fun checkKeyboardOnScreen() {
        val onScreen = rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ?: return
        val change = pageKeyboard.observed(onScreen)
        if (change == PageKeyboardTracker.KeyboardChange.NONE) return
        Log.i(LOG_TAG, "keyboard $change, $pageKeyboard")
        if (change == PageKeyboardTracker.KeyboardChange.DISMISSED) reportDismissal(Dismissal.CLOSED_BY_USER)
    }

    override fun onCheckIsTextEditor(): Boolean = engineRunning() && pageFieldActive

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // Without a prepared page field there is nothing to edit, so no connection is offered.
        if (!engineRunning() || !pageFieldActive) return null
        retireInputConnection()
        configurePageEditor(outAttrs, fieldKind, fieldMultiline)
        return EngineInputConnection(this, engineRunning, fieldContext).also { inputConnection = it }
    }

    /** Shows the keyboard for a page field of [kind] (as reported by the engine). */
    fun showKeyboard(kind: String = TEXT, multiline: Boolean = false, contextId: Long = 0) {
        val keyboard = context.getSystemService(InputMethodManager::class.java)
        requestFocus()
        // Before restartInput: it consults onCheckIsTextEditor, which is false without a field.
        pageFieldActive = true
        useFieldKind(kind, multiline, contextId, keyboard)
        pageKeyboard.showRequested()
        val shown = keyboard.showSoftInput(this, 0)
        Log.i(LOG_TAG, "keyboard show for $kind: accepted=$shown, $pageKeyboard")
        // Refused (this view is not the one the keyboard serves): no keyboard comes, so the field
        // must give up focus for a tap on it to ask again.
        if (!shown && pageKeyboard.reset()) reportDismissal(Dismissal.SHOW_REFUSED)
    }

    /**
     * A page field of [kind] has focus but the keyboard stays closed (the page focused it before
     * the user used the page); the engine asks for [showKeyboard] when the user taps the field.
     * Focus is not taken: a sheet the user is typing in keeps it.
     */
    fun prepareKeyboard(kind: String, multiline: Boolean = false, contextId: Long = 0) {
        pageFieldActive = true
        useFieldKind(kind, multiline, contextId, context.getSystemService(InputMethodManager::class.java))
    }

    fun hideKeyboard() {
        pageFieldActive = false
        retireInputConnection()
        pageKeyboard.hideRequested()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        Log.i(LOG_TAG, "keyboard hide, $pageKeyboard")
    }

    /**
     * The page's field no longer has the keyboard, other than through [hideKeyboard]. Servo asks
     * for a keyboard only when a field gains focus, so the engine makes the field give up focus:
     * a tap on it then opens the keyboard again.
     */
    private fun reportDismissal(cause: Dismissal) {
        pageFieldActive = false
        retireInputConnection()
        Log.i(LOG_TAG, "keyboard dismissal sent to the page: $cause")
        if (engineRunning()) NativeEngine.imeDismissed()
    }

    private fun useFieldKind(kind: String, multiline: Boolean, contextId: Long, keyboard: InputMethodManager) {
        // Even an identically typed field is a new editing context. Never keep an old
        // InputConnection that could deliver a delayed word into its replacement.
        retireInputConnection()
        fieldKind = kind
        fieldMultiline = multiline
        fieldContext = contextId
        keyboard.restartInput(this)
    }

    private fun retireInputConnection() {
        inputConnection?.closeConnection()
        inputConnection = null
    }

    private enum class Dismissal {
        /** Back, or the keyboard's own key. */
        CLOSED_BY_USER,
        SHOW_REFUSED,
        WINDOW_GONE,
    }

    private companion object {
        const val LOG_TAG = "CombrayEngine"
        const val TEXT = "text"
    }
}

/** Live, replaceable IME composition. Page selection/surrounding-text synchronization is separate. */
class EngineInputConnection(
    view: EngineSurfaceView,
    private val engineRunning: () -> Boolean,
    private val fieldContext: Long,
) : BaseInputConnection(view, false) {
    private val composition = PageComposition(
        send = { id, phase, text ->
            if (engineRunning() && fieldContext > 0) NativeEngine.imeComposition(id, fieldContext, phase.nativeValue, text)
        },
        commit = { if (engineRunning() && fieldContext > 0) NativeEngine.imeCommitInContext(fieldContext, it) },
    )

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (!canEdit() || !isRepresentableCursorPosition(newCursorPosition)) return false
        return composition.update(text.toString())
    }

    override fun finishComposingText(): Boolean = canEdit() && composition.finish()

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        // No local length cap on committed or composed text: neither the input donors nor the
        // native bridge establishes a supported bound, and inventing one would misrepresent the
        // engine. Only deletions are bounded, because each one becomes per-character key events.
        if (!canEdit() || !isRepresentableCursorPosition(newCursorPosition)) return false
        return composition.commit(text.toString())
    }

    override fun closeConnection() {
        composition.close()
        super.closeConnection()
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (!canEdit()) return false
        // Refused before the composition ends or a key is sent: a failed deletion must leave the
        // IME's composition and the page's text as they were.
        val keys = editDeletion(beforeLength, afterLength) ?: return false
        composition.finish()
        keys.forEach { tapKey(it) }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (!canEdit()) return false
        if (event.action == KeyEvent.ACTION_DOWN) composition.finish()
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> NativeEngine.KEY_PRESS
            KeyEvent.ACTION_UP -> NativeEngine.KEY_RELEASE
            else -> return false
        }
        NativeEngine.keyInContext(action, event.keyCode, event.unicodeChar, event.metaState, fieldContext)
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        if (!canEdit()) return false
        composition.finish()
        tapKey(editorActionKey(actionCode))
        return true
    }

    // The bridge has no page selection or surrounding-text channel, so these requests cannot be
    // represented. BaseInputConnection would answer from its own private editable and report a
    // selection the page never made, so each is refused or returns null instead.
    override fun setSelection(start: Int, end: Int): Boolean = false

    override fun setComposingRegion(start: Int, end: Int): Boolean = false

    override fun getSelectedText(flags: Int): CharSequence? = null

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? = null

    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? = null

    // Zero selects the legacy unbound hardware-key path. An InputConnection must never use it.
    private fun canEdit(): Boolean = engineRunning() && fieldContext > 0 && !composition.closed

    private fun tapKey(keyCode: Int) {
        NativeEngine.keyInContext(NativeEngine.KEY_PRESS, keyCode, 0, 0, fieldContext)
        NativeEngine.keyInContext(NativeEngine.KEY_RELEASE, keyCode, 0, 0, fieldContext)
    }
}

/** Longest deletion one IME request may turn into key events, in each direction. */
internal const val MAX_EDIT_CHARS = 1_000

/**
 * The key events a deletion request turns into: [beforeLength] backspaces, then [afterLength]
 * forward-deletes. Null when the request is negative or longer than [MAX_EDIT_CHARS] in either
 * direction; refusing it, rather than clipping it, keeps the IME's acknowledgment honest, since
 * only part of a clipped request could be sent.
 */
internal fun editDeletion(beforeLength: Int, afterLength: Int): List<Int>? {
    if (beforeLength !in 0..MAX_EDIT_CHARS || afterLength !in 0..MAX_EDIT_CHARS) return null
    return buildList {
        repeat(beforeLength) { add(KeyEvent.KEYCODE_DEL) }
        repeat(afterLength) { add(KeyEvent.KEYCODE_FORWARD_DEL) }
    }
}

/**
 * Whether a requested cursor position can be represented. The bridge has no cursor-placement
 * channel: the page puts the caret where its own editing does, which is after the text for the
 * default request (1). Any other requested position is refused rather than acknowledged while
 * ignored.
 */
internal fun isRepresentableCursorPosition(newCursorPosition: Int): Boolean = newCursorPosition == 1
