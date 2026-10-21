package ch.lkmc.combray.engine

/**
 * What the browser coordinator needs from the engine. Tab IDs here are native view IDs that
 * the caller allocates per view incarnation; they are not persistent logical tab IDs.
 *
 * Commands are queued for the engine thread and return at once. Results arrive as
 * [EngineEvent]s on the engine thread through the listener given to [start].
 */
interface EnginePort {
    fun start(listener: (EngineEvent) -> Unit): EngineStart

    fun createView(tab: Long, url: String)

    fun selectView(tab: Long)

    /** Shows no view (the internal home screen covers the page area). */
    fun showNone()

    fun closeView(tab: Long)

    fun navigate(tab: Long, request: Long, url: String)

    fun goBack(tab: Long)

    fun goForward(tab: Long)

    fun reload(tab: Long)

    fun setThrottled(tab: Long, throttled: Boolean)

    /** Writes cookies and other profile state now, so logins survive a killed process. */
    fun persistProfile()

    /** Pages see [appearance] as `prefers-color-scheme`, now and in views created later. */
    fun setAppearance(appearance: Appearance)

    /** Answers a [EngineEvent.ControlShown]; ignored once the control was hidden. */
    fun respondToControl(control: Long, response: ControlResponse)

    /** Reads bounded text from [document] only; the result names the document it came from. */
    fun capture(tab: Long, document: Long, capture: Long, maxTextBytes: Int, timeoutMs: Int)

    /**
     * Like [capture], for reader view: the result's text is [document]'s main article as JSON
     * blocks of at most [maxBytes] (native/src/reader.js), or empty when it has none.
     */
    fun captureArticle(tab: Long, document: Long, capture: Long, maxBytes: Int, timeoutMs: Int)
}

/** The system's light or dark theme, which pages follow through `prefers-color-scheme`. */
enum class Appearance { LIGHT, DARK }

enum class EngineStart {
    STARTED,
    ALREADY_STARTED,

    /** `libcombray_engine.so` is not in the APK (a build without scripts/build-native.sh). */
    LIBRARY_MISSING,
    FAILED,
}
