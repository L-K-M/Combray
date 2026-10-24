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
import ch.lkmc.combray.engine.Appearance
import ch.lkmc.combray.engine.Attribution
import ch.lkmc.combray.engine.CaptureStatus
import ch.lkmc.combray.engine.ControlResponse
import ch.lkmc.combray.engine.EngineEvent
import ch.lkmc.combray.engine.EnginePort
import ch.lkmc.combray.engine.EngineStart
import ch.lkmc.combray.engine.FindStatus
import ch.lkmc.combray.engine.HistoryKind
import ch.lkmc.combray.engine.LoadPhase
import ch.lkmc.combray.engine.PageControlContent
import ch.lkmc.combray.engine.PageRef
import ch.lkmc.combray.engine.ScrollEdge
import ch.lkmc.combray.engine.ViewRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

enum class Placement {
    CURRENT_TAB,
    NEW_TAB,

    /**
     * A new tab right after the focused one in Recent, which keeps focus. The tab has no renderer
     * until it is selected, so its page loads when the user switches to it.
     */
    BACKGROUND_TAB,
}

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
    /** How long a request may wait for its page before the tab stops showing it as loading. */
    private val navigationTimeoutMs: Long = NAVIGATION_TIMEOUT_MS,
    /** Read when the engine becomes ready and at [applyTextSize]: the page zoom of every view. */
    private val textSize: () -> TextSize = { TextSize.DEFAULT },
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

    /** Process visibility; conservatively backgrounded until Android reports onStart. */
    private var visibility = AppVisibility.BACKGROUND
    /** The tab focused before the focused one, in this process: the quick switch returns there. */
    private var previousTabId: String? = null
    private val pendingCaptures = HashMap<Long, PendingCapture>()
    private val pendingArticles = HashMap<Long, PendingArticle>()

    /** Reader view, while open; shown only over the tab it was opened for. */
    private var reader: ReaderState? = null

    /** The article request reader view waits for; an older one's late result is ignored. */
    private var readerRequest: Long? = null

    /** Find in page while its bar is open: over one tab, in one view of it. */
    private var findSession: FindSession? = null

    /** The tab another app's link opened, until focus moves elsewhere (process memory only). */
    private var otherAppTab: String? = null

    /** The open tabs in Recent order when the current run of bar swipes began, and its last swipe. */
    private var swipeRun: List<String> = emptyList()
    private var swipeRunAt: Long? = null

    /** Tab -> the tab whose page opened it, while Back may return there (process memory only). */
    private val openers = HashMap<String, String>()

    /** What pages see as `prefers-color-scheme`; the engine gets it as soon as it runs. */
    private var appearance = Appearance.LIGHT

    /** The page field the keyboard was last requested for, while its page is the one shown. */
    private var keyboardOwner: KeyboardOwner? = null

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
                    // Restoring ends even if it was this step that failed: show what there is, not a blank page.
                    state.update {
                        // Before the session is restored the user has done nothing: it was the restore.
                        val problem = if (it.session == SessionState.RESTORING) RESTORE_FAILED else ACTION_FAILED
                        it.copy(session = SessionState.RESTORED, notice = Notice.Problem(problem))
                    }
                }
            }
        }
        submit {
            // The stored tabs are shown even if maintenance fails, and the problem is named as
            // the restore: publishing first ends RESTORING, so the queue's handler would call it
            // "the last action".
            val failure = try {
                maintain()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                failed
            }
            publish()
            if (failure != null) {
                Log.e(LOG_TAG, "restoring the session failed", failure)
                state.update { it.copy(notice = Notice.Problem(RESTORE_FAILED)) }
            }
        }
    }

    /** Resolves after everything queued before this call has been processed. */
    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        submit { done.complete(Unit) }
        done.await()
    }

    /**
     * Recent: open tabs and local history, newest first, unfiltered. [depth] picks how much
     * history: the newest [RECENT_HISTORY_LIMIT] visits to browse, or up to
     * [RETAINED_HISTORY_LIMIT] retained visits to search, so a match older than the newest rows
     * is still found. The filter runs in the caller with [matching] against one subscription per
     * depth, so typing does not restart three Room observers on every keystroke. The query reads
     * from the retention cutoff at subscription; each answer, and a slow clock while nothing
     * changes, applies the cutoff as it is then, so a visit leaves an open sheet once it passes
     * the retention period.
     */
    fun recent(depth: HistoryDepth = HistoryDepth.NEWEST): Flow<List<RecentItem>> {
        val since = clock.now() - retention.closedHistoryMillis
        val historyLimit = if (depth == HistoryDepth.NEWEST) RECENT_HISTORY_LIMIT else RETAINED_HISTORY_LIMIT
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(RECENT_CUTOFF_TICK_MS)
            }
        }
        return combine(
            dao.observeOpenTabs(),
            dao.observeHistory(since, historyLimit),
            dao.observeSession(),
            ticks,
        ) { tabs, visits, session, _ ->
            val cutoff = clock.now() - retention.closedHistoryMillis
            val items = tabs.map {
                RecentItem.OpenTab(it.id, it.url, it.title, it.lastFocusedAt, it.id == session?.activeTabId)
            } + visits.filter { it.lastSeenAt >= cutoff }.map { RecentItem.Visit(it.id, it.tabId, it.url, it.title, it.lastSeenAt) }
            items.sortedByDescending { it.time }
        }
    }

    /**
     * The sites the user visits most, for the new-tab page: one entry per site (its host without
     * "www."), most visits first among the newest [RECENT_HISTORY_LIMIT] in the retention window,
     * ties by recency, each with its newest page. The window starts when the list is collected,
     * so each new tab ranks the current one. Pages open tabs show now count, as they are what the
     * user visits; a forgotten page has no visit, so it never ranks. Nor does a page [sensitive]
     * says carries credentials: a tile names only the site, and opening it must not replay a
     * sign-in or token link.
     */
    fun topSites(sensitive: (url: String) -> Boolean, limit: Int = TOP_SITES_LIMIT): Flow<List<TopSite>> = flow {
        val since = clock.now() - retention.closedHistoryMillis
        emitAll(
            dao.observeRecentVisits(since, RECENT_HISTORY_LIMIT).map { visits ->
                visits
                    .filterNot { sensitive(it.url) }
                    .mapNotNull { visit -> visit.host?.let { it.removePrefix(WWW) to visit } }
                    // Grouping keeps the newest-first order: each site's first page is its newest,
                    // and the stable sort below leaves sites with as many visits in recency order.
                    .groupBy({ it.first }, { it.second })
                    .map { (site, pages) -> TopSite(site, pages.first().url, pages.size) }
                    .sortedByDescending { it.visits }
                    .take(limit)
            },
        )
    }

    // --- Commands -------------------------------------------------------------------------------

    /** Opens [url] (already classified as a web address or search) in the focused, a new or a background tab. */
    fun openUrl(url: String, placement: Placement) = submit {
        // With no tab in focus there is nothing to stay on: every placement opens a focused tab.
        val active = activeTab()
        if (active == null) {
            createTab(url)
        } else {
            // A subject, so a new Placement does not compile until it is handled here.
            when (placement) {
                Placement.CURRENT_TAB -> navigateTab(active, url)
                Placement.NEW_TAB -> createTab(url)
                Placement.BACKGROUND_TAB -> createBackgroundTab(url, active)
            }
        }
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
     * Opens a link from the page in [openerTabId] (its link menu) in a new tab. Like a window the
     * page opened, Back on the new tab's first page returns to the opener ([returnToOpener]).
     */
    fun openUrlFromTab(url: String, openerTabId: String) = submit {
        createTab(url, openerTabId)
        reconcile()
        publish()
    }

    /**
     * Back on the first page of a tab another tab's page opened: the tab closes without an Undo
     * notice and its opener gets focus; its page stays in Recent as history. Nothing closes when
     * the opener is gone, so Back never closes a tab with nowhere to return to.
     */
    fun returnToOpener(tabId: String) = submit {
        val openerId = openers[tabId] ?: return@submit
        val tab = activeTab()?.takeIf { it.id == tabId } ?: return@submit
        val opener = dao.tab(openerId)?.takeIf { it.state == TabState.OPEN } ?: return@submit
        // Focus first: the closing tab then needs no successor, which could be some other tab.
        focus(opener)
        closeLogically(dao.tab(tab.id) ?: tab)
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
            // A forgotten page stays forgotten until another document commits ([onHistory]): this
            // request may never commit (a refusal, a 204, a download), and until it does the
            // forgotten page is still current and can reload or change its own address.
            val request = nextRequest++
            engine.navigate(view.nativeTab, request, url)
            awaitCommit(view)
            view.awaitedRequest = request
            view.awaitedUrl = url
            return
        }
        // No renderer yet (new tab, restored tab): the request becomes the recovery address.
        view?.let { closeView(it) }
        detachCurrentPage(tab, url)
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

    /**
     * Quick switch: focuses the tab the user left for the focused one. A tab opened in the
     * background ranks right behind the focused tab in Recent without having been used, so the
     * runner-up by `lastFocusedAt` is only the fallback after a restart, skipping such tabs
     * ([createBackgroundTab] ranks them before their creation time).
     */
    fun focusPreviousTab() = submit {
        val active = activeTab()
        val open = dao.openTabs().filter { it.id != active?.id }
        val previous = open.firstOrNull { it.id == previousTabId }
            ?: open.firstOrNull { it.lastFocusedAt >= it.createdAt }
            ?: open.firstOrNull()
            ?: return@submit
        focus(previous)
        reconcile()
        publish()
    }

    /**
     * A swipe along the bottom bar: focuses the [step] neighbour of the focused tab in Recent
     * order. Focusing reorders Recent, so a run of swipes walks the order as the run found it:
     * repeated swipes go further back instead of flipping between the two newest tabs. A pause
     * of [SWIPE_RUN_MS] starts the next run from the order as it is then.
     */
    fun stepTab(step: TabStep) = submit {
        val now = clock.now()
        val open = dao.openTabs()
        if (swipeRunAt.let { it == null || now - it > SWIPE_RUN_MS }) swipeRun = open.map { it.id }
        swipeRunAt = now
        // Tabs closed during the run drop out; tabs opened during it wait for the next run.
        val order = swipeRun.filter { id -> open.any { it.id == id } }
        val index = order.indexOf(activeTab()?.id)
        val target = when (step) {
            TabStep.OLDER -> order.getOrNull(index + 1)
            TabStep.NEWER -> if (index > 0) order[index - 1] else null
        } ?: return@submit
        focus(open.first { it.id == target })
        reconcile()
        publish()
    }

    /** Closes [tabId]; its pages stay in Recent as history. Focus moves to the last used tab. */
    fun closeTab(tabId: String) = submit {
        val tab = dao.tab(tabId)?.takeIf { it.state == TabState.OPEN } ?: return@submit
        val wasActive = closeLogically(tab)
        state.update { it.copy(notice = Notice.TabClosed(tab.id, tab.title, wasActive)) }
        reconcile()
        publish()
    }

    /**
     * Undo for [closeTab]: the same logical tab, reloaded at its last address (no back stack).
     * [focus] says whether it becomes the focused tab again ([Notice.TabClosed.undoFocus]).
     */
    fun reopenTab(tabId: String, focus: ReopenFocus) = submit {
        val tab = dao.tab(tabId)?.takeIf { it.state == TabState.CLOSED } ?: return@submit
        val now = clock.now()
        database.withTransaction {
            if (focus == ReopenFocus.KEEP_CURRENT) {
                // Closed in the background: back in its place among the open tabs, loaded once selected.
                dao.updateTab(tab.copy(state = TabState.OPEN, closedAt = null))
                return@withTransaction
            }
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

    /** A late tap must not scroll a different tab, a reloaded document or the page under reader view. */
    fun scrollToEdge(page: PageRef, edge: ScrollEdge) = submit {
        val view = view(page.view) ?: return@submit
        if (view.nativeTab != selectedNative || view.currentDocument != page.document || view.crashed || reader != null) return@submit
        engine.scrollToEdge(page, edge)
    }

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
            else -> {
                engine.reload(view.nativeTab)
                awaitCommit(view)
            }
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
            dao.detachVisitsForHost(site, if (includeSubdomains) ".$site" else null)
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

    /** The text size setting changed: every page, open or opened later, lays out at it. */
    fun applyTextSize() = submit {
        if (engineReady) engine.setPageZoom(textSize().zoom)
    }

    /**
     * Process visibility from the lifecycle owner: while backgrounded the shown renderer is
     * throttled too (hidden ones already are). Purely a hint; no logical state changes. Servo
     * 0.6.0 drops a throttle sent before a view's first document and lifts it for each new
     * document, so a backgrounded page is not reliably slowed yet (#88 investigates).
     */
    fun setAppVisibility(visibility: AppVisibility) = submit {
        if (visibility == this.visibility) return@submit
        this.visibility = visibility
        selectedNative?.let { engine.setThrottled(it, visibility == AppVisibility.BACKGROUND) }
    }

    /** Pages follow the app's light or dark theme, like the chrome around them. */
    fun setAppearance(appearance: Appearance) = submit {
        if (appearance == this.appearance) return@submit
        this.appearance = appearance
        if (engineReady) engine.setAppearance(appearance)
    }

    /**
     * Opens reader view for the focused tab: the engine reads the main article of the document
     * shown now, in that document only.
     */
    fun openReader() = submit {
        val tab = activeTab() ?: return@submit
        // Reader view covers the page find searches.
        closeFindSession()
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

    /** Opens find in page over the focused tab's page; not while reader view covers it. */
    fun openFind() = submit {
        if (reader != null) return@submit
        val tab = activeTab() ?: return@submit
        val view = liveByTab[tab.id]?.takeIf { !it.crashed && it.currentDocument != null } ?: return@submit
        val open = findSession
        if (open != null && open.tabId == tab.id && open.nativeTab == view.nativeTab) return@submit
        closeFindSession()
        findSession = FindSession(tab.id, view.nativeTab)
        publish()
    }

    /**
     * Searches the page find was opened on for [query]. A new query starts at its first match (or,
     * with [FindStep.PREVIOUS], its last); [FindStep.NEXT] and [FindStep.PREVIOUS] step through the
     * current one, wrapping around. Only the newest request's answer counts, and only while the
     * document it searched is still shown.
     */
    fun find(query: String, step: FindStep) = submit {
        val session = findSession ?: return@submit
        val view = liveByNative[session.nativeTab]?.takeIf { it.tabId == session.tabId && !it.crashed } ?: return@submit
        val generation = view.generation ?: return@submit
        val document = view.currentDocument ?: return@submit
        val text = boundedFindQuery(query)
        if (text.isBlank()) {
            // Nothing to look for: the page's find selection goes.
            session.searched?.let(engine::clearFind)
            session.reset(text)
            publish()
            return@submit
        }

        val current = (session.outcome as? FindOutcome.Found)?.takeIf { text == session.query }
        val index = findIndex(step, current)
        val request = nextRequest++
        val page = PageRef(ViewRef(view.nativeTab, generation), document)
        session.pending = PendingFind(request, page.view, document)
        // A new query shows nothing until the page answers; stepping keeps the last answer meanwhile.
        if (text != session.query) session.outcome = null
        session.query = text
        session.searched = page
        engine.find(page, request, text, index)
        publish()
    }

    /** Closes find in page and removes its selection from the page. */
    fun closeFind() = submit {
        closeFindSession()
        publish()
    }

    /** Answers [control]; ignored if the page withdrew it or its tab closed meanwhile. */
    fun answerPageControl(control: Long, response: ControlResponse, blocking: DialogBlocking = DialogBlocking.KEEP_SHOWING) = submit {
        val view = liveByTab.values.firstOrNull { live -> live.controls.any { it.id == control } } ?: return@submit
        val answered = view.controls.first { it.id == control }
        view.controls.remove(answered)
        // Only where the dialog offered it: a stray request must not silence the page's dialogs.
        if (blocking == DialogBlocking.BLOCK_FOR_PAGE && answered.offerBlocking) view.dialogsBlocked = true
        // Stay cancels the navigation or reload the page asked about: nothing will commit (#92).
        if (answered.content is PageControlContent.LeavePage && response == ControlResponse.Dismiss) view.awaitingCommit = false
        engine.respondToControl(control, response)
        publish()
    }

    /** Clears [notice] (or an equal duplicate); a different one posted while the UI showed it is kept. */
    fun noticeShown(notice: Notice) = state.update { if (it.notice == notice) it.copy(notice = null) else it }

    /** Clears [request] (or an equal duplicate); focus moving to the next field posts a different one meanwhile. */
    fun keyboardHandled(request: KeyboardRequest) = state.update { if (it.keyboard == request) it.copy(keyboard = null) else it }

    /** Clears [link] once the UI handed it to Android; a newer tap's link is kept. */
    fun externalLinkHandled(link: ExternalLink) = state.update { if (it.externalLink == link) it.copy(externalLink = null) else it }

    // --- Engine events ----------------------------------------------------------------------------

    private fun onEngineEvent(event: EngineEvent) = submit {
        when (event) {
            is EngineEvent.EngineReady -> {
                engineReady = true
                // Before any view exists, so the first page lays out at the chosen size.
                engine.setPageZoom(textSize().zoom)
                state.update { it.copy(engine = EngineStatus.READY) }
                // Before reconcile creates views: a dark page must not start light.
                engine.setAppearance(appearance)
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
            is EngineEvent.FindResult -> onFindResult(event)
            is EngineEvent.TitleChanged -> view(event.view)?.let { onTitle(it, event) }
            // Said once per document: a page that keeps trying must not keep a notice up.
            is EngineEvent.PopupBlocked -> view(event.view)?.takeIf { it.nativeTab == selectedNative }?.let {
                if (it.popupNoticeDocument != it.currentDocument) {
                    it.popupNoticeDocument = it.currentDocument
                    state.update { s -> s.copy(notice = Notice.PopupBlocked) }
                }
            }
            // The engine sends these only right after the user's tap; still, only the shown page's.
            is EngineEvent.ExternalLink -> view(event.view)?.takeIf { it.nativeTab == selectedNative }?.let {
                val link = ExternalLink(nextRequest++, event.url)
                state.update { it.copy(externalLink = link) }
            }
            is EngineEvent.ImeShown -> view(event.view)?.takeIf { it.nativeTab == selectedNative }?.let { view ->
                // Only a field that opens the keyboard owns it: one the next document focuses on
                // load must not keep the previous document's keyboard through the navigation.
                if (event.showKeyboard) keyboardOwner = KeyboardOwner(view.nativeTab, event.document)
                val request = if (event.showKeyboard) KeyboardRequest.Show(event.kind) else KeyboardRequest.Prepare(event.kind)
                state.update { it.copy(keyboard = request) }
            }
            is EngineEvent.ImeHidden -> view(event.view)?.takeIf { it.nativeTab == selectedNative }?.let {
                keyboardOwner = null
                state.update { it.copy(keyboard = KeyboardRequest.Hide) }
            }
            is EngineEvent.Crashed -> view(event.view)?.let {
                Log.w(LOG_TAG, "renderer crashed: ${event.reason}")
                it.crashed = true
                it.loading = false
                it.awaitingCommit = false
                it.controls.clear()
                it.dialogDocument = null
                it.dialogCount = 0
                it.dialogsBlocked = false
            }
            is EngineEvent.MediaBlocked -> view(event.view)?.let { onMediaBlocked(it, event) }
            is EngineEvent.ControlShown -> view(event.view)?.let { onControlShown(it, event) }
            is EngineEvent.ControlHidden -> view(event.view)?.let { live ->
                val hidden = live.controls.filter { it.id == event.control }
                live.controls.removeAll(hidden)
                // The engine answers an unanswered leave-page question Stay (its deadline, or the
                // tab left the screen), or drops it as the page commits; either way stop waiting.
                if (hidden.any { it.content is PageControlContent.LeavePage }) live.awaitingCommit = false
            }
            is EngineEvent.EngineError -> onEngineError(event)
            // Heartbeats, echoes, navigation confirmations and diagnostics cannot change what
            // publish() reads, so they skip its three Room reads on this serialized queue
            // (Painted alone arrives once per second per painting tab, and user commands wait
            // behind those reads). TabSelected only echoes a selection the coordinator made:
            // the engine emits it solely from its SelectTab command handler (engine.rs).
            // Every kind is listed, so a new EngineEvent does not compile until it is handled
            // above or placed here. Placing one here means its handling must never touch the
            // tab projection, reader state, live views or any table publish() reads.
            is EngineEvent.Painted,
            is EngineEvent.TabSelected,
            is EngineEvent.SurfaceBound,
            is EngineEvent.SurfaceUnbound,
            is EngineEvent.NavigationRequested,
            is EngineEvent.PageNavigationRequested,
            is EngineEvent.EvaluationResult,
            is EngineEvent.LateCaptureResult,
            is EngineEvent.ProfilePersisted,
            is EngineEvent.ShutdownComplete,
            is EngineEvent.Other,
            -> return@submit
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

    /** The engine reports each document once. Only the page on screen gets a notice: for a hidden tab or a replaced page it would name the wrong page. */
    private fun onMediaBlocked(view: LiveView, event: EngineEvent.MediaBlocked) {
        if (view.nativeTab != selectedNative || view.currentDocument != event.document) return
        state.update { it.copy(notice = Notice.MediaUnsupported) }
    }

    private suspend fun onHistory(view: LiveView, reported: EngineEvent.HistoryChanged) {
        // A tab's and a visit's URL are shown, shared, searched and reopened: never store sign-in credentials in them.
        val event = reported.copy(url = HostNames.withoutCredentials(reported.url))
        view.canGoBack = event.canGoBack
        view.canGoForward = event.canGoForward
        // Servo hides nothing when a navigation replaces the field's document (a form sent with
        // Enter). A field of the new document may have asked for the keyboard before its commit.
        val owner = keyboardOwner
        if (event.kind == HistoryKind.DOCUMENT_COMMITTED && owner?.nativeTab == view.nativeTab && owner.document != event.document) {
            releaseKeyboard(view.nativeTab)
        }
        val tab = dao.tab(view.tabId)?.takeIf { it.state == TabState.OPEN } ?: return
        val now = clock.now()

        // Before this event: a view that has shown nothing yet is loading its tab's page again.
        val firstCommit = view.currentDocument == null

        database.withTransaction {
            val current = tab.currentVisitId?.let { dao.visit(it) }
            // A page of a forgotten site stays open without a visit. Loading it again in a new
            // renderer (restore, eviction, a crash), reloading it or moving within it records
            // nothing (and requestCapture sends nothing); another page committing in the tab ends that.
            // A new renderer's first page is the forgotten one unless the user typed another first.
            val forgotten = tab.pageForgotten && when (event.kind) {
                HistoryKind.DOCUMENT_COMMITTED -> event.url == tab.url || (firstCommit && view.awaitedUrl == null)
                HistoryKind.SAME_DOCUMENT, HistoryKind.LIST_UPDATED -> true
            }
            // A single-page app can replace the article without replacing its document.
            if (reader?.tabId == view.tabId && (event.kind == HistoryKind.DOCUMENT_COMMITTED || event.url != tab.url)) {
                reader = null
            }
            val visit = when (event.kind) {
                HistoryKind.DOCUMENT_COMMITTED -> {
                    view.awaitingCommit = false
                    view.currentDocument = event.document
                    // Find searched the replaced document, and its selection went with it.
                    if (findSession?.tabId == view.tabId) findSession = null
                    // A cached document (back/forward) reports no load progress: it is loaded.
                    view.loading = event.document !in view.documentVisits
                    scheduleCapture(view, event.document, captureTiming.fallbackMs)
                    if (forgotten) null else committedVisit(view, tab, current, event, now)
                }
                HistoryKind.SAME_DOCUMENT -> {
                    // Only an awaited address within this page (a typed "#part") commits this way:
                    // the old page rewriting its own address does not answer the request.
                    if (view.awaitedUrl?.let { isSameDocumentTarget(it, event.url) } == true) view.awaitingCommit = false
                    scheduleCapture(view, event.document, captureTiming.debounceMs)
                    if (forgotten) null else sameDocumentVisit(view, tab, current, event, now)
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
            // A forgotten page stays open without a visit, under its last title.
            val title = if (visit == null && (forgotten || event.kind == HistoryKind.LIST_UPDATED)) tab.title else visit?.title
            dao.updateTab(
                tab.copy(currentVisitId = visit?.id, url = event.url, title = title, lastFocusedAt = lastFocusedAt, pageForgotten = forgotten),
            )
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
            // The visit as it was before this reload, which its title reports then rewrite.
            view.reloadBaselines.getOrPut(event.document) { ReloadBaseline(current.title, current.failed) }
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
        // Same document, same title; a forgotten page has no visit left, so the tab keeps it.
        return newVisit(tab, event.url, documentKey(view, event.document), VisitKind.SAME_DOCUMENT, now)
            .copy(title = current?.title ?: tab.title)
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
            val baseline = view.reloadBaselines[event.document]
            val updated = when {
                // A failed reload must not turn the page's earlier successful visit into a failure.
                errorPage && baseline != null && !baseline.failed ->
                    visit.copy(title = baseline.title, failed = false)
                // Servo reports a null, then an empty title for every new document before its own:
                // they must not erase the title of a visit a reload reused. A failed visit's title
                // is Servo's error page's, so it goes.
                title == null && !visit.failed -> visit
                else -> visit.copy(title = title, failed = errorPage)
            }
            if (updated != visit) dao.updateVisit(updated)
            val tab = dao.tab(view.tabId) ?: return@withTransaction
            // The tab names what it shows, Servo's error page included.
            if (tab.currentVisitId == visit.id) dao.updateTab(tab.copy(title = title ?: updated.title))
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
        val tabId = createTab(url = null, openerTabId = opener.tabId)
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

    /**
     * The app asked [view] for a page: the tab shows it loading until a document commits, which
     * for a slow server can take seconds after the tap. A request that never commits (a 204
     * answer, a download) stops showing after [navigationTimeoutMs].
     */
    private fun awaitCommit(view: LiveView) {
        view.awaitingCommit = true
        view.awaitedRequest = null
        view.awaitedUrl = null
        val request = ++view.commitRequests
        scope.launch {
            delay(navigationTimeoutMs)
            submit {
                if (view.commitRequests != request || !view.awaitingCommit) return@submit
                view.awaitingCommit = false
                publish()
            }
        }
    }

    private suspend fun requestCapture(view: LiveView, document: Long) {
        val sink = capture ?: return
        // Only the document still current in a live, healthy view: anything else is stale.
        if (liveByNative[view.nativeTab] !== view || view.currentDocument != document || view.crashed) return
        // Servo's error pages carry the failed address but are not that page's content.
        if (document in view.errorDocuments) return
        val generation = view.generation ?: return
        // A forgotten page is not sent again while its tab shows it ([TabEntity.pageForgotten]).
        val tab = dao.tab(view.tabId)?.takeIf { it.state == TabState.OPEN && !it.pageForgotten } ?: return
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
        // A capture that was on its way when the site was forgotten is dropped with it.
        val tab = dao.tab(view.tabId)?.takeIf { it.state == TabState.OPEN && it.url == pending.url && !it.pageForgotten } ?: return
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

    /** Shows the page's answer if it is for the newest request and its document is still shown. */
    private fun onFindResult(event: EngineEvent.FindResult) {
        val session = findSession ?: return
        val pending = session.pending?.takeIf { it.request == event.request } ?: return
        if (event.view != pending.view || event.document != pending.document) return
        if (view(event.view)?.currentDocument != pending.document) return
        session.pending = null
        val index = event.index
        session.outcome = when (event.status) {
            FindStatus.FOUND -> when {
                event.count == 0 -> FindOutcome.NotFound(event.capped)
                index != null && index in 0 until event.count -> FindOutcome.Found(event.count, index, event.capped)
                else -> FindOutcome.Failed
            }
            FindStatus.FAILED -> FindOutcome.Failed
            // The document or view changed meanwhile; whatever changed it ends find.
            FindStatus.STALE_DOCUMENT, FindStatus.TAB_CLOSED -> return
        }
    }

    /** Ends find in page; its selection is removed where the view still shows that page. */
    private fun closeFindSession() {
        val session = findSession ?: return
        findSession = null
        val searched = session.searched ?: return
        liveByNative[session.nativeTab]?.takeIf { it.tabId == session.tabId && !it.crashed } ?: return
        engine.clearFind(searched)
    }

    /** Whether [reported], a same-document change, is [awaited]: the same address up to a fragment. */
    private fun isSameDocumentTarget(awaited: String, reported: String): Boolean =
        '#' in awaited && awaited.substringBefore('#') == reported.substringBefore('#')

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

    private suspend fun createTab(url: String?, openerTabId: String? = null): String {
        val now = clock.now()
        val tab = newOpenTab(url, createdAt = now, lastFocusedAt = now)
        // Recorded before focus moves to the new tab, so that keeps the opener's own way back;
        // only for an opener still open (a page window can race its opener's close).
        openerTabId?.takeIf { dao.tab(it)?.state == TabState.OPEN }?.let { openers[tab.id] = it }
        database.withTransaction {
            dao.insertTab(tab)
            activate(tab.id, now)
        }
        return tab.id
    }

    /**
     * A tab for [url] that does not take focus. [active] was in use until now, like a tab focus
     * leaves ([activate]), and the new tab just before, so Recent lists it right after [active]
     * and a later one before it. No renderer is created here ([reconcile] serves the focused tab
     * only): the page loads when the user switches to the tab.
     */
    private suspend fun createBackgroundTab(url: String, active: TabEntity) {
        val now = clock.now()
        val tab = newOpenTab(url, createdAt = now, lastFocusedAt = now - 1)
        database.withTransaction {
            dao.updateTab(active.copy(lastFocusedAt = now))
            dao.insertTab(tab)
        }
        state.update { it.copy(notice = Notice.OpenedInBackground(tab.id)) }
    }

    private fun newOpenTab(url: String?, createdAt: Long, lastFocusedAt: Long) = TabEntity(
        id = newId(),
        state = TabState.OPEN,
        currentVisitId = null,
        url = url,
        title = null,
        createdAt = createdAt,
        lastFocusedAt = lastFocusedAt,
        closedAt = null,
    )

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
            previousTabId = previous
        }
        dao.setSession(SessionEntity(activeTabId = tabId))
        if (tabId != otherAppTab) otherAppTab = null
        keepOpenersFor(tabId)
    }

    /**
     * Focus moved to [tabId]. Opener relations survive within its chain: on its way back (the tab,
     * its opener, that tab's opener, ...) and below it (tabs whose way back passes [tabId]). A
     * chain of page-opened tabs unwinds one Back at a time, and focusing a tab outside it ends the
     * relation, as for [otherAppTab].
     */
    private fun keepOpenersFor(tabId: String?) {
        // Bounded: an opener always existed before its tab, but a cycle must not hang the queue.
        val bound = openers.size + 1
        fun wayBack(from: String?) = generateSequence(from) { openers[it] }.take(bound)
        val onWayBack = wayBack(tabId).toSet()
        // Decided before removing anything, so no chain is walked while it changes.
        val ended = openers.keys.filter { child -> child !in onWayBack && wayBack(child).none { it == tabId } }
        ended.forEach(openers::remove)
    }

    /**
     * Close and successor selection in one transaction (invariant 5). The closed page takes its
     * place in history by when the tab was last used, not by when it closed: closing a tab left
     * long ago does not move it to the top of Recent, and retention counts from that last use.
     * The focused tab was in use until now, like a tab focus leaves ([activate]); the successor
     * is in use from now. Returns whether [tab] was the focused tab.
     */
    private suspend fun closeLogically(tab: TabEntity): Boolean {
        val now = clock.now()
        val wasFocused = database.withTransaction {
            val focused = dao.session()?.activeTabId == tab.id
            tab.currentVisitId?.let { dao.visit(it) }?.let { retireVisit(tab, it, now) }
            dao.updateTab(tab.copy(state = TabState.CLOSED, closedAt = now))
            if (focused) {
                val successor = dao.openTabs().firstOrNull()
                successor?.let { dao.updateTab(it.copy(lastFocusedAt = now)) }
                dao.setSession(SessionEntity(activeTabId = successor?.id))
                keepOpenersFor(successor?.id)
            }
            focused
        }
        liveByTab[tab.id]?.let { closeView(it) }
        if (otherAppTab == tab.id) otherAppTab = null
        openers.entries.removeAll { (child, opener) -> child == tab.id || opener == tab.id }
        return wasFocused
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
        releaseKeyboard(view.nativeTab)
        if (selectedNative == view.nativeTab) selectedNative = null
    }

    /**
     * Hides the keyboard if a field of [nativeTab]'s page has it: that page is no longer shown
     * or no longer exists. Servo itself hides it only when the field loses focus.
     */
    private fun releaseKeyboard(nativeTab: Long) {
        if (keyboardOwner?.nativeTab != nativeTab) return
        keyboardOwner = null
        state.update { it.copy(keyboard = KeyboardRequest.Hide) }
    }

    /** The page closed its own window: the tab closes as if the user had closed it. */
    private suspend fun onClosedByPage(view: LiveView) {
        // A window that never showed a page (one a page opened for an app link, say) closes
        // without a "Tab closed" notice: there is nothing the user saw to bring back.
        val shownAPage = view.currentDocument != null
        forgetView(view)
        val tab = dao.tab(view.tabId)?.takeIf { it.state == TabState.OPEN } ?: return
        val wasActive = closeLogically(tab)
        if (shownAPage) state.update { it.copy(notice = Notice.TabClosed(tab.id, tab.title, wasActive)) }
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
            if (event.request != null) {
                // A refusal of an earlier address must not end the wait for a newer one. The
                // refused address never commits, so it no longer marks a typed navigation.
                if (event.request == view.awaitedRequest) {
                    view.awaitingCommit = false
                    view.awaitedUrl = null
                }
                state.update { it.copy(notice = Notice.AddressNotOpened) }
            }
            return
        }
        val wasShown = selectedNative == view.nativeTab
        forgetView(view)
        if (wasShown) engine.showNone()
        dao.tab(view.tabId)?.let { detachCurrentPage(it, null) }
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
                releaseKeyboard(it)
                engine.setThrottled(it, true)
                engine.showNone()
            }
            selectedNative = null
            return
        }
        if (selectedNative != view.nativeTab) {
            selectedNative?.let {
                releaseKeyboard(it)
                engine.setThrottled(it, true)
            }
            engine.setThrottled(view.nativeTab, visibility == AppVisibility.BACKGROUND)
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
            awaitCommit(it)
        }

    /**
     * The tab stops displaying its current page and shows [recoveryUrl] (or the internal home):
     * that page becomes plain Recent history again, retired with the tab's last use like a page
     * left by navigating (#71). Recommitting a document sets `currentVisitId` again in [onHistory].
     */
    private suspend fun detachCurrentPage(tab: TabEntity, recoveryUrl: String?) {
        tab.currentVisitId?.let { dao.visit(it) }?.let { retireVisit(tab, it, clock.now()) }
        dao.updateTab(tab.copy(url = recoveryUrl, title = null, currentVisitId = null, pageForgotten = false))
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
        // Find belongs to the page it was opened on: another tab, or a released or crashed view, ends it.
        findSession?.let { session ->
            val live = liveByTab[session.tabId]
            if (session.tabId != tab?.id || live == null || live.nativeTab != session.nativeTab || live.crashed) closeFindSession()
        }
        val view = tab?.let { liveByTab[it.id] }
        val openCount = dao.openTabCount()
        val returnsToOpener = tab?.let { openers[it.id] }?.let { dao.tab(it)?.state == TabState.OPEN } ?: false
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
                        loading = view?.let { it.loading || it.awaitingCommit } ?: false,
                        canGoBack = view?.takeIf { !it.crashed }?.canGoBack ?: false,
                        canGoForward = view?.takeIf { !it.crashed }?.canGoForward ?: false,
                        crashed = view?.crashed ?: false,
                        hasView = view != null,
                        loadFailed = loadFailed,
                        openedByOtherApp = active.id == otherAppTab,
                        returnsToOpener = returnsToOpener,
                        page = view?.takeIf { !it.crashed }?.let { live ->
                            val generation = live.generation ?: return@let null
                            live.currentDocument?.let { PageRef(ViewRef(live.nativeTab, generation), it) }
                        },
                    )
                },
                pageControl = view?.controls?.firstOrNull()?.let { shown ->
                    PageControl(shown.id, tab.url?.let(HostNames::of)?.let(HostNames::forDisplay), shown.content, shown.offerBlocking)
                },
                openTabCount = openCount,
                reader = reader,
                // Every publish reads the stored session, so the first one ends restoring.
                session = SessionState.RESTORED,
                find = findSession?.state(),
            )
        }
    }

    private fun documentKey(view: LiveView, document: Long) = "$processKey:${view.generation}:$document"

    /** One native view incarnation for a logical tab. */
    private class LiveView(val tabId: String, val nativeTab: Long) {
        var generation: Long? = null
        var currentDocument: Long? = null
        var loading = false

        /** The app requested a page that has not committed yet (see awaitCommit). */
        var awaitingCommit = false
        var commitRequests = 0L

        /** The engine request of the awaited address, so only its refusal ends the wait. */
        var awaitedRequest: Long? = null

        /** The awaited address, when the app navigated to one; a reload or a new view awaits none. */
        var awaitedUrl: String? = null
        var canGoBack = false
        var canGoForward = false
        var crashed = false

        /** Engine document ID -> visit, so returning to a cached document reuses its visit. */
        val documentVisits = HashMap<Long, String>()

        var captureTimer: Job? = null

        /** Documents that are Servo's error pages (network or certificate failure). */
        val errorDocuments = HashSet<Long>()

        /** Documents that reloaded the same address and kept the earlier visit, with its prior state. */
        val reloadBaselines = HashMap<Long, ReloadBaseline>()

        /** Unanswered page controls, oldest first. */
        val controls = ArrayDeque<ShownControl>()
        var dialogDocument: Long? = null
        var dialogCount = 0
        var dialogsBlocked = false

        /** The document a blocked window was last reported for. */
        var popupNoticeDocument: Long? = null
    }

    private class ShownControl(val id: Long, val content: PageControlContent, val offerBlocking: Boolean)

    /** A reused visit's title and failure before a same-URL reload's title reports changed it. */
    private data class ReloadBaseline(val title: String?, val failed: Boolean)

    /** Find in page over [tabId], in its view [nativeTab]; another view of the tab ends it. */
    private class FindSession(val tabId: String, val nativeTab: Long) {
        var query = ""
        var outcome: FindOutcome? = null

        /** The newest request; an older one's answer is ignored. */
        var pending: PendingFind? = null

        /** The document find last searched: it may hold find's selection, so closing clears it. */
        var searched: PageRef? = null

        fun reset(query: String) {
            this.query = query
            outcome = null
            pending = null
            searched = null
        }

        fun state() = FindState(query, outcome)
    }

    /** A find request and what its answer must still match. */
    private data class PendingFind(val request: Long, val view: ViewRef, val document: Long)

    /** A page field with the keyboard: its view and its document (its frame's, in a frame). */
    private class KeyboardOwner(val nativeTab: Long, val document: Long)

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
        const val ACTION_FAILED = "Something went wrong; the last action may not have completed."
        const val RESTORE_FAILED = "Your open tabs could not be fully restored."
        const val PROCESS_KEY_LENGTH = 8

        /** How long a pause between bar swipes keeps them one run over the same order. */
        const val SWIPE_RUN_MS = 3_000L
        /** Titles of the pages Servo commits for network and certificate failures (neterror.html, badcert.html). */
        val SERVO_ERROR_TITLES = setOf("Error loading page", "Certificate error")

        /** Proposed default until measured on the owner's phone (M4). */
        const val MAX_LIVE_VIEWS = 6

        /** Quick-link tiles on the new-tab page: two rows of three. */
        const val TOP_SITES_LIMIT = 6
        /** The prefix many sites are also reached under; both spellings rank as one site. */
        const val WWW = "www."

        /** Reader view budget: the article's JSON (native/src/reader.js); longer ones are cut. */
        const val MAX_ARTICLE_BYTES = 512 * 1024
        const val ARTICLE_TIMEOUT_MS = 15_000

        /** Proposed default: well past a slow home server, short of a stuck bar. */
        const val NAVIGATION_TIMEOUT_MS = 30_000L

        /** How often an open Recent applies the retention cutoff when nothing else changes. */
        const val RECENT_CUTOFF_TICK_MS = 60_000L

        /**
         * Visits a search (Recent, the address sheet) reads at most, newest first. Far more than a
         * week of browsing, so an old match is still found, but bounded: a page can add a visit
         * per pushState, and every new visit makes an open search read its rows again.
         */
        const val RETAINED_HISTORY_LIMIT = 5_000
    }
}

/** Whether open tabs the user has not used for the retention period close by themselves. */
enum class UnusedTabs { KEEP, CLOSE }

/** Proposed default: closed history kept seven days after its last interaction. */
data class Retention(val closedHistoryDays: Int = 7) {
    val closedHistoryMillis get() = closedHistoryDays * 24L * 60 * 60 * 1000
}
