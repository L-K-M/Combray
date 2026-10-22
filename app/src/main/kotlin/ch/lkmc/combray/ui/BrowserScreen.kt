package ch.lkmc.combray.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.combray.AppGraph
import ch.lkmc.combray.LaunchRequest
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.browser.BrowserState
import ch.lkmc.combray.core.browser.EngineStatus
import ch.lkmc.combray.core.browser.HistoryDepth
import ch.lkmc.combray.core.browser.KeyboardRequest
import ch.lkmc.combray.core.browser.Notice
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.SessionState
import ch.lkmc.combray.core.integration.SaveRequest
import ch.lkmc.combray.core.integration.SaveStatus
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.engine.EngineSurfaceView
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import ch.lkmc.combray.engine.ScrollEdge
import kotlinx.coroutines.launch

private enum class Sheet { NONE, ADDRESS, RECENT, MORE, SETTINGS, PAGE_INFO, TEXT_SIZE }

/**
 * The browser: page area on top, all routine actions in the bottom bar (R3). The engine
 * surface stays in place under every overlay, so sheets never release a renderer.
 * [launchRequest] carries a sheet another app or a launcher shortcut asked for; the screen
 * opens it once and hands it back through [onLaunchRequestHandled].
 */
@Composable
fun BrowserScreen(
    graph: AppGraph,
    launchRequest: StateFlow<LaunchRequest?>,
    onLaunchRequestHandled: (LaunchRequest) -> Unit,
    onLeave: () -> Unit,
) {
    val browser = graph.browser
    val state by browser.browserState.collectAsStateWithLifecycle()
    val textSize by graph.settings.textSize.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    var surfaceView by remember { mutableStateOf<EngineSurfaceView?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val tab = state.activeTab
    val page = tab?.page
    var scrollEdge by remember(tab?.id, page, state.reader) { mutableStateOf<ScrollEdge?>(null) }
    // Tagged with its address: until the new page's status arrives, the bar keeps the last one.
    val saveStatus by remember(tab?.url) {
        val url = tab?.url
        graph.integrations.saveStatus(url).map { PageSaveStatus(url, it) }
    }.collectAsStateWithLifecycle(PageSaveStatus(null, SaveStatus.Unsaved))
    val histerService by graph.integrations.hister.service.collectAsStateWithLifecycle(null)

    val closedMessage = stringResource(R.string.notice_tab_closed)
    val notOpened = stringResource(R.string.notice_address_not_opened)
    val mediaUnsupported = stringResource(R.string.notice_media_unsupported)
    val undo = stringResource(R.string.action_undo)
    val openedInBackground = stringResource(R.string.notice_opened_in_background)
    val show = stringResource(R.string.action_show)
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        browser.noticeShown(notice)
        // Clearing the notice restarts this effect: the snackbar runs in the screen's scope.
        scope.launch {
            when (notice) {
                is Notice.TabClosed -> {
                    // A newer close replaces the last Undo instead of queuing behind it.
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar(closedMessage, actionLabel = undo, duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) browser.reopenTab(notice.tabId, notice.undoFocus)
                }
                is Notice.Problem -> snackbar.showSnackbar(notice.message)
                Notice.AddressNotOpened -> snackbar.showSnackbar(notOpened)
                is Notice.OpenedInBackground -> {
                    // As for a close, a newer notice replaces the last Show instead of queuing behind it.
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar(openedInBackground, actionLabel = show, duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) browser.selectTab(notice.tabId)
                }
                Notice.MediaUnsupported -> snackbar.showSnackbar(mediaUnsupported)
            }
        }
    }

    // The page focused or left an editable element.
    LaunchedEffect(state.keyboard, surfaceView) {
        val request = state.keyboard ?: return@LaunchedEffect
        val view = surfaceView ?: return@LaunchedEffect
        when (request) {
            is KeyboardRequest.Show -> view.showKeyboard(request.kind)
            // While find is open the keyboard may be the find field's: find's selection can blur a
            // page field, and that must not close the keyboard the user is typing the query with.
            KeyboardRequest.Hide -> if (state.find == null) view.hideKeyboard()
        }
        browser.keyboardHandled(request)
    }

    // The address editor submits to the tab it was opened for; null opens a new tab.
    var addressTab by rememberSaveable { mutableStateOf<String?>(null) }
    // Text another app sent, which the editor starts with instead of an address.
    var addressText by rememberSaveable { mutableStateOf<String?>(null) }
    val editAddress = {
        addressTab = tab?.id
        addressText = null
        sheet = Sheet.ADDRESS
    }
    // An unused blank tab is the new tab already; typing there makes no second one. Text from
    // another app waits there, selected, until the user submits it.
    val editNewTabWith = { text: String? ->
        addressTab = tab?.takeIf { it.isHome }?.id
        addressText = text
        sheet = Sheet.ADDRESS
    }
    val editNewTab = { editNewTabWith(null) }
    var recentScope by rememberSaveable { mutableStateOf(RecentScope.OPEN) }
    val openRecent = { initial: RecentScope ->
        recentScope = initial
        sheet = Sheet.RECENT
    }

    // A requested sheet replaces the open one and starts fresh: the counter restarts its state.
    var launches by rememberSaveable { mutableIntStateOf(0) }
    val pendingLaunch by launchRequest.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLaunch) {
        val request = pendingLaunch ?: return@LaunchedEffect
        launches++
        when (request) {
            is LaunchRequest.EditNewTab -> editNewTabWith(request.text)
            LaunchRequest.SearchHistory -> openRecent(RecentScope.HISTER)
        }
        onLaunchRequestHandled(request)
    }

    // Back: close the open sheet or reader view first, then go back in the page, then return to
    // the tab whose page opened this one, then to the app that opened the tab, then leave the
    // app. A tab another app opened closes on the way out: its page stays in history only.
    BackHandler(enabled = sheet != Sheet.NONE) { sheet = Sheet.NONE }
    // One action for system Back and the bar's Back button, so the two cannot drift apart; null
    // when Back has nowhere to go in this tab. A page-opened tab and one another app opened
    // are exclusive by construction.
    val backInPage: (() -> Unit)? = tab?.let {
        when {
            it.canGoBack -> { { browser.goBack() } }
            it.returnsToOpener -> { { browser.returnToOpener(it.id) } }
            it.openedByOtherApp -> { {
                browser.returnToOtherApp(it.id)
                onLeave()
            } }
            else -> null
        }
    }
    BackHandler(enabled = sheet == Sheet.NONE && state.reader == null && backInPage != null) { backInPage?.invoke() }
    BackHandler(enabled = sheet == Sheet.NONE && state.reader != null) { browser.closeReader() }
    // Registered last, so it runs before going back in the page.
    BackHandler(enabled = sheet == Sheet.NONE && state.find != null) { browser.closeFind() }

    val notify: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    // The bar is one row or two (BottomBar), so snackbars clear its measured height.
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(0.dp) }
    val openSettings = { sheet = Sheet.SETTINGS }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            ) {
                AndroidView(
                    factory = { viewContext ->
                        EngineSurfaceView(viewContext) { graph.engineRunning() }.also { surfaceView = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view -> view.observeScroll(page) { scrollEdge = it } },
                )
                PageOverlay(
                    state = state,
                    onOpenAddress = editAddress,
                    onOpenRecent = { openRecent(RecentScope.OPEN) },
                    onReload = browser::reload,
                )
                if (state.engine == EngineStatus.READY && state.reader == null && page != null && !tab.loadFailed) {
                    scrollEdge?.let { edge ->
                        ScrollEdgeButton(
                            edge = edge,
                            onClick = {
                                scrollEdge = null
                                browser.scrollToEdge(page, edge)
                            },
                            modifier = Modifier.align(AbsoluteAlignment.BottomLeft).padding(16.dp),
                        )
                    }
                }
                state.reader?.let { reader ->
                    ReaderView(
                        state = reader,
                        textSize = textSize,
                        // A link leaves reader view and opens in the tab, through the address policy.
                        onOpenLink = { url ->
                            browser.closeReader()
                            (AddressPolicy.interpret(url).primary as? AddressDecision.Navigate)?.let { browser.openUrlInTab(reader.tabId, it.url) }
                        },
                        onClose = browser::closeReader,
                    )
                }
            }
            // Find in page takes the bottom bar's place, above the keyboard like it.
            state.find?.let { find ->
                FindBar(
                    state = find,
                    onFind = browser::find,
                    onClose = browser::closeFind,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                )
            }
            if (state.find == null) BottomBar(
                tab = tab,
                openTabs = state.openTabCount,
                saveStatus = saveStatus,
                backEnabled = state.reader != null || backInPage != null,
                // Like system Back: Reader view closes first, then Back moves through the page.
                onBack = { if (state.reader != null) browser.closeReader() else backInPage?.invoke() },
                onAddress = editAddress,
                onSave = {
                    // Snapshot now: switching tabs right after the tap must not change what is saved.
                    val url = tab?.url
                    val title = tab?.title
                    scope.launch {
                        // The Save icon shows a queued save of the page itself.
                        reportSave(graph.integrations.save(url, title), snackbar, resources, queued = null, onOpenSettings = openSettings)
                    }
                },
                onShare = {
                    tab?.let { page -> page.url?.let { url -> share(context, url, resources.getString(R.string.share_chooser), page.title.takeUnless { page.loadFailed }) } }
                },
                onRecent = { openRecent(RecentScope.OPEN) },
                onRecentLongPress = { browser.focusPreviousTab() },
                onSwipeTab = { step -> browser.stepTab(step) },
                modifier = Modifier.onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                onMore = { sheet = Sheet.MORE },
                onCopyAddress = {
                    // The rendered address as shown (a recovery URL before a commit), copied whole.
                    tab?.url?.let { url ->
                        copyAddressToClipboard(context, resources.getString(R.string.page_info_address), url)
                        // Android 13+ shows its own clipboard confirmation; below it we must.
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                            notify(resources.getString(R.string.address_copied))
                        }
                    }
                },
            )
        }
        // Above the keyboard too: a page field can hold it while a Save error shows. Recent is a
        // separate window over this one and shows the snackbars itself while it is open. The
        // bar's height includes the navigation bar, and the keyboard's padding is the column's.
        if (sheet != Sheet.RECENT) {
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = barHeight + SNACKBAR_GAP))
        }
    }

    val dismiss = { sheet = Sheet.NONE }
    when (sheet) {
        Sheet.NONE -> Unit
        Sheet.ADDRESS -> AddressSheet(
            target = addressTab,
            restartKey = launches,
            // Text another app sent, or the tab's address; on a Kagi results page, its search words.
            initialText = addressText ?: addressTab?.let { id -> tab?.takeIf { it.id == id }?.url }?.let { AddressPolicy.kagiQuery(it) ?: it },
            // Suggestions search all retained history, like a Recent search.
            recent = { browser.recent(HistoryDepth.ALL_RETAINED) },
            onDecision = { decision ->
                dismiss()
                val target = addressTab
                val open = { url: String -> if (target != null) browser.openUrlInTab(target, url) else browser.openUrl(url, Placement.NEW_TAB) }
                when (decision) {
                    is AddressDecision.Navigate -> open(decision.url)
                    is AddressDecision.Search -> open(decision.url)
                    is AddressDecision.External -> openExternally(context, decision.url, resources.getString(R.string.no_app_for_link), notify)
                    is AddressDecision.Rejected -> Unit
                }
            },
            // The exact tab, as in Recent: switching never follows an equal address elsewhere.
            onSelectTab = { tabId ->
                dismiss()
                browser.selectTab(tabId)
            },
            onDismiss = dismiss,
        )
        Sheet.RECENT -> RecentSheet(
            browser = browser,
            integrations = graph.integrations,
            initialScope = recentScope,
            restartKey = launches,
            onOpenUrl = { url ->
                dismiss()
                // Hister and Karakeep results open as new navigations, through the same address policy.
                (AddressPolicy.interpret(url).primary as? AddressDecision.Navigate)?.let { browser.openUrl(it.url, Placement.NEW_TAB) }
            },
            onNewTab = editNewTab,
            snackbar = snackbar,
            onDismiss = dismiss,
        )
        Sheet.MORE -> MoreSheet(
            tab = tab,
            onForward = { dismiss(); browser.goForward() },
            onReload = { dismiss(); browser.reload() },
            onNewTab = editNewTab,
            // The sheet closes so it does not cover the Undo notice in the main window.
            onCloseTab = { dismiss(); tab?.let { browser.closeTab(it.id) } },
            onPageInfo = { sheet = Sheet.PAGE_INFO },
            onSettings = { sheet = Sheet.SETTINGS },
            captureEnabled = histerService?.captureEnabled,
            onPauseCapture = {
                dismiss()
                scope.launch { graph.integrations.hister.setCaptureEnabled(false) }
            },
            readerOpen = state.reader != null,
            onReader = {
                dismiss()
                if (state.reader != null) browser.closeReader() else browser.openReader()
            },
            onTextSize = { sheet = Sheet.TEXT_SIZE },
            onFind = {
                dismiss()
                browser.openFind()
            },
            onDismiss = dismiss,
        )
        Sheet.SETTINGS -> SettingsSheet(
            integrations = graph.integrations,
            settings = graph.settings,
            retention = graph.retention,
            onUnusedTabsChanged = browser::runMaintenance,
            notices = graph.notices,
            onDismiss = dismiss,
        )
        Sheet.PAGE_INFO -> {
            val url = tab?.url
            if (url != null) {
                PageInfoDialog(url = url, onDismiss = dismiss)
            } else {
                // The focused tab can lose its address while the dialog is open: for example a
                // page's window.open focuses a new blank tab, or a refused address clears a tab
                // without a renderer. Close from an effect: writing sheet state during
                // composition is not allowed. If an address returns first, this branch leaves
                // composition and the pending dismissal is cancelled with it.
                LaunchedEffect(Unit) { dismiss() }
            }
        }
        Sheet.TEXT_SIZE -> {
            TextSizeDialog(
                size = textSize,
                onChange = { size ->
                    graph.settings.setTextSize(size)
                    browser.applyTextSize()
                },
                onDismiss = dismiss,
            )
        }
    }

    // Shown over any sheet: the page is blocked until it gets an answer.
    state.pageControl?.let { control ->
        // Through the address policy, like any link the app opens itself (no javascript:).
        val navigation = { url: String -> AddressPolicy.interpret(url).primary as? AddressDecision.Navigate }
        PageControlDialog(
            control = control,
            staging = graph.uploads,
            uploadScope = graph.scope,
            onAnswer = { response, blocking -> browser.answerPageControl(control.id, response, blocking) },
            onUploadFailed = { notify(resources.getString(R.string.upload_failed)) },
            isWebAddress = { url -> navigation(url) != null },
            // The control belongs to the focused tab's page: Back on the new tab returns there.
            onOpenInNewTab = { url ->
                navigation(url)?.let { link ->
                    val opener = tab?.id
                    if (opener != null) browser.openUrlFromTab(link.url, opener) else browser.openUrl(link.url, Placement.NEW_TAB)
                }
            },
            onOpenInBackground = { url -> navigation(url)?.let { browser.openUrl(it.url, Placement.BACKGROUND_TAB) } },
            onSaveLink = { url ->
                navigation(url)?.let { link ->
                    scope.launch {
                        // No title: the link's page is not loaded, and Karakeep fills it in when it fetches the page.
                        val request = graph.integrations.save(link.url, null)
                        reportSave(request, snackbar, resources, queued = resources.getString(R.string.link_save_queued), onOpenSettings = openSettings)
                    }
                }
            },
            onShare = { url -> share(context, url, resources.getString(R.string.share_chooser)) },
            onNotify = notify,
        )
    }
}

