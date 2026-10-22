package ch.lkmc.combray.engine

import android.util.Log
import java.io.File

/**
 * [EnginePort] backed by `libcombray_engine.so`. Servo keeps process-wide state, so there is one
 * engine per process; this host is meant to be a process-scoped singleton.
 */
class NativeEngineHost(private val profileDir: File, private val density: Float) : EnginePort {
    @Volatile private var started = false

    @Synchronized
    override fun start(listener: (EngineEvent) -> Unit): EngineStart {
        if (started) return EngineStart.ALREADY_STARTED

        // Initializing the class loads the library; a build without the native step has none.
        // (A constant would not do: the compiler inlines it without initializing the class.)
        val loaded = try {
            NativeEngine.ensureLoaded()
            true
        } catch (missing: LinkageError) {
            Log.e(LOG_TAG, "native engine library missing", missing)
            false
        }
        if (!loaded) return EngineStart.LIBRARY_MISSING

        profileDir.mkdirs()
        started = NativeEngine.start(profileDir.path, density) { json -> deliver(json, listener) }
        return if (started) EngineStart.STARTED else EngineStart.FAILED
    }

    private fun deliver(json: String, listener: (EngineEvent) -> Unit) {
        val event = try {
            EngineEvent.parse(json)
        } catch (malformed: IllegalArgumentException) {
            // A schema mismatch between native and Kotlin is a bug; never guess at the event.
            Log.e(LOG_TAG, "unparseable engine event (${json.length} chars)", malformed)
            return
        }
        listener(event)
    }

    override fun createView(tab: Long, url: String) = NativeEngine.createTab(tab, url)

    override fun selectView(tab: Long) = NativeEngine.selectTab(tab)

    override fun showNone() = NativeEngine.selectNone()

    override fun persistProfile() = NativeEngine.persistProfile()

    override fun closeView(tab: Long) = NativeEngine.closeTab(tab)

    override fun navigate(tab: Long, request: Long, url: String) = NativeEngine.navigate(tab, request, url)

    override fun goBack(tab: Long) = NativeEngine.goBack(tab)

    override fun goForward(tab: Long) = NativeEngine.goForward(tab)

    override fun reload(tab: Long) = NativeEngine.reload(tab)

    override fun scrollToEdge(page: PageRef, edge: ScrollEdge) =
        NativeEngine.scrollToEdge(page.view.tab, page.view.generation, page.document, edge.nativeCode)

    override fun setThrottled(tab: Long, throttled: Boolean) = NativeEngine.setThrottled(tab, throttled)

    override fun respondToControl(control: Long, response: ControlResponse) =
        NativeEngine.respondToControl(control, response.toJson())

    override fun capture(tab: Long, document: Long, capture: Long, maxTextBytes: Int, timeoutMs: Int) =
        NativeEngine.capture(tab, document, capture, maxTextBytes, timeoutMs, false)

    override fun captureArticle(tab: Long, document: Long, capture: Long, maxBytes: Int, timeoutMs: Int) =
        NativeEngine.captureArticle(tab, document, capture, maxBytes, timeoutMs)

    private companion object {
        const val LOG_TAG = "CombrayEngine"
    }
}
