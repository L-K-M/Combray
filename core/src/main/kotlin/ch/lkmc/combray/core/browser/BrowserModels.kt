package ch.lkmc.combray.core.browser

import ch.lkmc.combray.engine.PageControlContent

/** Wall-clock time in UTC milliseconds; injectable for retention tests. */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

enum class EngineStatus {
    STARTING,

    /** Servo runs and accepts views (after the first surface). */
    READY,

    /** The APK has no native engine library; browsing is impossible. */
    LIBRARY_MISSING,
    FAILED,
}

/** What the chrome shows for the focused tab. */
data class ActiveTab(
    val id: String,
    /** Last committed address, or the address being opened in a tab without a renderer yet. */
    val url: String?,
    val title: String?,
    val loading: Boolean,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    /** The renderer for this tab crashed; reloading creates a new one. */
    val crashed: Boolean,
    /** A native view exists (it may not have committed a page yet, like a new window). */
    val hasView: Boolean,
    /** The page shown is Servo's error page for this address (network or certificate failure). */
    val loadFailed: Boolean = false,
    /** Another app's link opened this tab and the user has not moved to another tab since. */
    val openedByOtherApp: Boolean = false,
    /** A page in another tab opened this one, and Back on its first page returns there ([BrowserCoordinator.returnToOpener]). */
    val returnsToOpener: Boolean = false,
) {
    /** The internal new-tab state: no page, nothing for the engine to show. */
    val isHome get() = url == null && !hasView
}

/** A short notice the UI shows once, optionally with an action. */
sealed interface Notice {
    /** [wasActive]: it was the focused tab when it closed. */
    data class TabClosed(val tabId: String, val title: String?, val wasActive: Boolean) : Notice {
        /** Undo focuses the tab again only if it was focused. */
        val undoFocus get() = if (wasActive) ReopenFocus.FOCUS else ReopenFocus.KEEP_CURRENT
    }

    data class Problem(val message: String) : Notice

    /** The engine could not open an address (it does not parse as a URL Servo can load). */
    data object AddressNotOpened : Notice

    /** A link opened in a tab that did not take focus ([Placement.BACKGROUND_TAB]). */
    data class OpenedInBackground(val tabId: String) : Notice
}

/** Whether Undo of a tab close moves focus to the reopened tab. */
enum class ReopenFocus {
    FOCUS,

    /** The tab was closed in the background: focus stays on the current tab. */
    KEEP_CURRENT,
}

sealed interface KeyboardRequest {
    /** A page field took focus; [kind] is its type ("text", "password", "email", ...). */
    data class Show(val kind: String) : KeyboardRequest

    data object Hide : KeyboardRequest
}

/**
 * Something the focused page asks: a `<select>` choice, a script dialog or files to upload.
 * Its text is page content and must not pass for the browser's own UI.
 */
data class PageControl(
    val id: Long,
    /** Display host of the asking page, or null when the page has no address yet. */
    val site: String?,
    val content: PageControlContent,
    /** The page already showed a dialog: offer to suppress further ones until it navigates. */
    val offerBlocking: Boolean,
)

/** Whether answering a dialog also suppresses the page's further dialogs. */
enum class DialogBlocking {
    KEEP_SHOWING,

    /** Later dialogs of the same document are dismissed without being shown. */
    BLOCK_FOR_PAGE,
}

/** Reader view of the focused tab: preparing, the article, or none found. */
sealed interface ReaderState {
    val tabId: String

    data class Loading(override val tabId: String) : ReaderState

    /** [url]: the page the article was read from. */
    data class Ready(override val tabId: String, val url: String, val article: Article) : ReaderState

    /** The page has no article Combray can find, or it changed before one was read. */
    data class Unavailable(override val tabId: String) : ReaderState
}

/** Whether the stored session has been read since the process started. */
enum class SessionState {
    /** Not yet: no focused tab does not mean the internal home, so neither may be shown. */
    RESTORING,

    /** The stored tabs are published, or reading them failed and the state shows what there is. */
    RESTORED,
}

data class BrowserState(
    val engine: EngineStatus = EngineStatus.STARTING,
    val session: SessionState = SessionState.RESTORING,
    /** Null: no tab is focused, the internal home is shown. */
    val activeTab: ActiveTab? = null,
    val openTabCount: Int = 0,
    /** The page focused (or left) an editable element: show or hide the keyboard. */
    val keyboard: KeyboardRequest? = null,
    /** The focused page's oldest unanswered control. */
    val pageControl: PageControl? = null,
    /** Reader view over the focused tab, while it is open. */
    val reader: ReaderState? = null,
    val notice: Notice? = null,
)

/** One row of the unified Recent list. */
/**
 * How much history [BrowserCoordinator.recent] reads. Browsing shows the newest rows; a search
 * must reach every retained visit, or a match older than the newest rows would be missed.
 */
enum class HistoryDepth {
    NEWEST,
    ALL_RETAINED,
    ;

    companion object {
        /** The depth a Recent filter needs: everything retained once there is text to match. */
        fun forQuery(query: String?) = if (query.isNullOrBlank()) NEWEST else ALL_RETAINED
    }
}

/** History rows Recent shows at most, newest first; open tabs are never cut. */
const val RECENT_HISTORY_LIMIT = 500

/**
 * The rows a Recent filter shows: matches of [query] in this newest-first list, keeping at most
 * [historyLimit] history rows. The cap applies after filtering, so a search over all retained
 * history still finds an old match.
 */
fun List<RecentItem>.matching(query: String?, historyLimit: Int = RECENT_HISTORY_LIMIT): List<RecentItem> {
    var history = 0
    return filter { it.matches(query) && (it !is RecentItem.Visit || history++ < historyLimit) }
}

sealed interface RecentItem {
    val key: String
    val url: String?
    val title: String?

    /** Time used for ordering: last use for tabs (focus, or navigation in focus), last interaction for history. */
    val time: Long

    /** Whether the local Recent filter keeps this row; a blank query keeps everything. */
    fun matches(query: String?): Boolean {
        if (query.isNullOrBlank()) return true
        val needle = query.trim()
        return (title?.contains(needle, ignoreCase = true) ?: false) ||
            (url?.contains(needle, ignoreCase = true) ?: false)
    }

    data class OpenTab(
        val tabId: String,
        override val url: String?,
        override val title: String?,
        override val time: Long,
        val active: Boolean,
    ) : RecentItem {
        override val key get() = "tab:$tabId"
    }

    data class Visit(
        val visitId: String,
        val tabId: String,
        override val url: String,
        override val title: String?,
        override val time: Long,
    ) : RecentItem {
        override val key get() = "visit:$visitId"
    }
}