/**
 * Tells the user what became of a Karakeep save, the same way for the bar's Save and a link's.
 * [queued] confirms a queued save, or is null where the Save icon already shows it.
 */
private suspend fun reportSave(
    request: SaveRequest,
    snackbar: SnackbarHostState,
    resources: Resources,
    queued: String?,
    onOpenSettings: () -> Unit,
) {
    when (request) {
        SaveRequest.NotConfigured -> {
            // Timed, not Indefinite (the default with an action): it must not block later notices.
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                resources.getString(R.string.save_not_set_up),
                actionLabel = resources.getString(R.string.action_open_settings),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onOpenSettings()
        }
        SaveRequest.NotSaveable -> snackbar.showSnackbar(resources.getString(R.string.save_not_saveable))
        SaveRequest.Sensitive -> snackbar.showSnackbar(resources.getString(R.string.save_sensitive))
        SaveRequest.AlreadyQueued -> snackbar.showSnackbar(resources.getString(R.string.save_already_queued))
        SaveRequest.NeedsAttention -> {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                resources.getString(R.string.save_needs_attention),
                actionLabel = resources.getString(R.string.action_open_settings),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onOpenSettings()
        }
        SaveRequest.Queued -> queued?.let { snackbar.showSnackbar(it) }
    }
}

