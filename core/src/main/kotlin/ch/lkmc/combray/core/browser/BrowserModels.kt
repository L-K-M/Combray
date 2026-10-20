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
) {
    /** The internal new-tab state: no page, nothing for the engine to show. */
    val isHome get() = url == null && !hasView
}

/** A short notice the UI shows once, optionally with an action. */
sealed interface Notice {
    data class TabClosed(val tabId: String, val title: String?) : Notice

    data class Problem(val message: String) : Notice

    /** The engine could not open an address (it does not parse as a URL Servo can load). */
    data object AddressNotOpened : Notice
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

data class BrowserState(
    val engine: EngineStatus = EngineStatus.STARTING,
    /** Null: no tab is focused, the internal home is shown. */
    val activeTab: ActiveTab? = null,
    val openTabCount: Int = 0,
    /** The page focused (or left) an editable element: show or hide the keyboard. */
    val keyboard: KeyboardRequest? = null,
    /** The focused page's oldest unanswered control. */
    val pageControl: PageControl? = null,
    val notice: Notice? = null,
)

/** One row of the unified Recent list. */
sealed interface RecentItem {
    val key: String
    val url: String?
    val title: String?

    /** Time used for ordering: last focus for tabs, last interaction for history. */
    val time: Long

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
