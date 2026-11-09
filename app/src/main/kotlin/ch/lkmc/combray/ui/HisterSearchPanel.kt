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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
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
import ch.lkmc.combray.core.integration.HisterHit
import ch.lkmc.combray.core.integration.HisterIntegration
import ch.lkmc.combray.core.integration.HisterSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The rows shown so far, with [page] added: one row per address, in arrival order. */
internal fun mergeHits(hits: List<HisterHit>, page: List<HisterHit>): List<HisterHit> =
    (hits + page).distinctBy { it.url }

private sealed interface SearchUi {
    data object Idle : SearchUi

    data class Answer(val result: HisterSearch) : SearchUi
}

/**
 * The explicit Hister scope of Recent. Only text typed here is sent to Hister; typing in local
 * Recent never is. A newer query's answer always wins over a slower older one, and the last
 * answer stays on screen while the next search is in flight.
 */
@Composable
fun HisterSearchPanel(hister: HisterIntegration, initialQuery: String, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val service by hister.service.collectAsStateWithLifecycle(null, context = Dispatchers.Main.immediate)
    HisterSearchPanelContent(service, initialQuery, { page -> hister.search(page.query, page.cursor, page.service) }, onOpen, onSettings)
}

/**
 * The panel's state and UI, separated from service observation so delayed-read checks can drive
 * it directly. [search] receives the page's bound query, cursor and destination.
 */
@Composable
internal fun HisterSearchPanelContent(
    service: ServiceEntity?,
    initialQuery: String,
    search: suspend (SearchPage) -> HisterSearch?,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    // A query seeded from Recent's local filter is not the user's text: it is sent only after
    // the user types in this field or submits it. The submit nonce re-runs an unchanged query.
    var typed by rememberSaveable { mutableStateOf(false) }
    var submit by rememberSaveable { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf<SearchUi>(SearchUi.Idle) }
    var hits by remember { mutableStateOf<List<HisterHit>>(emptyList()) }
    var nextPage by remember { mutableStateOf<SearchPage?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    // True while a search is in flight: the last answer stays on screen with the progress line,
    // so typing does not clear the list the user is still reading.
    var searching by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Capture policy and route bookkeeping do not change the remote search or its cursor.
    LaunchedEffect(query, service?.fingerprint, service?.generation, typed, submit) {
        val mine = ++generation
        loadingMore = false
        // Hister has no default list: a blank query stays idle, as before. A seeded non-blank
        // query is sent only after the user types or submits it.
        if (service == null || query.isBlank() || !typed) {
            ui = SearchUi.Idle
            hits = emptyList()
            nextPage = null
            searching = false
            return@LaunchedEffect
        }
        val sentQuery = query.trim()
        searching = true
        delay(SEARCH_DEBOUNCE_MS)
        val answer = search(SearchPage(sentQuery, null, mine, service))
        if (mine != generation) return@LaunchedEffect
        searching = false
        // Null: the service went away or its destination changed; the flow restarts this effect.
        if (answer == null) return@LaunchedEffect
        ui = SearchUi.Answer(answer)
        if (answer is HisterSearch.Results) {
            hits = mergeHits(emptyList(), answer.hits)
            nextPage = answer.nextPage?.let { SearchPage(sentQuery, it, mine, service) }
        } else {
            hits = emptyList()
            nextPage = null
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; typed = true },
            singleLine = true,
            enabled = service != null,
            placeholder = { Text(stringResource(R.string.search_all_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { typed = true; submit++ }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        val notice = when {
            service == null -> stringResource(R.string.search_all_not_configured)
            else -> when (val state = ui) {
                is SearchUi.Answer -> when (val result = state.result) {
                    // The rows shown, not the last page: a later page may add none.
                    is HisterSearch.Results -> if (hits.isEmpty()) stringResource(R.string.search_all_empty) else null
                    HisterSearch.Unauthorized -> stringResource(R.string.search_all_unauthorized)
                    is HisterSearch.Unreachable -> stringResource(R.string.search_all_unreachable, result.category)
                    is HisterSearch.ServerError -> stringResource(R.string.search_all_error, result.status)
                    HisterSearch.Malformed -> stringResource(R.string.search_all_malformed)
                }
                else -> null
            }
        }
        val count = (ui as? SearchUi.Answer)?.result as? HisterSearch.Results
        val status = notice ?: count?.let { pluralStringResource(R.plurals.search_all_count, hits.size, hits.size) }
        status?.let {
            Text(
                it,
                style = if (notice == null) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = if (notice == null) 4.dp else 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (service == null) TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.action_open_settings))
        }
    }

    val highlight = matchStyle()
    LazyColumn(Modifier.fillMaxWidth()) {
        items(hits, key = { it.url }) { hit ->
            // Remembered per row: folding text for matching is not free on every recomposition.
            val title = remember(hit, query, highlight) { highlightMatches(hit.title ?: displayHost(hit.url), query, highlight) }
            val excerpt = remember(hit, query, highlight) { hit.excerpt?.let { highlightMatches(it, query, highlight) } }
            ListItem(
                headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        // Plain text only: stored excerpts are never rendered as HTML.
                        excerpt?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Text(
                            listOfNotNull(displayHost(hit.url), if (hit.fromHistory) stringResource(R.string.search_all_from_history) else null).joinToString(" · "),
                            maxLines = 1,
                        )
                    }
                },
                modifier = Modifier.clickable { onOpen(hit.url) },
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
                                val more = search(page)
                                // A newer query owns the list and the loading state now.
                                if (page.generation != generation) return@launch
                                // Null: the service went away or its destination changed.
                                more ?: return@launch
                                // A failed page keeps the rows shown and says why above them.
                                ui = SearchUi.Answer(more)
                                if (more is HisterSearch.Results) {
                                    hits = mergeHits(hits, more.hits)
                                    nextPage = more.nextPage?.let { SearchPage(page.query, it, page.generation, page.service) }
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

private const val SEARCH_DEBOUNCE_MS = 400L
