package ch.lkmc.combray.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.browser.BrowserState
import ch.lkmc.combray.core.browser.EngineStatus
import ch.lkmc.combray.core.browser.KeyboardRequest
import ch.lkmc.combray.core.browser.Notice
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.SessionState
import ch.lkmc.combray.core.integration.SaveRequest
import ch.lkmc.combray.core.integration.SaveStatus
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.engine.EngineSurfaceView
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class Sheet { NONE, ADDRESS, RECENT, MORE, SETTINGS, PAGE_INFO }

/**
 * The browser: page area on top, all routine actions in the bottom bar (R3). The engine
 * surface stays in place under every overlay, so sheets never release a renderer.
 */
@Composable
fun BrowserScreen(graph: AppGraph, onLeave: () -> Unit) {
    val browser = graph.browser
    val state by browser.browserState.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    var surfaceView by remember { mutableStateOf<EngineSurfaceView?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val tab = state.activeTab
    // Tagged with its address: until the new page's status arrives, the bar keeps the last one.
    val saveStatus by remember(tab?.url) {
        val url = tab?.url
        graph.integrations.saveStatus(url).map { PageSaveStatus(url, it) }
    }.collectAsStateWithLifecycle(PageSaveStatus(null, SaveStatus.Unsaved))
    val histerService by graph.integrations.hister.service.collectAsStateWithLifecycle(null)

    val closedMessage = stringResource(R.string.notice_tab_closed)
    val notOpened = stringResource(R.string.notice_address_not_opened)
    val undo = stringResource(R.string.action_undo)
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
            }
        }
    }

    // The page focused or left an editable element.
    LaunchedEffect(state.keyboard, surfaceView) {
        val request = state.keyboard ?: return@LaunchedEffect
        val view = surfaceView ?: return@LaunchedEffect
        when (request) {
            is KeyboardRequest.Show -> view.showKeyboard(request.kind)
            KeyboardRequest.Hide -> view.hideKeyboard()
        }
        browser.keyboardHandled(request)
    }

    // The address editor submits to the tab it was opened for; null opens a new tab.
    var addressTab by rememberSaveable { mutableStateOf<String?>(null) }
    val editAddress = {
        addressTab = tab?.id
        sheet = Sheet.ADDRESS
    }
    // An unused blank tab is the new tab already; typing there makes no second one.
    val editNewTab = {
        addressTab = tab?.takeIf { it.isHome }?.id
        sheet = Sheet.ADDRESS
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

    val notify: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

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
                )
                PageOverlay(
                    state = state,
                    onOpenAddress = editAddress,
                    onOpenRecent = { sheet = Sheet.RECENT },
                    onReload = browser::reload,
                )
                state.reader?.let { reader ->
                    ReaderView(
                        state = reader,
                        // A link leaves reader view and opens in the tab, through the address policy.
                        onOpenLink = { url ->
                            browser.closeReader()
                            (AddressPolicy.interpret(url).primary as? AddressDecision.Navigate)?.let { browser.openUrlInTab(reader.tabId, it.url) }
                        },
                        onClose = browser::closeReader,
                    )
                }
            }
            BottomBar(
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
                        when (graph.integrations.save(url, title)) {
                            SaveRequest.NotConfigured -> {
                                // Timed, not Indefinite (the default with an action): it must not block later notices.
                                snackbar.currentSnackbarData?.dismiss()
                                val result = snackbar.showSnackbar(
                                    resources.getString(R.string.save_not_set_up),
                                    actionLabel = resources.getString(R.string.action_open_settings),
                                    duration = SnackbarDuration.Long,
                                )
                                if (result == SnackbarResult.ActionPerformed) sheet = Sheet.SETTINGS
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
                                if (result == SnackbarResult.ActionPerformed) sheet = Sheet.SETTINGS
                            }
                            SaveRequest.Queued -> Unit
                        }
                    }
                },
                onShare = {
                    tab?.let { page -> page.url?.let { url -> share(context, url, resources.getString(R.string.share_chooser), page.title.takeUnless { page.loadFailed }) } }
                },
                onRecent = { sheet = Sheet.RECENT },
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
        // separate window over this one and shows the snackbars itself while it is open.
        if (sheet != Sheet.RECENT) {
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(bottom = 72.dp))
        }
    }

    val dismiss = { sheet = Sheet.NONE }
    when (sheet) {
        Sheet.NONE -> Unit
        Sheet.ADDRESS -> AddressSheet(
            target = addressTab,
            currentUrl = addressTab?.let { id -> tab?.takeIf { it.id == id }?.url },
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
            onDismiss = dismiss,
        )
        Sheet.RECENT -> RecentSheet(
            browser = browser,
            integrations = graph.integrations,
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
            canOpenInNewTab = { url -> navigation(url) != null },
            // The control belongs to the focused tab's page: Back on the new tab returns there.
            onOpenInNewTab = { url ->
                navigation(url)?.let { link ->
                    val opener = tab?.id
                    if (opener != null) browser.openUrlFromTab(link.url, opener) else browser.openUrl(link.url, Placement.NEW_TAB)
                }
            },
            onShare = { url -> share(context, url, resources.getString(R.string.share_chooser)) },
        )
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
