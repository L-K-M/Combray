package ch.lkmc.combray.engine

import android.view.Surface

/**
 * Process-wide bridge to the native engine thread in `libcombray_engine.so`.
 *
 * Every call only queues a request for the engine thread, which owns Servo; calls are safe from
 * any thread. Results come back as JSON events through [EngineListener], on the engine thread.
 * Events carry `tab` (the caller's logical tab ID), `generation` (native view incarnation) and,
 * when relevant, `document` (top-level document ID). Servo keeps process-wide state: start the
 * engine once per process and do not restart it after [shutdown].
 */
object NativeEngine {
    // Codes shared with native/src/jni_api.rs.
    const val TOUCH_DOWN = 0
    const val TOUCH_MOVE = 1
    const val TOUCH_UP = 2
    const val TOUCH_CANCEL = 3

    const val KEY_PRESS = 0
    const val KEY_RELEASE = 1

    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("combray_engine")
    }

    /** Loads the libraries (class initialization); throws a [LinkageError] if they are missing. */
    @JvmStatic fun ensureLoaded() = Unit

    fun interface EngineListener {
        /** Called on the engine thread; do not call blocking [NativeEngine] methods from here. */
        fun onEngineEvent(json: String)
    }

    @JvmStatic external fun start(profileDir: String, density: Float, listener: EngineListener): Boolean

    /**
     * Queues the surface and returns; the engine reports `surface_bound` (or `error`). The
     * first surface starts Servo; later ones replace the rendering target. Surface calls must
     * come from one thread, as `SurfaceHolder.Callback` guarantees.
     */
    @JvmStatic external fun surfaceCreated(surface: Surface, width: Int, height: Int): Boolean

    @JvmStatic external fun surfaceChanged(width: Int, height: Int)

    /** Stops rendering into the surface at once and waits (bounded) for the engine to unbind it. */
    @JvmStatic external fun surfaceDestroyed(): Boolean

    @JvmStatic external fun createTab(tab: Long, url: String)

    @JvmStatic external fun selectTab(tab: Long)

    /** Shows no view: the application's own screen covers the page area. */
    @JvmStatic external fun selectNone()

    /**
     * Writes cookies and other profile state now (reported as `profile_persisted`). Servo
     * otherwise writes them only at a clean shutdown, which Android rarely allows.
     */
    @JvmStatic external fun persistProfile()

    @JvmStatic external fun closeTab(tab: Long)

    @JvmStatic external fun navigate(tab: Long, request: Long, url: String)

    @JvmStatic external fun goBack(tab: Long)

    @JvmStatic external fun goForward(tab: Long)

    @JvmStatic external fun reload(tab: Long)

    /** Root-page jump, guarded by view generation and document identity. */
    @JvmStatic external fun scrollToEdge(tab: Long, generation: Long, document: Long, edge: Int)

    @JvmStatic external fun setThrottled(tab: Long, throttled: Boolean)

    /** Touch input for the selected tab, in surface pixels. */
    @JvmStatic external fun touch(phase: Int, pointer: Int, x: Float, y: Float)

    @JvmStatic external fun key(action: Int, keyCode: Int, unicode: Int)

    @JvmStatic external fun imeCommit(text: String)

    /** Long-press at a surface point of the selected tab: Servo's context menu. */
    @JvmStatic external fun contextClick(x: Float, y: Float)

    /** [response] is [ControlResponse.toJson]. */
    @JvmStatic external fun respondToControl(control: Long, response: String)

    /**
     * Capture readable text from [document] of [tab]. Servo refuses to run the script if the
     * document is no longer current. [skipLocalCheck] is for tests of that refusal only.
     */
    @JvmStatic external fun capture(
        tab: Long,
        document: Long,
        capture: Long,
        maxTextBytes: Int,
        timeoutMs: Int,
        skipLocalCheck: Boolean,
    )

    /** Reader view: [document]'s main article as JSON in the result's text (native/src/reader.js). */
    @JvmStatic external fun captureArticle(tab: Long, document: Long, capture: Long, maxBytes: Int, timeoutMs: Int)

    /** Test support: evaluate [script] in the tab's current document, without a document guard. */
    @JvmStatic external fun evaluate(tab: Long, request: Long, script: String)

    /**
     * Closes all views and shuts Servo down cleanly, persisting its profile. Blocks for up to
     * five minutes: never call it on the main thread or from [EngineListener.onEngineEvent].
     */
    @JvmStatic external fun shutdown(): Boolean
}
