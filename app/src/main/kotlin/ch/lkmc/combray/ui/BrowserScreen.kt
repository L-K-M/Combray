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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
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
import ch.lkmc.combray.core.integration.SaveRequest
import ch.lkmc.combray.core.integration.SaveStatus
import ch.lkmc.combray.core.integration.SensitiveUrls
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.engine.Appearance
import ch.lkmc.combray.engine.EngineSurfaceView
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import ch.lkmc.combray.engine.ScrollEdge
import ch.lkmc.combray.player.PlayerActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private enum class Sheet { NONE, ADDRESS, RECENT, SETTINGS, PAGE_INFO, TEXT_SIZE, HOME_EDIT, MEDIA }

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
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    var homeMode by rememberSaveable { mutableStateOf(AddressEditorMode.IDLE) }
    var homeEntry by rememberSaveable { mutableIntStateOf(0) }
    var homeText by rememberSaveable { mutableStateOf("") }
    var homeEditLink by remember { mutableStateOf<HomeLink?>(null) }
    var homeEditPage by rememberSaveable { mutableStateOf(HomeEditPage.OVERVIEW) }
    var surfaceView by remember { mutableStateOf<EngineSurfaceView?>(null) }
    val snackbar = remember { SnackbarHostState() }
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
    val notify: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
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
                is Notice.MediaPlayable -> {
                    // Replaces the page's plain notice if it still shows: one page, one message.
                    snackbar.currentSnackbarData?.takeIf { it.visuals.message == mediaUnsupported }?.dismiss()
                    // Long: with an action, Material extends it to the accessibility timeout.
                    val result = snackbar.showSnackbar(mediaPlayable, actionLabel = play, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) playMedia(notice.page)
                }
                Notice.PopupBlocked -> snackbar.showSnackbar(popupBlocked)
            }
        }
    }

    // Showing another tab (a swipe on the bar, Recent's long-press, a snackbar's Show, a page's
    // window) hides the keyboard: the engine sends typing to the page shown, so keys meant for
    // one page's field must not go on into the next page. Before the request below, so a field
    // the new page focuses at once still gets the keyboard.
    var keyboardTab by remember { mutableStateOf(tab?.id) }
    LaunchedEffect(tab?.id) {
        if (tab?.id == keyboardTab) return@LaunchedEffect
        keyboardTab = tab?.id
        surfaceView?.hideKeyboard()
    }

    // The page focused or left an editable element.
    LaunchedEffect(state.keyboard, surfaceView) {
        val request = state.keyboard ?: return@LaunchedEffect
        val view = surfaceView ?: return@LaunchedEffect
        when (request) {
            is KeyboardRequest.Show -> view.showKeyboard(request.kind)
            is KeyboardRequest.Prepare -> view.prepareKeyboard(request.kind)
            // A page's blur must not hide the keyboard now owned by Find or Home's search field.
            KeyboardRequest.Hide -> if (state.find == null && !(home && homeMode == AddressEditorMode.EDITING)) view.hideKeyboard()
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

    // The address editor submits to the tab it was opened for; null opens a new tab.
    var addressTab by rememberSaveable { mutableStateOf<String?>(null) }
    val editAddress = {
        addressTab = tab?.id
        sheet = Sheet.ADDRESS
    }
    // New-tab entry points all focus the existing Home field. Save its prefill before queuing
    // the blank tab so rotation cannot drop text another app sent while creation is pending.
    val editNewTabWith = { text: String? ->
        homeText = text.orEmpty()
        homeEntry++
        homeMode = AddressEditorMode.EDITING
        sheet = Sheet.NONE
        surfaceView?.hideKeyboard()
        browser.newTab()
    }
    val newTab = { editNewTabWith(null) }
    val openAddress = { target: String?, decision: AddressDecision ->
        sheet = Sheet.NONE
        stopHomeInput()
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
    BackHandler(enabled = sheet == Sheet.NONE && state.reader != null) { browser.closeReader() }
    // Find and Home editing take precedence over going back in the page.
    BackHandler(enabled = sheet == Sheet.NONE && state.find != null) { browser.closeFind() }
    BackHandler(enabled = sheet == Sheet.NONE && home && homeMode == AddressEditorMode.EDITING) { stopHomeInput() }

    val density = LocalDensity.current
    val accessibleControls = rememberAccessibleControlsRequired()
    val barBehavior = when {
        home || page == null || state.engine != EngineStatus.READY || tab.crashed || tab.loadFailed -> BottomBarBehavior.PINNED
        sheet != Sheet.NONE || state.find != null || state.pageControl != null || appLink != null -> BottomBarBehavior.PINNED
        WindowInsets.ime.getBottom(density) > 0 || accessibleControls -> BottomBarBehavior.PINNED
        else -> BottomBarBehavior.SCROLLING
    }
    // A new document or editing context starts with reachable controls, not an old drag.
    var barDirection by remember(tab?.id, page, state.reader, barBehavior) { mutableStateOf<ScrollEdge?>(null) }
    val showBar = bottomBarVisible(barDirection, barBehavior)
    val pageInsets = if (showBar || state.find != null) {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    } else WindowInsets.safeDrawing
    var barHeight by remember { mutableStateOf(0.dp) }
    val noticeInset = if (showBar || state.find != null) barHeight else with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
    // A shown snackbar covers the page's bottom-left corner, where the scroll button sits, and
    // takes the taps there: the button moves above it. Measured from both edges, as snackbars
    // clear the bottom bar's last height, which is not the find bar's.
    var pageBottom by remember { mutableFloatStateOf(0f) }
    var snackbarTop by remember { mutableStateOf<Float?>(null) }
    val snackbarReach = (snackbarTop?.let { top -> with(density) { (pageBottom - top).toDp() } } ?: 0.dp).coerceAtLeast(0.dp)
    val scrollButtonPadding = scrollButtonPadding(snackbarReach)
    val openSettings = { sheet = Sheet.SETTINGS }
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
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(pageInsets)
                    .onGloballyPositioned { pageBottom = it.boundsInRoot().bottom },
            ) {
                AndroidView(
                    factory = { viewContext ->
                        EngineSurfaceView(viewContext) { graph.engineRunning() }.also { surfaceView = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.observeScroll(page) {
                            barDirection = it
                            scrollEdge = it.takeIf { reloadPull == null }
                            scrollActivity++
                        }
                    },
                )
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
                if (state.engine == EngineStatus.READY && state.reader == null && page != null && !tab.loadFailed) {
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
                    onNewTab = newTab,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                )
            }
            if (state.find == null && showBar) BottomBar(
                tab = tab,
                openTabs = state.openTabCount,
                tabCounter = tabCounter,
                saveStatus = saveStatus,
                histerStored = histerStored && tab?.loading != true,
                backEnabled = state.reader != null || backInPage != null,
                // Like system Back: Reader view closes first, then Back moves through the page.
                onBack = { if (state.reader != null) browser.closeReader() else backInPage?.invoke() },
                onAddress = editAddress,
                onCloseTab = { tab?.let { browser.closeTab(it.id) } },
                readerDisplay = readerDisplay,
                onStop = { tab?.let { browser.stopLoading(it.id) } },
                onReader = { if (state.reader != null) browser.closeReader() else browser.openReader() },
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
                homeMode = homeMode,
                homeSearch = {
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
                        modifier = if (homeMode == AddressEditorMode.EDITING) {
                            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        } else Modifier,
                    )
                },
                modifier = Modifier.onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                menuButton = { ToolbarMenuButton(menu, selectMenuAction) },
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
            NoticeHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(bottom = noticeInset + SNACKBAR_GAP)
                    // The host itself, without its offset: empty while no snackbar shows.
                    .onGloballyPositioned { snackbarTop = it.takeIf { host -> host.size.height > 0 }?.boundsInRoot()?.top },
            )
        }
        ToolbarMenu(menu, tab, readerDisplay, selectMenuAction)
    }

    val dismiss = { sheet = Sheet.NONE }
    when (sheet) {
        Sheet.NONE -> Unit
        Sheet.ADDRESS -> AddressSheet(
            target = addressTab,
            restartKey = launches,
            // On a Kagi results page, start with its search words instead of the address.
            initialText = addressTab?.let { id -> tab?.takeIf { it.id == id }?.url }?.let { AddressPolicy.kagiQuery(it) ?: it },
            // Suggestions search the retained history, like a Recent search.
            recent = { browser.recent(HistoryDepth.ALL_RETAINED) },
            onDecision = { decision -> openAddress(addressTab, decision) },
            // The exact tab, as in Recent: switching never follows an equal address elsewhere.
            onSelectTab = { tabId ->
                dismiss()
                browser.selectTab(tabId)
            },
            onNewTab = newTab,
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

/** Space between a snackbar and the bottom bar under it. */
private val SNACKBAR_GAP = 8.dp

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
