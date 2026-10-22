package ch.lkmc.combray.core.browser

import ch.lkmc.combray.engine.ControlResponse
import ch.lkmc.combray.engine.EngineEvent
import ch.lkmc.combray.engine.EnginePort
import ch.lkmc.combray.engine.EngineStart
import ch.lkmc.combray.engine.PageRef
import ch.lkmc.combray.engine.ScrollEdge
import ch.lkmc.combray.engine.ViewRef

/** Behaves like the native bridge: one generation per created view, events via the listener. */
class FakeEngine : EnginePort {
    companion object {
        /** Recorded in [selected] when the coordinator shows no view. */
        const val NONE = -1L
    }

    private var listener: ((EngineEvent) -> Unit)? = null
    private var nextGeneration = 1L
    private val generations = HashMap<Long, Long>()
    val created = mutableListOf<Pair<Long, String>>()
    val selected = mutableListOf<Long>()
    val closed = mutableListOf<Long>()
    val navigated = mutableListOf<Triple<Long, Long, String>>()

    /** (tab, throttled) throttle commands, in order. */
    val throttling = mutableListOf<Pair<Long, Boolean>>()

    /** (document, capture ID) per capture request. */
    val captures = mutableListOf<Pair<Long, Long>>()

    /** Addresses the engine cannot parse: creating their view is refused with an error event. */
    val refusedUrls = mutableSetOf<String>()
    var persisted = 0

    override fun start(listener: (EngineEvent) -> Unit): EngineStart {
        this.listener = listener
        return EngineStart.STARTED
    }

    fun emit(event: EngineEvent) = listener!!(event)

    fun view(native: Long) = ViewRef(native, generations[native] ?: 0)

    override fun createView(tab: Long, url: String) {
        created += tab to url
        if (url in refusedUrls) {
            emit(EngineEvent.EngineError("cannot open $url", tab, null))
            return
        }
        generations[tab] = nextGeneration++
        emit(EngineEvent.TabCreated(view(tab)))
    }

    override fun selectView(tab: Long) {
        selected += tab
    }

    override fun showNone() {
        selected += NONE
    }

    override fun persistProfile() {
        persisted++
    }

    /** What the native bridge does for `window.open`: a view with an engine-chosen ID. */
    fun openWindow(opener: Long, tab: Long) {
        generations[tab] = nextGeneration++
        emit(EngineEvent.TabOpened(view(tab), view(opener)))
    }

    override fun closeView(tab: Long) {
        closed += tab
    }

    override fun navigate(tab: Long, request: Long, url: String) {
        navigated += Triple(tab, request, url)
    }

    override fun goBack(tab: Long) = Unit

    override fun goForward(tab: Long) = Unit

    override fun reload(tab: Long) = Unit

    val scrolled = mutableListOf<Pair<PageRef, ScrollEdge>>()

    override fun scrollToEdge(page: PageRef, edge: ScrollEdge) {
        scrolled += page to edge
    }

    override fun setThrottled(tab: Long, throttled: Boolean) {
        throttling += tab to throttled
    }

    /** Each page zoom requested, in order, with the number of views created before it. */
    val pageZooms = mutableListOf<Pair<Float, Int>>()

    override fun setPageZoom(zoom: Float) {
        pageZooms += zoom to created.size
    }

    val responses = mutableListOf<Pair<Long, ControlResponse>>()

    override fun respondToControl(control: Long, response: ControlResponse) {
        responses += control to response
    }

    override fun capture(tab: Long, document: Long, capture: Long, maxTextBytes: Int, timeoutMs: Int) {
        captures += document to capture
    }

    /** (document, capture ID) per article request. */
    val articleCaptures = mutableListOf<Pair<Long, Long>>()

    override fun captureArticle(tab: Long, document: Long, capture: Long, maxBytes: Int, timeoutMs: Int) {
        articleCaptures += document to capture
    }
}
