package ch.lkmc.combray.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.combray.R
import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.integration.Integrations
import ch.lkmc.combray.core.integration.KarakeepBookmark
import ch.lkmc.combray.core.integration.KarakeepPage
import ch.lkmc.combray.core.integration.KarakeepRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface BookmarksUi {
    data object Idle : BookmarksUi

    data class Answer(val read: KarakeepRead<KarakeepPage>) : BookmarksUi
}

/**
 * The Karakeep scope of Recent: the newest bookmarks, or Karakeep's search for the text typed
 * here. Only this field's text is sent. A newer query's answer always wins over an older one,
 * and the last answer stays on screen while the next search is in flight.
 */
@Composable
fun KarakeepPanel(integrations: Integrations, initialQuery: String, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val service by integrations.karakeepService.collectAsStateWithLifecycle(null, context = Dispatchers.Main.immediate)
    KarakeepPanelContent(service, initialQuery, { page -> integrations.karakeepBookmarks(page.query, page.cursor, page.service) }, onOpen, onSettings)
}

/**
 * The panel's state and UI, separated from service observation so delayed-read checks can drive
 * it directly. [readBookmarks] receives the page's bound query, cursor and destination.
 */
@Composable
internal fun KarakeepPanelContent(
    service: ServiceEntity?,
    initialQuery: String,
    readBookmarks: suspend (SearchPage) -> KarakeepRead<KarakeepPage>?,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    // A query seeded from Recent's local filter is not the user's text: it is sent only after
    // the user types in this field or submits it. The submit nonce re-runs an unchanged query.
    var typed by rememberSaveable { mutableStateOf(false) }
    var submit by rememberSaveable { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf<BookmarksUi>(BookmarksUi.Idle) }
    var bookmarks by remember { mutableStateOf<List<KarakeepBookmark>>(emptyList()) }
    var nextPage by remember { mutableStateOf<SearchPage?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    // True while a load is in flight: the last answer stays on screen with the progress line,
    // so typing does not clear the list the user is still reading.
    var searching by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Key on what the load depends on: the server (its fingerprint, derived from the main
    // address) and the configuration generation, which bumps whenever settings are saved with a
    // key (a new main address, a new alternate or a new key). Not the whole row: choosing a list
    // or dropping the alternate rewrites it without changing what a read returns, and must not
    // reset the loaded pages.
    LaunchedEffect(query, service?.fingerprint, service?.generation, typed, submit) {
        val mine = ++generation
        loadingMore = false
        if (service == null || !shouldSendQuery(query, typed)) {
            ui = BookmarksUi.Idle
            bookmarks = emptyList()
            nextPage = null
            searching = false
            return@LaunchedEffect
        }
        val sentQuery = query.trim()
        searching = true
        // The newest bookmarks show at once; a search waits for a pause in typing.
        if (sentQuery.isNotEmpty()) delay(SEARCH_DEBOUNCE_MS)
        val answer = readBookmarks(SearchPage(sentQuery, null, mine, service))
        if (mine != generation) return@LaunchedEffect
        searching = false
        // Null: the service went away or its destination changed; the flow restarts this effect.
        if (answer == null) return@LaunchedEffect
        ui = BookmarksUi.Answer(answer)
        if (answer is KarakeepRead.Ok) {
            // Ids key the rows below; a repeat from the server must not crash the list.
            bookmarks = answer.value.bookmarks.distinctBy { it.id }
            nextPage = answer.value.nextCursor?.let { SearchPage(sentQuery, it, mine, service) }
        } else {
            bookmarks = emptyList()
            nextPage = null
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; typed = true },
            singleLine = true,
            enabled = service != null,
            placeholder = { Text(stringResource(R.string.karakeep_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { typed = true; submit++ }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        val answer = (ui as? BookmarksUi.Answer)?.read
        val notice = when {
            service == null -> stringResource(R.string.karakeep_search_not_configured)
            answer == null -> null
            answer is KarakeepRead.Ok && bookmarks.isEmpty() ->
                stringResource(if (query.isBlank()) R.string.karakeep_bookmarks_empty else R.string.karakeep_search_empty)
            else -> karakeepProblem(answer)
        }
        notice?.let {
            Text(it, modifier = Modifier.padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (service == null) TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.action_open_settings))
        }
    }

    val highlight = matchStyle()
    LazyColumn(Modifier.fillMaxWidth()) {
        items(bookmarks, key = { it.id }) { bookmark ->
            // Remembered per row: folding text for matching is not free on every recomposition.
            val title = remember(bookmark, query, highlight) { highlightMatches(bookmark.title ?: displayHost(bookmark.url), query, highlight) }
            // The host is marked too: a search for part of a site's name matches no title.
            val host = remember(bookmark, query, highlight) { highlightMatches(displayHost(bookmark.url), query, highlight) }
            ListItem(
                headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(host, maxLines = 1) },
                modifier = Modifier.clickable { onOpen(bookmark.url) },
                colors = sheetListItemColors(),
            )
        }
        nextPage?.let { page ->
            // One page at a time: the button waits, disabled, under a progress bar.
            if (loadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
            item {
                TextButton(
                    onClick = {
                        // Two taps in one frame both see the button still enabled.
                        if (loadingMore) return@TextButton
                        // The page belongs to an older search: a newer query owns the list now.
                        if (page.generation != generation) return@TextButton
                        loadingMore = true
                        scope.launch {
                            try {
                                val more = readBookmarks(page)
                                // A newer query owns the list and the loading state now.
                                if (page.generation != generation) return@launch
                                // Null: the service went away or its destination changed.
                                more ?: return@launch
                                // A failed page keeps the rows shown and says why above them.
                                ui = BookmarksUi.Answer(more)
                                if (more is KarakeepRead.Ok) {
                                    bookmarks = (bookmarks + more.value.bookmarks).distinctBy { it.id }
                                    nextPage = more.value.nextCursor?.let { SearchPage(page.query, it, page.generation, page.service) }
                                }
                            } finally {
                                // An old page must not release a newer query/destination's gate.
                                if (page.generation == generation) loadingMore = false
                            }
                        }
                    },
                    enabled = !loadingMore && page.generation == generation,
                    modifier = Modifier.padding(16.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.search_all_more)) }
            }
        }
    }
}

/** Why a Karakeep read has no value, in the user's words; null when it has one. */
@Composable
fun karakeepProblem(read: KarakeepRead<*>): String? = when (read) {
    is KarakeepRead.Ok -> null
    KarakeepRead.Unauthorized -> stringResource(R.string.karakeep_read_unauthorized)
    is KarakeepRead.Unreachable -> stringResource(R.string.karakeep_read_unreachable, read.category)
    is KarakeepRead.ServerError -> stringResource(R.string.karakeep_read_error, read.status)
    KarakeepRead.Malformed -> stringResource(R.string.karakeep_read_malformed)
}

private const val SEARCH_DEBOUNCE_MS = 400L
