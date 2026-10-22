package ch.lkmc.combray.core.browser

import android.util.Log
import androidx.room.withTransaction
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.SessionEntity
import ch.lkmc.combray.core.data.TabEntity
import ch.lkmc.combray.core.data.TabState
import ch.lkmc.combray.core.data.VisitEntity
import ch.lkmc.combray.core.data.VisitKind
import ch.lkmc.combray.core.policy.HostNames
import ch.lkmc.combray.engine.Attribution
import ch.lkmc.combray.engine.CaptureStatus
import ch.lkmc.combray.engine.ControlResponse
import ch.lkmc.combray.engine.EngineEvent
import ch.lkmc.combray.engine.EnginePort
import ch.lkmc.combray.engine.EngineStart
import ch.lkmc.combray.engine.HistoryKind
import ch.lkmc.combray.engine.LoadPhase
import ch.lkmc.combray.engine.PageControlContent
import ch.lkmc.combray.engine.ViewRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

enum class Placement { CURRENT_TAB, NEW_TAB }

/**
 * Owns the browser's logical state: tabs, visits and the focused tab in the database, and the
 * native views that render them.
 *
 * ```
 *  UI commands ─┐                       ┌─> database (transactions)
 *               ├─> work queue ─> one ──┤
 *  engine events┘   (in order)  consumer└─> engine commands (create/select/close views)
 * ```
 *
 * Every command and engine event runs to completion on one consumer before the next starts, so
 * logical state, the database and the native view map never interleave. Native views are a
 * cache of the logical state: a tab is open whether or not it has a renderer, and late events
 * from a closed or replaced view are ignored (they no longer map to a live view).
 */