/** What covers the page area when there is no page to show, or the page cannot be shown. */
@Composable
private fun PageOverlay(
    state: BrowserState,
    onOpenAddress: () -> Unit,
    onOpenRecent: () -> Unit,
    onReload: () -> Unit,
) {
    val tab: ActiveTab? = state.activeTab
    when {
        state.engine == EngineStatus.LIBRARY_MISSING -> OverlayMessage(stringResource(R.string.engine_missing))
        state.engine == EngineStatus.FAILED -> OverlayMessage(stringResource(R.string.engine_failed))
        // No tab yet does not mean home: a restored session must not flash "New tab" first.
        // The launch screen covers this for up to two seconds; a slower restore says what happens.
        state.session == SessionState.RESTORING -> OverlayMessage(stringResource(R.string.session_restoring))
        tab == null || tab.isHome -> Home(onOpenAddress, onOpenRecent)
        tab.crashed -> OverlayMessage(stringResource(R.string.page_crashed)) {
            Button(onClick = onReload) { Text(stringResource(R.string.action_reload)) }
        }
        state.engine == EngineStatus.STARTING -> OverlayMessage(stringResource(R.string.engine_starting))
    }
}

@Composable
private fun Home(onOpenAddress: () -> Unit, onOpenRecent: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.home_body), textAlign = TextAlign.Center)
            Button(onClick = onOpenAddress, modifier = Modifier.widthIn(min = 240.dp)) {
                Text(stringResource(R.string.address_placeholder))
            }
            OutlinedButton(onClick = onOpenRecent, modifier = Modifier.widthIn(min = 240.dp)) {
                Text(stringResource(R.string.action_recent))
            }
        }
    }
}

@Composable
private fun OverlayMessage(message: String, action: @Composable () -> Unit = {}) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, textAlign = TextAlign.Center)
            action()
        }
    }
}

private fun openExternally(context: Context, url: String, noApp: String, notify: (String) -> Unit) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (none: ActivityNotFoundException) {
        notify(noApp)
    }
}

/**
 * Shares the address, and the page's title as the subject and first line when it has one.
 * A failed load's title is the engine's error page, not the page's, so callers leave it out.
 */
private fun share(context: Context, url: String, chooserTitle: String, title: String? = null) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, shareText(url, title))
    shareTitle(title)?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
    context.startActivity(Intent.createChooser(send, chooserTitle))
}

/**
 * Copied addresses can carry one-time tokens (a recovery URL before a commit), so they are
 * marked sensitive: Android 13+ redacts the system preview and well-behaved keyboards keep
 * them out of suggestions/history. A rendering hint, not access control.
 * `ClipDescription.extras` exists since API 24; the app requires API 30.
 */
internal fun copyAddressToClipboard(context: Context, label: String, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, url)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    clipboard.setPrimaryClip(clip)
}

/** Space between a snackbar and the bottom bar under it. */
private val SNACKBAR_GAP = 8.dp
