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
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.combray.AppGraph
import ch.lkmc.combray.ExternalLinks
import ch.lkmc.combray.ExternalOutcome
import ch.lkmc.combray.LaunchRequest
import ch.lkmc.combray.ImageDownloadResult
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.browser.BrowserState
import ch.lkmc.combray.core.browser.EngineStatus
import ch.lkmc.combray.core.browser.HistoryDepth
import ch.lkmc.combray.core.browser.KeyboardRequest
import ch.lkmc.combray.core.browser.MediaPageRef
import ch.lkmc.combray.core.browser.Notice
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.SessionState
import ch.lkmc.combray.core.browser.HomeLink
import ch.lkmc.combray.core.integration.ReportDisplay
import ch.lkmc.combray.core.integration.ReportDraft
import ch.lkmc.combray.core.integration.ReportRequest
import ch.lkmc.combray.core.integration.SaveRequest
import ch.lkmc.combray.core.integration.ScreenshotOmission
import ch.lkmc.combray.core.integration.SaveStatus
import ch.lkmc.combray.core.integration.SensitiveUrls
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.engine.Appearance
import ch.lkmc.combray.engine.ControlResponse
import ch.lkmc.combray.engine.EngineSurfaceView
import ch.lkmc.combray.engine.LeavePageChoice
import ch.lkmc.combray.engine.PageControlContent
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ch.lkmc.combray.engine.ScrollEdge
import ch.lkmc.combray.player.PlayerActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class Sheet { NONE, RECENT, SETTINGS, PAGE_INFO, TEXT_SIZE, HOME_EDIT, MEDIA, REPORT }

/**
 * The browser: page area on top, all routine actions in the bottom bar (R3). The engine
 * surface stays in place under every overlay, so sheets never release a renderer.
 * [launchRequest] carries an entry point another app or a launcher shortcut asked for; the
 * screen opens it once and hands it back through [onLaunchRequestHandled].
 */