class BrowserCoordinator(
    private val database: CombrayDatabase,
    private val engine: EnginePort,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.SYSTEM,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val retention: Retention = Retention(),
    private val capture: CaptureSink? = null,
    private val captureTiming: CaptureTiming = CaptureTiming(),
    /** Renderers kept alive; beyond this the least recently focused hidden tabs release theirs. */
    private val maxLiveViews: Int = MAX_LIVE_VIEWS,
    /** Read at each maintenance: whether open tabs unused for the retention period close. */
    private val unusedTabs: () -> UnusedTabs = { UnusedTabs.KEEP },
) {
    private val dao = database.browser()
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val state = MutableStateFlow(BrowserState())

    // Runtime state, touched only by the consumer.
    private val liveByTab = HashMap<String, LiveView>()
    private val liveByNative = HashMap<Long, LiveView>()
    private var nextNativeTab = 1L
    private var nextRequest = 1L
    private var selectedNative: Long? = null
    private var engineReady = false
    private val pendingCaptures = HashMap<Long, PendingCapture>()
    private val pendingArticles = HashMap<Long, PendingArticle>()

    /** Reader view, while open; shown only over the tab it was opened for. */
    private var reader: ReaderState? = null

    /** The article request reader view waits for; an older one's late result is ignored. */
    private var readerRequest: Long? = null

    /** The tab another app's link opened, until focus moves elsewhere (process memory only). */
    private var otherAppTab: String? = null

    /** Document keys are only meaningful within one process: prefix them with its identity. */
    private val processKey = newId().take(PROCESS_KEY_LENGTH)

    val browserState: StateFlow<BrowserState> = state.asStateFlow()

    /**
     * Starts the engine (synchronously, so a surface created right after this can bind) and the
     * work queue, then restores the session. Call once per process.
     */
    fun start() {
        startEngine()
        scope.launch {
            for (job in work) {
                try {
                    job()
                } catch (failure: Exception) {
                    // A failed step must not stop the queue; it is a bug worth surfacing.
                    Log.e(LOG_TAG, "browser state update failed", failure)
                    state.update { it.copy(notice = Notice.Problem("Something went wrong; the last action may not have completed.")) }
                }
            }
        }
        submit {
            maintain()
            publish()
        }
    }

    /** Resolves after everything queued before this call has been processed. */
    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        submit { done.complete(Unit) }
        done.await()
    }

    /** Recent: open tabs and local history, newest first, filtered by [query] if given. */
    fun recent(query: String? = null): Flow<List<RecentItem>> {
        val since = clock.now() - retention.closedHistoryMillis
        return combine(
            dao.observeOpenTabs(),
            dao.observeHistory(since, RECENT_LIMIT),
            dao.observeSession(),
        ) { tabs, visits, session ->
            val items = tabs.map {
                RecentItem.OpenTab(it.id, it.url, it.title, it.lastFocusedAt, it.id == session?.activeTabId)
            } + visits.map { RecentItem.Visit(it.id, it.tabId, it.url, it.title, it.lastSeenAt) }
            items.filter { it.matches(query) }.sortedByDescending { it.time }
        }
    }

    // --- Commands -------------------------------------------------------------------------------

    /** Opens [url] (already classified as a web address or search) in the focused or a new tab. */
    fun openUrl(url: String, placement: Placement) = submit {
        val active = activeTab()
        if (placement == Placement.NEW_TAB || active == null) createTab(url) else navigateTab(active, url)
        reconcile()
        publish()
    }

    /**
     * Opens a link from another app in a new tab. Until the user moves to another tab, Back on
     * its first page returns to that app and leaves the page in history only
     * ([returnToOtherApp]).
     */
    fun openFromOtherApp(url: String) = submit {
        otherAppTab = createTab(url)
        reconcile()
        publish()
    }

    /**
     * Back on the first page of the tab another app opened: the tab closes without an Undo
     * notice (the user is leaving), and its page stays in Recent as history.
     */
    fun returnToOtherApp(tabId: String) = submit {
        if (otherAppTab != tabId) return@submit
        otherAppTab = null
        val tab = dao.tab(tabId)?.takeIf { it.state == TabState.OPEN } ?: return@submit
        closeLogically(tab)
        reconcile()
        publish()
    }

    /**
     * Opens [url] in the tab the user was editing ([tabId]), even if focus moved meanwhile; a
     * closed tab gets a new one instead.
     */
    fun openUrlInTab(tabId: String, url: String) = submit {
        val tab = dao.tab(tabId)?.takeIf { it.state == TabState.OPEN }
        if (tab == null) {
            createTab(url)
        } else {
            if (activeTab()?.id != tab.id) focus(tab)
            navigateTab(dao.tab(tab.id) ?: tab, url)
        }
        reconcile()
        publish()
    }

    private suspend fun navigateTab(tab: TabEntity, url: String) {
        val view = liveByTab[tab.id]
        if (view != null && !view.crashed) {
            engine.navigate(view.nativeTab, nextRequest++, url)
            return
        }
        // No renderer yet (new tab, restored tab): the request becomes the recovery address.
        view?.let { closeView(it) }
        dao.updateTab(tab.copy(url = url, title = null))
    }

    /** Focuses an unused new tab, creating one only when none exists. */
    fun newTab() = submit {
        val active = activeTab()
        if (active != null && isUnusedBlank(active)) return@submit
        val blank = dao.openTabs().firstOrNull { isUnusedBlank(it) }
        if (blank != null) focus(blank) else createTab(url = null)
        reconcile()
        publish()
    }

    fun selectTab(tabId: String) = submit {
        val tab = dao.tab(tabId)?.takeIf { it.state == TabState.OPEN } ?: return@submit
        focus(tab)
        reconcile()
        publish()
    }

    /** Closes [tabId]; its pages stay in Recent as history. Focus moves to the last used tab. */
    fun closeTab(tabId: String) = submit {
        val tab = dao.tab(tabId)?.takeIf { it.state == TabState.OPEN } ?: return@submit
        closeLogically(tab)
        state.update { it.copy(notice = Notice.TabClosed(tab.id, tab.title)) }
        reconcile()
        publish()
    }

    /** Undo for [closeTab]: the same logical tab, reloaded at its last address (no back stack). */
    fun reopenTab(tabId: String) = submit {
        val tab = dao.tab(tabId)?.takeIf { it.state == TabState.CLOSED } ?: return@submit
        val now = clock.now()
        database.withTransaction {
            dao.updateTab(tab.copy(state = TabState.OPEN, closedAt = null, lastFocusedAt = now))
            activate(tab.id, now)
        }
        reconcile()
        publish()
    }

    /** Opens a history row's recorded address as a new navigation in a new tab. */
    fun openVisit(visitId: String) = submit {
        val visit = dao.visit(visitId) ?: return@submit
        createTab(visit.url)
        reconcile()
        publish()
    }

    fun goBack() = withActiveView { engine.goBack(it.nativeTab) }

    fun goForward() = withActiveView { engine.goForward(it.nativeTab) }

    /** Reloads the page; a crashed renderer is replaced by a new one at the tab's address. */
    fun reload() = submit {
        val tab = activeTab() ?: return@submit
        val view = liveByTab[tab.id]
        when {
            view == null -> reconcile()
            view.crashed -> {
                closeView(view)
                reconcile()
            }
            else -> engine.reload(view.nativeTab)
        }
        publish()
    }

    /** Removes one history row. The page an open tab shows is not history and stays. */
    fun forgetVisit(visitId: String) = submit {
        database.withTransaction {
            val visit = dao.visit(visitId) ?: return@withTransaction
            val tab = dao.tab(visit.tabId)
            if (tab?.state == TabState.OPEN && tab.currentVisitId == visit.id) return@withTransaction
            dao.deleteVisit(visit.id)
            dao.deleteEmptyClosedTabs()
            capture?.forgetUrl(visit.url)
        }
    }

    /**
     * Removes local history for a site and its unsent captures. Open tabs stay open on their
     * current page. What Hister and Karakeep already received is not touched (separate services).
     */
    fun forgetSite(host: String, includeSubdomains: Boolean) = submit {
        val site = HostNames.normalize(host) ?: return@submit
        database.withTransaction {
            dao.deleteVisitsForHost(site, if (includeSubdomains) ".$site" else null)
            dao.deleteEmptyClosedTabs()
            capture?.forgetHost(site, includeSubdomains)
        }
    }

    /**
     * Expires history past the retention window and, when the user chose so, closes tabs unused
     * for as long. The focused tab is never closed this way.
     */
    fun runMaintenance() = submit {
        maintain()
        reconcile()
        publish()
    }

    /** Writes cookies and profile state, so logins survive Android killing the process. */
    fun persistProfile() = submit {
        if (engineReady) engine.persistProfile()
    }

    /**
     * Opens reader view for the focused tab: the engine reads the main article of the document
     * shown now, in that document only.
     */
    fun openReader() = submit {
        val tab = activeTab() ?: return@submit
        val view = liveByTab[tab.id]
        val document = view?.currentDocument
        val generation = view?.generation
        val url = tab.url
        if (view == null || view.crashed || document == null || generation == null || url == null || document in view.errorDocuments) {
            reader = ReaderState.Unavailable(tab.id)
            publish()
            return@submit
        }
        val id = nextRequest++
        pendingArticles[id] = PendingArticle(tab.id, ViewRef(view.nativeTab, generation), document, url)
        engine.captureArticle(view.nativeTab, document, id, MAX_ARTICLE_BYTES, ARTICLE_TIMEOUT_MS)
        readerRequest = id
        reader = ReaderState.Loading(tab.id)
        publish()
    }

    /** Leaves reader view; an article still being read is dropped when it arrives. */
    fun closeReader() = submit {
        reader = null
        publish()
    }

    /** Answers [control]; ignored if the page withdrew it or its tab closed meanwhile. */
    fun answerPageControl(control: Long, response: ControlResponse, blocking: DialogBlocking = DialogBlocking.KEEP_SHOWING) = submit {
        val view = liveByTab.values.firstOrNull { live -> live.controls.any { it.id == control } } ?: return@submit
        val answered = view.controls.first { it.id == control }
        view.controls.remove(answered)
        // Only where the dialog offered it: a stray request must not silence the page's dialogs.
        if (blocking == DialogBlocking.BLOCK_FOR_PAGE && answered.offerBlocking) view.dialogsBlocked = true
        engine.respondToControl(control, response)
        publish()
    }

    /** Clears [notice] (or an equal duplicate); a different one posted while the UI showed it is kept. */
    fun noticeShown(notice: Notice) = state.update { if (it.notice == notice) it.copy(notice = null) else it }

    /** Clears [request] (or an equal duplicate); focus moving to the next field posts a different one meanwhile. */
    fun keyboardHandled(request: KeyboardRequest) = state.update { if (it.keyboard == request) it.copy(keyboard = null) else it }

    // --- Engine events ----------------------------------------------------------------------------

    private fun onEngineEvent(event: EngineEvent) = submit {
        when (event) {
            is EngineEvent.EngineReady -> {
                engineReady = true
                state.update { it.copy(engine = EngineStatus.READY) }
                reconcile()
            }
            is EngineEvent.TabCreated -> liveByNative[event.view.tab]?.let { it.generation = event.view.generation }
            is EngineEvent.TabOpened -> onTabOpened(event)
            is EngineEvent.TabClosed -> view(event.view)?.let { onClosedByPage(it) }
            is EngineEvent.HistoryChanged -> view(event.view)?.let { onHistory(it, event) }
            is EngineEvent.LoadStatus -> view(event.view)?.let {
                if (event.document == it.currentDocument && event.attribution != Attribution.STALE) {
                    it.loading = event.phase != LoadPhase.COMPLETE
                    if (event.phase == LoadPhase.COMPLETE) scheduleCapture(it, event.document, captureTiming.settleMs)
                }
            }
            is EngineEvent.CaptureResult -> if (event.capture in pendingArticles) onArticleResult(event) else onCaptureResult(event)
            is EngineEvent.TitleChanged -> view(event.view)?.let { onTitle(it, event) }
            is EngineEvent.ImeShown -> view(event.view)?.takeIf { it.nativeTab == selectedNative }?.let {
                state.update { it.copy(keyboard = KeyboardRequest.Show(event.kind)) }
            }
            is EngineEvent.ImeHidden -> view(event.view)?.takeIf { it.nativeTab == selectedNative }?.let {
                state.update { it.copy(keyboard = KeyboardRequest.Hide) }
            }
            is EngineEvent.Crashed -> view(event.view)?.let {
                Log.w(LOG_TAG, "renderer crashed: ${event.reason}")
                it.crashed = true
                it.loading = false
                it.controls.clear()
                it.dialogDocument = null
                it.dialogCount = 0
                it.dialogsBlocked = false
            }
            is EngineEvent.ControlShown -> view(event.view)?.let { onControlShown(it, event) }
            is EngineEvent.ControlHidden -> view(event.view)?.let { live -> live.controls.removeAll { it.id == event.control } }
            is EngineEvent.EngineError -> onEngineError(event)
            else -> Unit
        }
        publish()
    }

    private fun onControlShown(view: LiveView, event: EngineEvent.ControlShown) {
        val dialog = event.content is PageControlContent.Alert ||
            event.content is PageControlContent.Confirm ||
            event.content is PageControlContent.Prompt
        if (dialog) {
            // Counting restarts with each document, so a page that navigated may ask again.
            if (view.dialogDocument != view.currentDocument) {
                view.dialogDocument = view.currentDocument
                view.dialogCount = 0
                view.dialogsBlocked = false
            }
            if (view.dialogsBlocked) {
                engine.respondToControl(event.control, ControlResponse.Dismiss)
                return
            }
            view.dialogCount++
        }
        view.controls.addLast(ShownControl(event.control, event.content, offerBlocking = dialog && view.dialogCount > 1))
    }

    private suspend fun onHistory(view: LiveView, event: EngineEvent.HistoryChanged) {
        view.canGoBack = event.canGoBack
        view.canGoForward = event.canGoForward
        val tab = dao.tab(view.tabId)?.takeIf { it.state == TabState.OPEN } ?: return
        val now = clock.now()

        database.withTransaction {
            val current = tab.currentVisitId?.let { dao.visit(it) }
            // A single-page app can replace the article without replacing its document.
            if (reader?.tabId == view.tabId && (event.kind == HistoryKind.DOCUMENT_COMMITTED || event.url != tab.url)) {
                reader = null
            }
            val visit = when (event.kind) {
                HistoryKind.DOCUMENT_COMMITTED -> {
                    view.currentDocument = event.document
                    // A cached document (back/forward) reports no load progress: it is loaded.
                    view.loading = event.document !in view.documentVisits
                    scheduleCapture(view, event.document, captureTiming.fallbackMs)
                    committedVisit(view, tab, current, event, now)
                }
                HistoryKind.SAME_DOCUMENT -> {
                    scheduleCapture(view, event.document, captureTiming.debounceMs)
                    sameDocumentVisit(view, tab, current, event, now)
                }
                HistoryKind.LIST_UPDATED -> current?.let {
                    if (it.url == event.url) it else it.copy(url = event.url, host = HostNames.of(event.url)).also { updated -> dao.updateVisit(updated) }
                }
            }
            visit?.let { view.documentVisits[event.document] = it.id }
            if (current != null && visit?.id != current.id) retireVisit(tab, current, now)

            // Navigating in the focused tab is using it, so its Recent row stays above the pages it
            // left. A hidden tab loading pages is not in use: closing unused tabs still closes it.
            val focused = dao.session()?.activeTabId == tab.id
            val lastFocusedAt = if (focused) now else tab.lastFocusedAt
            dao.updateTab(tab.copy(currentVisitId = visit?.id, url = event.url, title = visit?.title, lastFocusedAt = lastFocusedAt))
        }
    }

    /**
     * Grouping (documented in docs/IMPLEMENTATION_PLAN.md section 5): a back/forward return to a
     * document this view already showed reuses its visit; a same-URL reload refreshes the current
     * visit; any other new document is a new visit. Non-web documents are not history.
     */
    private suspend fun committedVisit(
        view: LiveView,
        tab: TabEntity,
        current: VisitEntity?,
        event: EngineEvent.HistoryChanged,
        now: Long,
    ): VisitEntity? {
        if (!HostNames.isWeb(event.url)) return null
        val key = documentKey(view, event.document)

        val returned = view.documentVisits[event.document]?.let { dao.visit(it) }
        if (returned != null) {
            return returned.copy(lastSeenAt = now).also { dao.updateVisit(it) }
        }
        if (current != null && current.url == event.url) {
            view.reloadedDocuments += event.document
            return current.copy(documentKey = key, lastSeenAt = now).also { dao.updateVisit(it) }
        }
        return newVisit(tab, event.url, key, VisitKind.DOCUMENT, now)
    }

    /** A fragment change on the same path updates the current visit; a new path is a new visit. */
    private suspend fun sameDocumentVisit(
        view: LiveView,
        tab: TabEntity,
        current: VisitEntity?,
        event: EngineEvent.HistoryChanged,
        now: Long,
    ): VisitEntity? {
        if (!HostNames.isWeb(event.url)) return current
        if (current != null && current.url.substringBefore('#') == event.url.substringBefore('#')) {
            return current.copy(url = event.url, lastSeenAt = now).also { dao.updateVisit(it) }
        }
        return newVisit(tab, event.url, documentKey(view, event.document), VisitKind.SAME_DOCUMENT, now)
            .copy(title = current?.title)
            .also { dao.updateVisit(it) }
    }

    private suspend fun newVisit(tab: TabEntity, url: String, key: String, kind: VisitKind, now: Long): VisitEntity =
        VisitEntity(
            id = newId(),
            tabId = tab.id,
            url = url,
            host = HostNames.of(url),
            title = null,
            documentKey = key,
            kind = kind,
            committedAt = now,
            lastSeenAt = now,
            failed = false,
        ).also { dao.insertVisit(it) }

    private suspend fun onTitle(view: LiveView, event: EngineEvent.TitleChanged) {
        // A replaced document's late title must never relabel the page now shown.
        if (event.attribution == Attribution.STALE) return
        val visitId = view.documentVisits[event.document] ?: return
        database.withTransaction {
            val visit = dao.visit(visitId) ?: return@withTransaction
            val title = event.title?.takeIf { it.isNotBlank() }
            // Servo commits its own error page for failed loads, with no other failure signal.
            val errorPage = title in SERVO_ERROR_TITLES
            if (errorPage) view.errorDocuments += event.document
            val reloadOfAGoodPage = errorPage && event.document in view.reloadedDocuments && visit.title != null && !visit.failed
            // A failed reload must not turn the page's earlier successful visit into a failure.
            if (!reloadOfAGoodPage) dao.updateVisit(visit.copy(title = title, failed = errorPage))
            val tab = dao.tab(view.tabId) ?: return@withTransaction
            if (tab.currentVisitId == visit.id) dao.updateTab(tab.copy(title = title))
        }
    }

    /**
     * A page opened a window. It becomes a new logical tab, focused, like a link opened in a new
     * tab; its address arrives with its first commit. Windows from closed tabs are refused.
     */
    private suspend fun onTabOpened(event: EngineEvent.TabOpened) {
        // Only the page the user sees may open a tab (the engine enforces this too).
        val opener = view(event.opener)
        if (opener == null || opener.nativeTab != selectedNative) {
            engine.closeView(event.view.tab)
            return
        }
        val tabId = createTab(url = null)
        val view = LiveView(tabId, event.view.tab).also { it.generation = event.view.generation }
        liveByTab[tabId] = view
        liveByNative[view.nativeTab] = view
        reconcile()
    }

    /** A new tab nobody typed into yet (a page-opened window has a view before its address). */
    private fun isUnusedBlank(tab: TabEntity) = tab.url == null && liveByTab[tab.id] == null

    // --- Capture ----------------------------------------------------------------------------------

    /** (Re)starts the capture timer of [view] for [document]; a newer trigger replaces it. */
    private fun scheduleCapture(view: LiveView, document: Long, delayMs: Long) {
        if (capture == null) return
        view.captureTimer?.cancel()
        view.captureTimer = scope.launch {
            delay(delayMs)
            submit { requestCapture(view, document) }
        }
    }

    private suspend fun requestCapture(view: LiveView, document: Long) {
        val sink = capture ?: return
        // Only the document still current in a live, healthy view: anything else is stale.
        if (liveByNative[view.nativeTab] !== view || view.currentDocument != document || view.crashed) return
        // Servo's error pages carry the failed address but are not that page's content.
        if (document in view.errorDocuments) return
        val generation = view.generation ?: return
        val tab = dao.tab(view.tabId)?.takeIf { it.state == TabState.OPEN } ?: return
        val url = tab.url ?: return
        val policy = sink.wantsCapture(url) ?: return
        val id = nextRequest++
        pendingCaptures[id] = PendingCapture(view.tabId, ViewRef(view.nativeTab, generation), document, url, tab.currentVisitId, policy)
        engine.capture(view.nativeTab, document, id, captureTiming.maxTextBytes, captureTiming.timeoutMs)
    }

    /**
     * The engine only runs the script in the named document and rechecks it afterwards; this
     * also requires the tab to still show that document at the same address (a single-page app
     * may have changed it meanwhile; its own capture follows).
     */
    private suspend fun onCaptureResult(event: EngineEvent.CaptureResult) {
        val pending = pendingCaptures.remove(event.capture) ?: return
        if (event.status != CaptureStatus.CAPTURED || event.view != pending.view || event.document != pending.document) return
        val content = event.content ?: return
        if (content.title in SERVO_ERROR_TITLES) return
        val view = view(event.view)?.takeIf { it.currentDocument == pending.document && pending.document !in it.errorDocuments } ?: return
        val tab = dao.tab(view.tabId)?.takeIf { it.state == TabState.OPEN && it.url == pending.url } ?: return
        capture?.captured(
            CapturedPage(
                tabId = tab.id,
                visitId = pending.visitId,
                url = pending.url,
                title = content.title.takeIf { it.isNotBlank() },
                text = content.text,
                sha256 = content.sha256,
                truncated = content.truncated,
                policyGeneration = pending.policyGeneration,
            ),
        )
    }

    /** Shows the article if reader view still waits for it and the page is still the one asked. */
    private fun onArticleResult(event: EngineEvent.CaptureResult) {
        val pending = pendingArticles.remove(event.capture) ?: return
        if (event.capture != readerRequest || reader != ReaderState.Loading(pending.tabId)) return
        // Status only: the detail can quote page script errors.
        Log.i(LOG_TAG, "reader article: ${event.status}")
        // The saved view identity is not enough: a replaced or crashed renderer can still
        // deliver that callback, and it must not cover the page now on screen.
        val live = view(pending.view)?.takeIf {
            !it.crashed && it.currentDocument == pending.document && pending.document !in it.errorDocuments
        }
        val content = event.content?.takeIf {
            live != null && event.status == CaptureStatus.CAPTURED && event.view == pending.view && event.document == pending.document
        }
        val article = content?.let { ArticleJson.parse(it.text, it.truncated) }
        reader = article?.let { ReaderState.Ready(pending.tabId, pending.url, it) } ?: ReaderState.Unavailable(pending.tabId)
    }

    private fun view(ref: ViewRef): LiveView? =
        liveByNative[ref.tab]?.takeIf { it.generation == ref.generation }

    // --- Helpers ----------------------------------------------------------------------------------

    private fun submit(block: suspend () -> Unit) {
        work.trySend(block)
    }

    private fun withActiveView(action: (LiveView) -> Unit) = submit {
        val tab = activeTab() ?: return@submit
        liveByTab[tab.id]?.takeIf { !it.crashed }?.let(action)
    }

    private fun startEngine() {
        val start = engine.start { event -> onEngineEvent(event) }
        val status = when (start) {
            EngineStart.STARTED, EngineStart.ALREADY_STARTED -> EngineStatus.STARTING
            EngineStart.LIBRARY_MISSING -> EngineStatus.LIBRARY_MISSING
            EngineStart.FAILED -> EngineStatus.FAILED
        }
        state.update { it.copy(engine = status) }
    }

    private suspend fun activeTab(): TabEntity? =
        dao.session()?.activeTabId?.let { dao.tab(it) }?.takeIf { it.state == TabState.OPEN }

    private suspend fun createTab(url: String?): String {
        val now = clock.now()
        val tab = TabEntity(
            id = newId(),
            state = TabState.OPEN,
            currentVisitId = null,
            url = url,
            title = null,
            createdAt = now,
            lastFocusedAt = now,
            closedAt = null,
        )
        database.withTransaction {
            dao.insertTab(tab)
            activate(tab.id, now)
        }
        return tab.id
    }

    private suspend fun focus(tab: TabEntity) {
        val now = clock.now()
        database.withTransaction {
            dao.updateTab(tab.copy(lastFocusedAt = now))
            activate(tab.id, now)
        }
    }

    /**
     * Makes [tabId] the focused tab. The tab losing focus was in use until now: its
     * [TabEntity.lastFocusedAt] moves to just before [now], so a tab read for days is not
     * "unused" the moment the user switches away, and keeps its place right after the new one.
     */
    private suspend fun activate(tabId: String, now: Long) {
        val previous = dao.session()?.activeTabId
        if (previous != null && previous != tabId) {
            dao.tab(previous)?.takeIf { it.state == TabState.OPEN }?.let { dao.updateTab(it.copy(lastFocusedAt = now - 1)) }
        }
        dao.setSession(SessionEntity(activeTabId = tabId))
        if (tabId != otherAppTab) otherAppTab = null
    }

    /**
     * Close and successor selection in one transaction (invariant 5). The closed page takes its
     * place in history by when the tab was last used, not by when it closed: closing a tab left
     * long ago does not move it to the top of Recent, and retention counts from that last use.
     * The focused tab was in use until now, like a tab focus leaves ([activate]); the successor
     * is in use from now.
     */
    private suspend fun closeLogically(tab: TabEntity) {
        val now = clock.now()
        database.withTransaction {
            val focused = dao.session()?.activeTabId == tab.id
            tab.currentVisitId?.let { dao.visit(it) }?.let { retireVisit(tab, it, now) }
            dao.updateTab(tab.copy(state = TabState.CLOSED, closedAt = now))
            if (focused) {
                val successor = dao.openTabs().firstOrNull()
                successor?.let { dao.updateTab(it.copy(lastFocusedAt = now)) }
                dao.setSession(SessionEntity(activeTabId = successor?.id))
            }
        }
        liveByTab[tab.id]?.let { closeView(it) }
        if (otherAppTab == tab.id) otherAppTab = null
    }

    /**
     * [tab] leaves [visit] by navigating elsewhere or closing. The page takes its place in history,
     * and its retention counts, from the tab's last use: the focused tab was in use until now, like
     * a tab focus leaves ([activate]). The max keeps a later time, such as a hidden tab's own commit.
     */
    private suspend fun retireVisit(tab: TabEntity, visit: VisitEntity, now: Long) {
        val focused = dao.session()?.activeTabId == tab.id
        val lastUse = if (focused) now - 1 else tab.lastFocusedAt
        dao.updateVisit(visit.copy(lastSeenAt = maxOf(visit.lastSeenAt, lastUse)))
    }

    private fun closeView(view: LiveView) {
        engine.closeView(view.nativeTab)
        forgetView(view)
    }

    /** Drops the bookkeeping for a renderer the engine no longer has. */
    private fun forgetView(view: LiveView) {
        view.captureTimer?.cancel()
        liveByTab.remove(view.tabId)
        liveByNative.remove(view.nativeTab)
        if (selectedNative == view.nativeTab) selectedNative = null
    }

    /** The page closed its own window: the tab closes as if the user had closed it. */
    private suspend fun onClosedByPage(view: LiveView) {
        forgetView(view)
        val tab = dao.tab(view.tabId)?.takeIf { it.state == TabState.OPEN } ?: return
        closeLogically(tab)
        state.update { it.copy(notice = Notice.TabClosed(tab.id, tab.title)) }
        reconcile()
    }

    /**
     * The engine refused a request — or the engine itself failed. A refused view (an address
     * Servo cannot parse) must not leave a dead tab with the previous page still shown: the
     * tab returns to the internal home.
     */
    private suspend fun onEngineError(event: EngineEvent.EngineError) {
        val tab = event.tab
        if (tab == null) {
            // Engine-level: the native error() path emits with no tab, refusals carry one.
            // Only startup failure is fatal here, so only that one is an ERROR.
            if (state.value.engine == EngineStatus.STARTING) {
                Log.e(LOG_TAG, "engine-level failure: ${event.message}")
            } else {
                Log.w(LOG_TAG, "engine-level failure: ${event.message}")
            }
            // While the app still waits for engine_ready, "starting" would never resolve —
            // fail into the overlay that already renders it. After ready, engine-level
            // errors are recoverable or benign (a failed surface bind retries on the next
            // bind; control-response oddities do not affect rendering), so the status stays
            // READY and the log carries them; severity per message belongs with the emitter.
            state.update { current ->
                if (current.engine == EngineStatus.STARTING) current.copy(engine = EngineStatus.FAILED) else current
            }
            return
        }
        Log.w(LOG_TAG, "engine refused a request: ${event.message}")
        val view = liveByNative[tab] ?: return
        if (view.generation != null) {
            if (event.request != null) state.update { it.copy(notice = Notice.AddressNotOpened) }
            return
        }
        val wasShown = selectedNative == view.nativeTab
        forgetView(view)
        if (wasShown) engine.showNone()
        dao.tab(view.tabId)?.let { dao.updateTab(it.copy(url = null, title = null)) }
        state.update { it.copy(notice = Notice.AddressNotOpened) }
    }

    /**
     * Brings native views in line with logical state: the focused tab, if it has an address, has
     * a renderer and is the one shown. Other renderers are throttled; they are not evicted yet.
     */
    private suspend fun reconcile() {
        if (!engineReady) return
        val tab = activeTab()
        val view = tab?.let { liveByTab[it.id] ?: it.url?.let { url -> createView(it.id, url) } }
        if (view == null) {
            // Internal home: no page may stay painted under it.
            selectedNative?.let {
                engine.setThrottled(it, true)
                engine.showNone()
            }
            selectedNative = null
            return
        }
        if (selectedNative != view.nativeTab) {
            selectedNative?.let { engine.setThrottled(it, true) }
            engine.setThrottled(view.nativeTab, false)
            engine.selectView(view.nativeTab)
            selectedNative = view.nativeTab
        }
        releaseViewsBeyond(maxLiveViews)
    }

    /**
     * Releases renderers of hidden tabs, least recently focused first, until at most [keep]
     * remain. The tabs stay open: selecting one loads its last address in a new renderer
     * (its back/forward list and page state are lost, as after a restart).
     */
    private suspend fun releaseViewsBeyond(keep: Int) {
        if (liveByTab.size <= keep) return
        val recency = dao.openTabs().mapIndexed { index, tab -> tab.id to index }.toMap()
        liveByTab.values
            // A page waiting for an answer would lose it: keep its renderer.
            .filter { it.nativeTab != selectedNative && it.controls.isEmpty() && dao.tab(it.tabId)?.url != null }
            .sortedByDescending { recency[it.tabId] ?: Int.MAX_VALUE }
            .take(liveByTab.size - keep)
            .forEach { closeView(it) }
    }

    /** Android is short of memory: keep only the renderer of the focused tab. */
    fun releaseHiddenViews() = submit { releaseViewsBeyond(keep = 1) }

    private fun createView(tabId: String, url: String): LiveView =
        LiveView(tabId, nextNativeTab++).also {
            liveByTab[tabId] = it
            liveByNative[it.nativeTab] = it
            engine.createView(it.nativeTab, url)
        }

    private suspend fun maintain() {
        val cutoff = clock.now() - retention.closedHistoryMillis
        if (unusedTabs() == UnusedTabs.CLOSE) {
            val active = dao.session()?.activeTabId
            dao.openTabs().filter { it.id != active && it.lastFocusedAt < cutoff }.forEach { closeLogically(it) }
        }
        database.withTransaction {
            dao.deleteVisitsSeenBefore(cutoff)
            dao.deleteEmptyClosedTabs()
        }
    }

    private suspend fun publish() {
        val tab = activeTab()
        // Reader view belongs to the tab it was opened for: moving to another tab leaves it.
        if (reader != null && reader?.tabId != tab?.id) reader = null
        val view = tab?.let { liveByTab[it.id] }
        val openCount = dao.openTabs().size
        // Without a committed document (restored, released or not yet loaded) the stored visit
        // still says whether the tab's page was an engine error page, whose title is not the page's.
        val loadFailed = view?.currentDocument?.let { it in view.errorDocuments }
            ?: tab?.currentVisitId?.let { dao.visit(it)?.failed }
            ?: false
        state.update {
            it.copy(
                activeTab = tab?.let { active ->
                    ActiveTab(
                        id = active.id,
                        url = active.url,
                        title = active.title,
                        loading = view?.loading ?: false,
                        canGoBack = view?.takeIf { !it.crashed }?.canGoBack ?: false,
                        canGoForward = view?.takeIf { !it.crashed }?.canGoForward ?: false,
                        crashed = view?.crashed ?: false,
                        hasView = view != null,
                        loadFailed = loadFailed,
                        openedByOtherApp = active.id == otherAppTab,
                    )
                },
                pageControl = view?.controls?.firstOrNull()?.let { shown ->
                    PageControl(shown.id, tab.url?.let(HostNames::of)?.let(HostNames::forDisplay), shown.content, shown.offerBlocking)
                },
                openTabCount = openCount,
                reader = reader,
            )
        }
    }

    private fun documentKey(view: LiveView, document: Long) = "$processKey:${view.generation}:$document"

    private fun RecentItem.matches(query: String?): Boolean {
        if (query.isNullOrBlank()) return true
        val needle = query.trim()
        return (title?.contains(needle, ignoreCase = true) ?: false) || (url?.contains(needle, ignoreCase = true) ?: false)
    }

    /** One native view incarnation for a logical tab. */
    private class LiveView(val tabId: String, val nativeTab: Long) {
        var generation: Long? = null
        var currentDocument: Long? = null
        var loading = false
        var canGoBack = false
        var canGoForward = false
        var crashed = false

        /** Engine document ID -> visit, so returning to a cached document reuses its visit. */
        val documentVisits = HashMap<Long, String>()

        var captureTimer: Job? = null

        /** Documents that are Servo's error pages (network or certificate failure). */
        val errorDocuments = HashSet<Long>()

        /** Documents that reloaded the same address and kept the earlier visit. */
        val reloadedDocuments = HashSet<Long>()

        /** Unanswered page controls, oldest first. */
        val controls = ArrayDeque<ShownControl>()
        var dialogDocument: Long? = null
        var dialogCount = 0
        var dialogsBlocked = false
    }

    private class ShownControl(val id: Long, val content: PageControlContent, val offerBlocking: Boolean)

    /** A reader-view request and what its result must still match. */
    private data class PendingArticle(val tabId: String, val view: ViewRef, val document: Long, val url: String)

    /** A capture request and everything its result must still match. */
    private data class PendingCapture(
        val tabId: String,
        val view: ViewRef,
        val document: Long,
        val url: String,
        val visitId: String?,
        val policyGeneration: Long,
    )

    private companion object {
        const val LOG_TAG = "CombrayBrowser"
        const val RECENT_LIMIT = 500
        const val PROCESS_KEY_LENGTH = 8
        /** Titles of the pages Servo commits for network and certificate failures (neterror.html, badcert.html). */
        val SERVO_ERROR_TITLES = setOf("Error loading page", "Certificate error")

        /** Proposed default until measured on the owner's phone (M4). */
        const val MAX_LIVE_VIEWS = 6

        /** Reader view budget: the article's JSON (native/src/reader.js); longer ones are cut. */
        const val MAX_ARTICLE_BYTES = 512 * 1024
        const val ARTICLE_TIMEOUT_MS = 15_000
    }
}

/** Whether open tabs the user has not used for the retention period close by themselves. */
enum class UnusedTabs { KEEP, CLOSE }

/** Proposed default: closed history kept seven days after its last interaction. */
data class Retention(val closedHistoryDays: Int = 7) {
    val closedHistoryMillis get() = closedHistoryDays * 24L * 60 * 60 * 1000
}
