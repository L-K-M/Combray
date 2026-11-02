package ch.lkmc.combray.core.browser

import ch.lkmc.combray.engine.Appearance
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

        /** What `engine_ready` reports, as Servo 0.7.0 on Android sends it. */
        const val USER_AGENT = "Mozilla/5.0 (Android 10; Mobile; rv:153.0) Servo/0.7.0 Firefox/153.0"
    }

    private var listener: ((EngineEvent) -> Unit)? = null
    private var nextGeneration = 1L
    private val generations = HashMap<Long, Long>()
    val created = mutableListOf<Pair<Long, String>>()
    val selected = mutableListOf<Long>()
    val closed = mutableListOf<Long>()
    val navigated = mutableListOf<Triple<Long, Long, String>>()
    var reportCreated = true

    /** (tab, throttled) throttle commands, in order. */
    val throttling = mutableListOf<Pair<Long, Boolean>>()

    /** (document, capture ID) per capture request. */
    val captures = mutableListOf<Pair<Long, Long>>()

    /** Addresses the engine cannot parse: creating their view is refused with an error event. */
    val refusedUrls = mutableSetOf<String>()
    var persisted = 0

    /** Each appearance sent, with how many views existed then. */
    val appearances = mutableListOf<Pair<Appearance, Int>>()

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
        if (reportCreated) emit(EngineEvent.TabCreated(view(tab)))
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

    override fun setAppearance(appearance: Appearance) {
        appearances += appearance to created.size
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

    data class Stop(val view: ViewRef, val request: Long)
    val stopRequests = mutableListOf<Stop>()
    override fun stopLoading(view: ViewRef, request: Long) { stopRequests += Stop(view, request) }

    val reloaded = mutableListOf<Long>()
    override fun reload(tab: Long) { reloaded += tab }
    val reloadedPages = mutableListOf<PageRef>()
    override fun reload(page: PageRef) { reloaded += page.view.tab; reloadedPages += page }

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

    /** One find request: the document it searches, request ID, query and match index. */
    data class Find(val page: PageRef, val request: Long, val query: String, val index: Int) {
        val tab: Long get() = page.view.tab
    }

    val finds = mutableListOf<Find>()

    /** Documents whose find selection was cleared. */
    val findClears = mutableListOf<PageRef>()

    override fun find(page: PageRef, request: Long, query: String, index: Int) {
        finds += Find(page, request, query, index)
    }

    override fun clearFind(page: PageRef) {
        findClears += page
    }
}