@Composable
fun BrowserScreen(
    graph: AppGraph,
    launchRequest: StateFlow<LaunchRequest?>,
    onLaunchRequestHandled: (LaunchRequest) -> Unit,
    onLeave: () -> Unit,
) {
    val browser = graph.browser
    // The UI collects every flow on the main thread. The coordinator publishes from
    // Dispatchers.Default and Room from its executor; without a context, a Compose test's
    // collector resumes there, wakes the recomposer there and may run the frame there, where a
    // BackHandler's lifecycle effect throws (MainThreadStateTest).
    val state by browser.browserState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val textSize by graph.settings.textSize.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val tabCounter by graph.settings.tabCounter.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val paintedViewport by browser.paintedViewport.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    var homeMode by rememberSaveable { mutableStateOf(AddressEditorMode.IDLE) }
    var homeEntry by rememberSaveable { mutableIntStateOf(0) }
    var homeText by rememberSaveable { mutableStateOf("") }
    // A page's address editor, in the bar: the tab it edits, a counter that starts each edit
    // afresh, and the text it started with (taken at the tap, so a page that changes its
    // address meanwhile does not change it).
    var pageEditing by rememberSaveable { mutableStateOf(false) }
    var addressTab by rememberSaveable { mutableStateOf<String?>(null) }
    var pageEntry by rememberSaveable { mutableIntStateOf(0) }
    var pageText by rememberSaveable { mutableStateOf("") }
    // While the address editor dims a page and owns the keyboard, the engine surface keeps its
    // size and the page its layout, until that keyboard has gone (see the surface below).
    var holdSurface by remember { mutableStateOf(false) }
    var homeEditLink by remember { mutableStateOf<HomeLink?>(null) }
    var homeEditPage by rememberSaveable { mutableStateOf(HomeEditPage.OVERVIEW) }
    var surfaceView by remember { mutableStateOf<EngineSurfaceView?>(null) }
    // The report sheet keeps only its draft's id: the draft itself lives in the process.
    var reportDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    var reportStarting by remember { mutableStateOf(false) }
    val reportDraft by graph.reportDrafts.current.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val snackbar = remember { SnackbarHostState() }
    // The page's offer to play its media lasts until it is played or closed, so it has a host of
    // its own: in the notices' queue it would hold back every notice after it.
    val offerHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val tab = state.activeTab
    val readerDisplay = if (state.reader == null) ReaderDisplay.PAGE else ReaderDisplay.ARTICLE
    val menu = remember(tab?.id) { ToolbarMenuState() }
    LaunchedEffect(sheet) { menu.dismiss() }
    val page = tab?.page
    val reloadPull = state.reloadPull?.takeIf { it.page == page }
    val home = tab?.url == null
    val stopHomeInput: () -> Unit = {
        homeMode = AddressEditorMode.IDLE
        // The field keeps its own draft; its launch prefill must not seed a later Home.
        homeText = ""
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    // Only the tab it was opened for, and only on a page: another tab ends it (below).
    val editingPage = pageEditing && !home && tab.id == addressTab && state.find == null
    val stopPageInput: () -> Unit = {
        pageEditing = false
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    // A sheet that opens meanwhile (Settings from a notice's action) ends the page's editor, as
    // it replaced the old address sheet: the editor must not come back unfocused behind it.
    LaunchedEffect(sheet) { if (sheet != Sheet.NONE && pageEditing) stopPageInput() }
    val notify: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    // Text size's preview is provisional: rapid steps read the latest pending choice, and a
    // failed write rolls the engine back to the durable value.
    val textSizeDraft = remember {
        TextSizeDraft(
            scope = scope,
            durable = { graph.settings.textSize.value },
            apply = { size -> browser.applyTextSize(size) },
            persist = graph.settings::setTextSize,
            onFailure = { notify(resources.getString(R.string.home_change_failed)) },
        )
    }
    TextSizePreviewEffect(
        textSizeDraft,
        textSize,
        state.engine == EngineStatus.READY,
        apply = { browser.applyTextSize(it) },
    )
    LaunchedEffect(tab?.id, tab?.url) {
        if (!home) homeMode = AddressEditorMode.IDLE
    }
    var scrollEdge by remember(tab?.id, page, state.reader) { mutableStateOf<ScrollEdge?>(null) }
    var scrollActivity by remember(tab?.id, page, state.reader) { mutableIntStateOf(0) }
    // Tagged with its address: until the new page's status arrives, the bar keeps the last one.
    val saveStatus by remember(tab?.url) {
        val url = tab?.url
        graph.integrations.saveStatus(url).map { PageSaveStatus(url, it) }
    }.collectAsStateWithLifecycle(PageSaveStatus(null, SaveStatus.Unsaved), context = Dispatchers.Main.immediate)
    val histerStored by remember(tab?.url) { graph.integrations.hister.captureStored(tab?.url) }
        .collectAsStateWithLifecycle(false, context = Dispatchers.Main.immediate)

    val closedMessage = stringResource(R.string.notice_tab_closed)
    val notOpened = stringResource(R.string.notice_address_not_opened)
    val mediaUnsupported = stringResource(R.string.notice_media_unsupported)
    val mediaPlayable = stringResource(R.string.notice_media_playable)
    val play = stringResource(R.string.action_play)
    val mediaPageLeft = stringResource(R.string.notice_media_page_left)
    val popupBlocked = stringResource(R.string.notice_popup_blocked)
    val undo = stringResource(R.string.action_undo)
    val openedInBackground = stringResource(R.string.notice_opened_in_background)
    val show = stringResource(R.string.action_show)
    // The player gets an id for a request the coordinator built and checked; nothing opens if the page has changed.
    val openPlayer = { page: MediaPageRef, entry: Long ->
        scope.launch {
            val request = browser.playbackRequest(page, entry) ?: return@launch
            context.startActivity(PlayerActivity.intent(context, graph.playback.offer(request)))
        }
    }
    // One entry plays at once; several open a choice. Only for the page the tab still shows: a notice that outlived its page says so.
    val playMedia = { page: MediaPageRef ->
        // Played from the offer, the address field or More: either way the offer has done its job.
        browser.dismissMediaOffer(page)
        val shown = browser.browserState.value.activeTab?.takeIf { it.mediaPage == page }
        when (shown?.media?.size ?: 0) {
            0 -> notify(mediaPageLeft)
            1 -> openPlayer(page, shown!!.media.single().id)
            else -> sheet = Sheet.MEDIA
        }
    }
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
                Notice.StopFailed -> snackbar.showSnackbar(resources.getString(R.string.notice_stop_failed))
                is Notice.OpenedInBackground -> {
                    // As for a close, a newer notice replaces the last Show instead of queuing behind it.
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar(openedInBackground, actionLabel = show, duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) browser.selectTab(notice.tabId)
                }
                Notice.MediaUnplayable -> snackbar.showSnackbar(mediaUnsupported)
                Notice.PopupBlocked -> snackbar.showSnackbar(popupBlocked)
            }
        }
    }

    // The page's offer stays until it is played or closed (owner decision, 6 November 2026). The
    // coordinator ends it when its page leaves the screen, which restarts this and removes it.
    val offer = state.mediaOffer
    LaunchedEffect(offer) {
        if (offer == null) return@LaunchedEffect
        // Replaces the page's plain notice if it still shows: one page, one message.
        snackbar.currentSnackbarData?.takeIf { it.visuals.message == mediaUnsupported }?.dismiss()
        val result = offerHost.showSnackbar(mediaPlayable, actionLabel = play, withDismissAction = true, duration = SnackbarDuration.Indefinite)
        when (result) {
            SnackbarResult.ActionPerformed -> playMedia(offer)
            SnackbarResult.Dismissed -> browser.dismissMediaOffer(offer)
        }
    }

    // Showing another tab (a swipe on the bar, Recent's long-press, a snackbar's Show, a page's
    // window) hides the keyboard: the engine sends typing to the page shown, so keys meant for
    // one page's field must not go on into the next page. Before the request below, so a field
    // the new page focuses at once still gets the keyboard.
    var keyboardTab by remember { mutableStateOf(tab?.id) }
    LaunchedEffect(tab?.id) {
        // A page's address editor belongs to its tab: showing another one ends it.
        if (pageEditing && tab?.id != addressTab) stopPageInput()
        if (tab?.id == keyboardTab) return@LaunchedEffect
        keyboardTab = tab?.id
        surfaceView?.hideKeyboard()
    }

    // The page focused or left an editable element.
    LaunchedEffect(state.keyboard, surfaceView) {
        val request = state.keyboard ?: return@LaunchedEffect
        val view = surfaceView ?: return@LaunchedEffect
        // Home's search field or a page's address editor is typing with the keyboard.
        val editorTyping = editingPage || (home && homeMode == AddressEditorMode.EDITING)
        when (request) {
            // Not taken from the editor, which covers the page: keys meant for the address must
            // not go into the page. The page's field lets go of its focus; a tap on it asks again.
            is KeyboardRequest.Show -> if (editorTyping) {
                view.declineKeyboard()
            } else {
                // The page's field needs the room above the keyboard: stop holding the surface.
                holdSurface = false
                view.showKeyboard(request.kind, request.multiline, request.context)
            }
            is KeyboardRequest.Prepare -> view.prepareKeyboard(request.kind, request.multiline, request.context)
            // A page's blur must not hide the keyboard now owned by Find or the address editor.
            KeyboardRequest.Hide -> if (state.find == null && !editorTyping) view.hideKeyboard()
        }
        browser.keyboardHandled(request)
    }

    // A page link for another app asks first (owner decision, 22 October 2026). Open hands it to
    // Android, or the link's own fallback page loads in the tab. Taken from the state before the
    // question, so the engine's next link is a new request. Saved, as the state no longer holds
    // the link: recreating the activity (rotation, dark mode) must not drop the question.
    var appLink by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.externalLink) {
        val link = state.externalLink ?: return@LaunchedEffect
        appLink = link.url
        browser.externalLinkHandled(link)
    }
    appLink?.let { url ->
        AppLinkDialog(
            url = url,
            onOpen = {
                appLink = null
                val noApp = { scope.launch { snackbar.showSnackbar(resources.getString(R.string.no_app_for_link)) } }
                when (val outcome = ExternalLinks.open(context, url)) {
                    ExternalOutcome.Opened -> Unit
                    is ExternalOutcome.Fallback -> {
                        val page = AddressPolicy.interpret(outcome.url).primary as? AddressDecision.Navigate
                        val shown = tab?.id
                        // A fallback that is no web page, or no tab to show it in: say so, as without one.
                        if (page != null && shown != null) browser.openUrlInTab(shown, page.url) else noApp()
                    }
                    ExternalOutcome.NoApp -> noApp()
                }
            },
            onDismiss = { appLink = null },
        )
    }

    // Pages follow the app's light or dark theme, the in-app Appearance choice included, like
    // the chrome around them.
    val dark = LocalDarkTheme.current
    LaunchedEffect(dark) { browser.setAppearance(if (dark) Appearance.DARK else Appearance.LIGHT) }

    // A page's address opens for editing in the bar itself, for the tab shown at the tap, with
    // its address (on a Kagi results page, the search words) selected.
    val editAddress: () -> Unit = edit@{
        val shown = tab ?: return@edit
        val url = shown.url ?: return@edit
        addressTab = shown.id
        pageText = AddressPolicy.kagiQuery(url) ?: url
        pageEntry++
        pageEditing = true
        // The address field takes the keyboard over, without closing it in between: a page
        // field lets go of its connection and its focus.
        surfaceView?.yieldKeyboard()
    }
    // New-tab entry points all focus the existing Home field. Save its prefill before queuing
    // the blank tab so rotation cannot drop text another app sent while creation is pending.
    val editNewTabWith = { text: String? ->
        homeText = text.orEmpty()
        homeEntry++
        homeMode = AddressEditorMode.EDITING
        // Home's field takes the focus and the keyboard over from a page's address editor.
        pageEditing = false
        sheet = Sheet.NONE
        surfaceView?.hideKeyboard()
        browser.newTab()
    }
    val newTab = { editNewTabWith(null) }
    val openAddress = { target: String?, decision: AddressDecision ->
        sheet = Sheet.NONE
        stopHomeInput()
        stopPageInput()
        val open = { url: String -> if (target != null) browser.openUrlInTab(target, url) else browser.openUrl(url, Placement.NEW_TAB) }
        when (decision) {
            is AddressDecision.Navigate -> open(decision.url)
            is AddressDecision.Search -> open(decision.url)
            is AddressDecision.External -> openExternally(context, decision.url, resources.getString(R.string.no_app_for_link), notify)
            is AddressDecision.Rejected -> Unit
        }
    }
    // Cold and bounded to retained candidates; pin/hide filtering must precede the six-tile cap.
    val homeLinks = remember(graph) { graph.home.state(browser.topSites(SensitiveUrls::looksSensitive, limit = Int.MAX_VALUE)) }
    val continuation = remember(browser) { browser.continueTab(SensitiveUrls::looksSensitive) }
    var recentScope by rememberSaveable { mutableStateOf(RecentScope.OPEN) }
    val openRecent = { initial: RecentScope ->
        if (homeMode == AddressEditorMode.EDITING) stopHomeInput()
        if (pageEditing) stopPageInput()
        recentScope = initial
        sheet = Sheet.RECENT
    }

    // A requested sheet replaces the open one and starts fresh: the counter restarts its state.
    var launches by rememberSaveable { mutableIntStateOf(0) }
    val pendingLaunch by launchRequest.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    LaunchedEffect(pendingLaunch) {
        val request = pendingLaunch ?: return@LaunchedEffect
        menu.dismiss()
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
    // An open page question outranks going back in the page: Back answers it with the dialog's
    // default (Stay on a leave-page question), as PageControlDialog documents. Registered after
    // the page-back handler, so it wins while a control is shown.
    BackHandler(enabled = sheet == Sheet.NONE && state.pageControl != null) {
        val control = state.pageControl ?: return@BackHandler
        browser.answerPageControl(control.id, backResponse(control.content))
    }
    BackHandler(enabled = sheet == Sheet.NONE && state.reader != null) { browser.closeReader() }
    // Find and Home editing take precedence over going back in the page.
    BackHandler(enabled = sheet == Sheet.NONE && state.find != null) { browser.closeFind() }
    BackHandler(enabled = sheet == Sheet.NONE && home && homeMode == AddressEditorMode.EDITING) { stopHomeInput() }
    // A page question that appears meanwhile is answered first, as Back answers it in the page.
    BackHandler(enabled = sheet == Sheet.NONE && editingPage && state.pageControl == null) { stopPageInput() }

    val density = LocalDensity.current
    val imeShown = WindowInsets.ime.getBottom(density) > 0
    // Taken when the editor opens, given back once its keyboard has gone: resizing the surface
    // under the dimmed page would show its last frame scaled until the engine draws one, and lay
    // the page out again twice for nothing. A page field that asks for the keyboard ends it.
    LaunchedEffect(editingPage, imeShown) {
        holdSurface = editingPage || (holdSurface && imeShown)
    }
    val accessibleControls = rememberAccessibleControlsRequired()
    val barBehavior = when {
        home || page == null || state.engine != EngineStatus.READY || tab.crashed || tab.loadFailed -> BottomBarBehavior.PINNED
        sheet != Sheet.NONE || state.find != null || state.pageControl != null || appLink != null -> BottomBarBehavior.PINNED
        editingPage || imeShown || accessibleControls -> BottomBarBehavior.PINNED
        else -> BottomBarBehavior.SCROLLING
    }
    // A new document or editing context starts with reachable controls, not an old drag.
    var barDirection by remember(tab?.id, page, state.reader, barBehavior) { mutableStateOf<ScrollEdge?>(null) }
    val showBar = bottomBarVisible(barDirection, barBehavior)
    // The engine surface lies under the bottom controls and keeps its size when they come or go
    // (see EngineSurfaceView.coverBottom); the engine lays pages out above the pixels they cover.
    // Measured from both boxes' bottom edges: the page box ends where the controls start.
    var pageBottom by remember { mutableFloatStateOf(0f) }
    var surfaceBottom by remember { mutableFloatStateOf(0f) }
    val drawnOverlap = (surfaceBottom - pageBottom).roundToInt().coerceAtLeast(0)
    val coveredInset = if (showBar || state.find != null) drawnOverlap else 0
    var viewportRequest by remember { mutableStateOf<ViewportRequest?>(null) }
    LaunchedEffect(coveredInset, surfaceView, state.engine, holdSurface) {
        // The editor covers the page: its layout waits, and catches up once the surface is free.
        if (holdSurface) return@LaunchedEffect
        val view = surfaceView ?: return@LaunchedEffect
        viewportRequest = ViewportRequest(view.coverBottom(coveredInset), coveredInset)
    }
    // A bar the user's scroll hides stays until the engine has drawn the page under it.
    var barHoldExpired by remember(showBar) { mutableStateOf(false) }
    LaunchedEffect(showBar) {
        if (showBar) return@LaunchedEffect
        delay(BAR_HOLD_TIMEOUT_MS)
        barHoldExpired = true
    }
    val drawBar = showBar || bottomBarHeld(viewportRequest, paintedViewport?.request, barHoldExpired)
    val pageInsets = if (drawBar || state.find != null) {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    } else WindowInsets.safeDrawing
    var barHeight by remember { mutableStateOf(0.dp) }
    val noticeInset = if (drawBar || state.find != null) barHeight else with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
    // A shown snackbar covers the page's bottom-left corner, where the scroll button sits, and
    // takes the taps there: the button moves above it. Measured from both edges, as snackbars
    // clear the bottom bar's last height, which is not the find bar's.
    var snackbarTop by remember { mutableStateOf<Float?>(null) }
    val snackbarReach = (snackbarTop?.let { top -> with(density) { (pageBottom - top).toDp() } } ?: 0.dp).coerceAtLeast(0.dp)
    val scrollButtonPadding = scrollButtonPadding(snackbarReach)
    val openSettings = { sheet = Sheet.SETTINGS }
    // Cancel, Back, a swipe or another sheet ends a report: its draft and screenshot go.
    LaunchedEffect(sheet) {
        if (sheet == Sheet.REPORT) return@LaunchedEffect
        reportDraftId?.let(graph.reportDrafts::remove)
        reportDraftId = null
    }
    val startReport: () -> Unit = start@{
        // Snapshot at the tap (invariant 6): the report describes what was shown then.
        val shown = browser.browserState.value
        val reported = shown.activeTab ?: return@start
        val url = reported.url ?: return@start
        if (reportStarting) return@start
        val createdAt = System.currentTimeMillis()
        val snapshot = ReportSnapshot(
            url = url,
            // As for Share: an error page's title is the engine's, not the page's.
            title = reported.title.takeUnless { reported.loadFailed },
            display = if (shown.reader != null) ReportDisplay.READER else ReportDisplay.PAGE,
            loadFailed = reported.loadFailed,
            crashed = reported.crashed,
            zoomPercent = graph.settings.textSize.value.percent,
            density = resources.displayMetrics.density,
        )
        val view = surfaceView
        val blocker = screenshotBlocker(shown, surface = view != null)
        reportStarting = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Nothing is captured for a report that cannot be sent.
                val service = graph.integrations.reports.service.first()
                if (service == null) {
                    // Its own coroutine: a waiting snackbar must not hold up the next report.
                    scope.launch { offerSettings(snackbar, resources.getString(R.string.report_not_set_up), resources, openSettings) }
                    return@launch
                }
                // Before any sheet or keyboard: the keyboard shrinks the page, which lays out again.
                // Only the part of the surface above the bottom controls shows the page.
                val frame = if (blocker == null && view != null) {
                    copyFrame(view, coveredBottom = drawnOverlap)
                } else FrameCopy.Unavailable(blocker ?: ScreenshotOmission.NOT_SHOWN)
                // Only the page shown at the tap may be sent.
                val checked = checkedFrame(frame, reported = reported.page, shown = browser.browserState.value.activeTab?.page)
                reportDraftId = graph.reportDrafts.start(createdAt, snapshot, displayHost(service.baseUrl), checked).id
                sheet = Sheet.REPORT
            } finally {
                reportStarting = false
            }
        }
    }
    val sendReport: (ReportDraftState, ReportDraft) -> Unit = { draft, report ->
        // Stored in the process scope; this screen only tells the user how it went.
        graph.reportDrafts.send(draft, report)?.let { stored ->
            scope.launch {
                val result = stored.await()
                when {
                    result == ReportRequest.Queued -> {
                        if (reportDraftId == draft.id) sheet = Sheet.NONE
                        snackbar.showSnackbar(resources.getString(R.string.report_queued))
                    }
                    // The sheet shows the problem; once it is gone, a snackbar has to.
                    reportDraftId != draft.id -> ReportProblem.of(result)?.let { reportLost(it, snackbar, resources, openSettings) }
                }
            }
        }
    }
    val selectMenuAction: (MoreAction) -> Unit = select@{ action ->
        menu.dismiss()
        // A page can replace itself while the finger is held over a menu row.
        if (action !in moreActions(tab, readerDisplay)) return@select
        when (action) {
            MoreAction.FORWARD -> browser.goForward()
            MoreAction.RELOAD -> browser.reload()
            MoreAction.SHARE -> tab?.let { page ->
                page.url?.let { url -> share(context, url, resources.getString(R.string.share_chooser), page.title.takeUnless { page.loadFailed }) }
            }
            MoreAction.PAGE_INFO -> sheet = Sheet.PAGE_INFO
            MoreAction.FIND -> browser.openFind()
            MoreAction.PLAY_MEDIA -> tab?.mediaPage?.let(playMedia)
            MoreAction.REPORT_SITE -> startReport()
            MoreAction.TEXT_SIZE -> sheet = Sheet.TEXT_SIZE
            MoreAction.SETTINGS -> sheet = Sheet.SETTINGS
        }
    }
    val changeHome: (suspend () -> Unit) -> Unit = { change ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { change() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snackbar.showSnackbar(resources.getString(R.string.home_change_failed))
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The page box's area with the bottom controls hidden, under the controls when they show.
        // Touches pass the page box, which takes none of its own, to reach it.
        AndroidView(
            factory = { viewContext ->
                EngineSurfaceView(viewContext) { graph.engineRunning() }.also { surfaceView = it }
            },
            modifier = Modifier
                .fillMaxSize()
                // Held, the keyboard covers the surface's bottom instead of shrinking it: the
                // safe area without the keyboard at all. (Consuming the keyboard's inset would not
                // do: while it rises past the navigation bar, safeDrawing's bottom shrinks.)
                .then(
                    if (holdSurface) {
                        Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                    } else {
                        Modifier.imePadding().windowInsetsPadding(WindowInsets.safeDrawing)
                    },
                )
                .onGloballyPositioned { surfaceBottom = it.boundsInRoot().bottom },
            update = { view ->
                view.observeScroll(page) {
                    barDirection = it
                    scrollEdge = it.takeIf { reloadPull == null }
                    scrollActivity++
                }
            },
        )
        // The status bar's inset is taken here, once: the page box keeps clear of it, and so does
        // the bar when the address editor's matches make it tall.
        Column(Modifier.fillMaxSize().imePadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Behind the address editor, the page's own controls are no TalkBack stops.
                    .then(if (editingPage) Modifier.clearAndSetSemantics {} else Modifier)
                    .windowInsetsPadding(pageInsets)
                    .onGloballyPositioned { pageBottom = it.boundsInRoot().bottom },
            ) {
                PageOverlay(
                    state = state,
                    onReload = browser::reload,
                    home = {
                        HomeScreen(
                            links = homeLinks,
                            continuation = continuation,
                            settings = graph.settings,
                            onOpenUrl = { url ->
                                (AddressPolicy.interpret(url).primary as? AddressDecision.Navigate)?.let {
                                    stopHomeInput()
                                    browser.openUrl(it.url, Placement.CURRENT_TAB)
                                }
                            },
                            onContinue = { id -> stopHomeInput(); browser.selectTab(id) },
                            onEdit = { link ->
                                homeEditLink = link
                                homeEditPage = if (link == null) HomeEditPage.OVERVIEW else HomeEditPage.LINK
                                sheet = Sheet.HOME_EDIT
                            },
                            onAdd = { homeEditLink = null; homeEditPage = HomeEditPage.LINK; sheet = Sheet.HOME_EDIT },
                            onHide = { site -> changeHome { graph.home.hideSite(site) } },
                            onUnpin = { id -> changeHome { graph.home.unpin(id) } },
                            // Paint the reserved notice space too, covering the native surface.
                            modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(bottom = snackbarReach),
                        )
                    },
                )
                if (state.engine == EngineStatus.READY && state.reader == null && page != null && !tab.loadFailed && !editingPage) {
                    reloadPull?.let { pull ->
                        ReloadPullIndicator(
                            progress = pull.progress,
                            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                        )
                    }
                    scrollEdge?.takeIf { reloadPull == null }?.let { edge ->
                        ScrollEdgeButton(
                            edge = edge,
                            scrollActivity = scrollActivity,
                            onClick = {
                                scrollEdge = null
                                browser.scrollToEdge(page, edge)
                            },
                            modifier = Modifier.align(AbsoluteAlignment.BottomLeft).padding(scrollButtonPadding),
                        )
                    }
                }
                state.reader?.let { reader ->
                    ReaderView(
                        state = reader,
                        textSize = textSize,
                        scrollButtonPadding = scrollButtonPadding,
                        onScroll = { barDirection = it },
                        // A link the address policy accepts leaves reader view and opens in the tab;
                        // a refused one (e.g. href="https://") keeps the article open.
                        onOpenLink = { url ->
                            (AddressPolicy.interpret(url).primary as? AddressDecision.Navigate)?.let {
                                browser.closeReader()
                                browser.openUrlInTab(reader.tabId, it.url)
                            }
                        },
                        onClose = browser::closeReader,
                    )
                }
                // The page dims behind its address editor; a tap on it leaves the editor. Not the
                // Column's scoped overload: this is the page box.
                androidx.compose.animation.AnimatedVisibility(
                    visible = editingPage,
                    enter = fadeIn(tween(SCRIM_FADE_MS)),
                    exit = fadeOut(tween(SCRIM_FADE_MS)),
                    modifier = Modifier.matchParentSize(),
                ) {
                    Scrim(onDismiss = stopPageInput)
                }
            }
            // Find in page takes the bottom bar's place, above the keyboard like it.
            state.find?.let { find ->
                FindBar(
                    state = find,
                    onFind = browser::find,
                    onClose = browser::closeFind,
                    onNewTab = newTab,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                )
            }
            if (state.find == null && drawBar) BottomBar(
                tab = tab,
                openTabs = state.openTabCount,
                tabCounter = tabCounter,
                saveStatus = saveStatus,
                histerStored = histerStored && tab?.loading != true,
                backEnabled = state.reader != null || state.pageControl != null || backInPage != null,
                // Like system Back: Reader view closes first, an open page question is answered,
                // then Back moves through the page.
                onBack = {
                    val control = state.pageControl
                    when {
                        state.reader != null -> browser.closeReader()
                        control != null -> browser.answerPageControl(control.id, backResponse(control.content))
                        else -> backInPage?.invoke()
                    }
                },
                onAddress = editAddress,
                onCloseTab = { tab?.let { browser.closeTab(it.id) } },
                readerDisplay = readerDisplay,
                onStop = { tab?.let { browser.stopLoading(it.id) } },
                onReader = { if (state.reader != null) browser.closeReader() else browser.openReader() },
                onPlayMedia = tab?.mediaPage?.takeIf { tab.media.isNotEmpty() }?.let { page -> { playMedia(page) } },
                onNewTab = newTab,
                onHister = { openRecent(RecentScope.HISTER) },
                onKarakeep = { openRecent(RecentScope.KARAKEEP) },
                onSave = {
                    // Snapshot now: switching tabs right after the tap must not change what is saved.
                    val url = tab?.url
                    val title = tab?.title
                    scope.launch {
                        // The Save icon shows a queued save of the page itself.
                        reportSave(graph.integrations.save(url, title), snackbar, resources, queued = null, onOpenSettings = openSettings)
                    }
                },
                onRecent = { openRecent(RecentScope.OPEN) },
                onRecentLongPress = { browser.focusPreviousTab() },
                onSwipeTab = { step -> browser.stepTab(step) },
                editorMode = when {
                    home -> homeMode
                    editingPage -> AddressEditorMode.EDITING
                    else -> AddressEditorMode.IDLE
                },
                editor = {
                    if (home) {
                        AddressEditor(
                            target = tab?.id,
                            initialText = homeText,
                            restartKey = homeEntry,
                            recent = { browser.recent(HistoryDepth.ALL_RETAINED) },
                            onDecision = { decision -> openAddress(tab?.id, decision) },
                            onSelectTab = { id -> stopHomeInput(); browser.selectTab(id) },
                            onNewTab = newTab,
                            onDismiss = stopHomeInput,
                            mode = homeMode,
                            focusKey = homeEntry.takeIf { homeMode == AddressEditorMode.EDITING },
                            onFocused = { homeMode = AddressEditorMode.EDITING },
                            placeholder = R.string.home_search_hint,
                        )
                    } else {
                        AddressEditor(
                            target = addressTab,
                            initialText = pageText,
                            restartKey = pageEntry,
                            // Suggestions search the retained history, like a Recent search.
                            recent = { browser.recent(HistoryDepth.ALL_RETAINED) },
                            onDecision = { decision -> openAddress(addressTab, decision) },
                            // The exact tab, as in Recent: switching never follows an equal address elsewhere.
                            onSelectTab = { id -> stopPageInput(); browser.selectTab(id) },
                            onNewTab = newTab,
                            onDismiss = stopPageInput,
                            prefill = AddressPrefill.CURRENT_PAGE,
                            focusKey = pageEntry,
                        )
                    }
                },
                modifier = Modifier.onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                menuButton = {
                    ToolbarMenuButton(menu, selectMenuAction, if (home) MenuButtonDisplay.LABEL else MenuButtonDisplay.ICON)
                },
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
        // visible bar includes navigation insets; hidden controls reserve only those insets.
        if (sheet != Sheet.RECENT) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(bottom = noticeInset + SNACKBAR_GAP)
                    // The hosts themselves, without their offset: empty while no snackbar shows.
                    .onGloballyPositioned { snackbarTop = it.takeIf { host -> host.size.height > 0 }?.boundsInRoot()?.top },
            ) {
                NoticeHost(snackbar)
                // A notice takes the offer's place while it shows; the offer comes back after it.
                if (snackbar.currentSnackbarData == null) NoticeHost(offerHost)
            }
        }
        ToolbarMenu(menu, tab, readerDisplay, selectMenuAction)
    }

    val dismiss = { sheet = Sheet.NONE }
    when (sheet) {
        Sheet.NONE -> Unit
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
            onNewTab = newTab,
            snackbar = snackbar,
            onSettings = openSettings,
            onDismiss = dismiss,
        )
        Sheet.MEDIA -> {
            val page = tab?.mediaPage
            if (page != null && tab.media.isNotEmpty()) {
                MediaSheet(
                    media = tab.media,
                    onPlay = { entry ->
                        dismiss()
                        openPlayer(page, entry.id)
                    },
                    onDismiss = dismiss,
                )
            } else {
                // The page changed under the sheet (another page, another tab): close it from an
                // effect, as Page info does.
                LaunchedEffect(Unit) { dismiss() }
            }
        }
        Sheet.SETTINGS -> SettingsSheet(
            integrations = graph.integrations,
            settings = graph.settings,
            retention = graph.retention,
            onUnusedTabsChanged = browser::runMaintenance,
            notices = graph.notices,
            onDismiss = dismiss,
        )
        Sheet.HOME_EDIT -> HomeEditorSheet(
            home = graph.home,
            states = homeLinks,
            settings = graph.settings,
            initialLink = homeEditLink,
            initialPage = homeEditPage,
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
        Sheet.REPORT -> {
            val draft = reportDraft?.takeIf { it.id == reportDraftId }
            if (draft != null) {
                // The problem's Settings button opens Settings over the sheet, not in its place:
                // another sheet would end the report, and the text typed so far with it.
                var settingsOverReport by rememberSaveable(draft.id) { mutableStateOf(false) }
                // Settings can set the service up or change it: name the one Send would use now.
                val service by graph.integrations.reports.service.collectAsStateWithLifecycle(null, context = Dispatchers.Main.immediate)
                ReportSheet(
                    draft = draft,
                    serviceHost = service?.let { displayHost(it.baseUrl) } ?: draft.serviceHost,
                    environment = graph.reportEnvironment,
                    onSend = { report -> sendReport(draft, report) },
                    onOpenSettings = { settingsOverReport = true },
                    onDismiss = dismiss,
                )
                if (settingsOverReport) {
                    SettingsSheet(
                        integrations = graph.integrations,
                        settings = graph.settings,
                        retention = graph.retention,
                        onUnusedTabsChanged = browser::runMaintenance,
                        notices = graph.notices,
                        onDismiss = { settingsOverReport = false },
                    )
                }
            } else {
                // Queued, or the process died since: the draft is gone, so the sheet closes too.
                LaunchedEffect(Unit) { dismiss() }
            }
        }
        Sheet.TEXT_SIZE -> {
            TextSizeDialog(
                current = { textSizeDraft.displayed },
                onChange = { size -> textSizeDraft.choose(size) },
                onDismiss = {
                    textSizeDraft.reset()
                    dismiss()
                },
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
            onDownloadImage = { url ->
                // The system download and its confirmation outlive this dialog and Activity.
                val application = context.applicationContext
                graph.scope.launch {
                    val message = when (graph.imageDownloads.enqueue(url)) {
                        ImageDownloadResult.QUEUED -> R.string.image_download_queued
                        ImageDownloadResult.UNSUPPORTED_ADDRESS -> R.string.image_download_unsupported
                        ImageDownloadResult.UNAVAILABLE -> R.string.image_download_unavailable
                    }
                    withContext(Dispatchers.Main.immediate) {
                        Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
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

/**
 * Tells the user that a report was not stored after its sheet closed. The draft went with the
 * sheet, so nothing can be tried again; a problem Settings can fix offers Settings.
 */
internal suspend fun reportLost(problem: ReportProblem, snackbar: SnackbarHostState, resources: Resources, onOpenSettings: () -> Unit) {
    val message = resources.getString(problem.withoutSheet)
    if (problem.offersSettings) offerSettings(snackbar, message, resources, onOpenSettings) else snackbar.showSnackbar(message)
}

/** [message] with a Settings action, for a feature that needs a service set up there. */
private suspend fun offerSettings(snackbar: SnackbarHostState, message: String, resources: Resources, onOpenSettings: () -> Unit) {
    // Timed, not Indefinite (the default with an action): it must not block later notices.
    snackbar.currentSnackbarData?.dismiss()
    val result = snackbar.showSnackbar(message, actionLabel = resources.getString(R.string.action_open_settings), duration = SnackbarDuration.Long)
    if (result == SnackbarResult.ActionPerformed) onOpenSettings()
}

/** What covers the page area when there is no page to show, or the page cannot be shown. */
@Composable
private fun PageOverlay(
    state: BrowserState,
    onReload: () -> Unit,
    home: @Composable () -> Unit,
) {
    val tab: ActiveTab? = state.activeTab
    when {
        state.engine == EngineStatus.LIBRARY_MISSING -> OverlayMessage(stringResource(R.string.engine_missing))
        state.engine == EngineStatus.FAILED -> OverlayMessage(stringResource(R.string.engine_failed))
        // No tab yet does not mean home: a restored session must not flash "New tab" first.
        // The launch screen covers this for up to two seconds; a slower restore says what happens.
        state.session == SessionState.RESTORING -> OverlayMessage(stringResource(R.string.session_restoring))
        tab == null || tab.isHome -> home()
        tab.crashed -> OverlayMessage(stringResource(R.string.page_crashed)) {
            Button(onClick = onReload) { Text(stringResource(R.string.action_reload)) }
        }
        state.engine == EngineStatus.STARTING -> OverlayMessage(stringResource(R.string.engine_starting))
    }
}

/** Internal for OverlayMessageTest's large-font reachability check. */
@Composable
internal fun OverlayMessage(message: String, action: @Composable () -> Unit = {}) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Scrolls at 200% font on a small screen: the message and its action must stay
        // reachable however little room is left (Phase 1 finding).
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A polite live region: a crash or an engine failure is announced when it appears.
            Text(message, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
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
 * them out of suggestions/history. A rendering hint, not access control. The hint covers the
 * text, not [label], so the label is a fixed name ("Address"), never the address itself.
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

/**
 * Back's answer to an open page question: Stay on a leave-page question (leaving is the choice
 * that loses the page's text), the dialog's default Dismiss otherwise.
 */
internal fun backResponse(content: PageControlContent): ControlResponse =
    if (content is PageControlContent.LeavePage) LeavePageChoice.STAY.response else ControlResponse.Dismiss

/** Space between a snackbar and the bottom bar under it. */
private val SNACKBAR_GAP = 8.dp

/** The page's dimming behind the address editor fades in and out this fast. */
private const val SCRIM_FADE_MS = 150

/**
 * Dims the page behind the address editor and takes its touches: a tap leaves the editor, and
 * nothing reaches the page meanwhile. Not a TalkBack stop; the editor's own Close and Back are.
 */
@Composable
private fun Scrim(onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            .pointerInput(Unit) { detectTapGestures { dismiss() } }
            .clearAndSetSemantics {},
    )
}

/** Material's modal scrim opacity. */
private const val SCRIM_ALPHA = 0.32f

/** Longest a hidden bar waits for the engine to draw the page under it (bottomBarHeld). */
private const val BAR_HOLD_TIMEOUT_MS = 400L

/**
 * "Open this link in another app?" over the page. The page wrote the link, so it is shown as is,
 * scheme first and cut short, and nothing leaves Combray without the user's Open.
 */
@Composable
private fun AppLinkDialog(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_link_question)) },
        text = { Text(url.take(APP_LINK_SHOWN_CHARS), maxLines = APP_LINK_SHOWN_LINES, overflow = TextOverflow.Ellipsis) },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.action_open_app_link)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Enough of a link to tell a phone number, an address or an app apart; the rest is the page's. */
private const val APP_LINK_SHOWN_CHARS = 200
private const val APP_LINK_SHOWN_LINES = 3
