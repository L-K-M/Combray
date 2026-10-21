package ch.lkmc.combray.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
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
import ch.lkmc.combray.core.integration.HisterHit
import ch.lkmc.combray.core.integration.HisterIntegration
import ch.lkmc.combray.core.integration.HisterSearch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface SearchUi {
    data object Idle : SearchUi

    data object Loading : SearchUi

    data class Answer(val result: HisterSearch) : SearchUi
}

/**
 * The explicit Hister scope of Recent. Only text typed here is sent to Hister; typing in local
 * Recent never is. A newer query's answer always wins over a slower older one.
 */
@Composable
fun HisterSearchPanel(hister: HisterIntegration, initialQuery: String, onOpen: (String) -> Unit) {
    val service by hister.service.collectAsStateWithLifecycle(null)
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var ui by remember { mutableStateOf<SearchUi>(SearchUi.Idle) }
    var hits by remember { mutableStateOf<List<HisterHit>>(emptyList()) }
    var nextPage by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query, service) {
        val mine = ++generation
        // The last answer belongs to the last query: no stale count while the next one waits.
        ui = SearchUi.Idle
        hits = emptyList()
        nextPage = null
        if (service == null || query.isBlank()) {
            ui = SearchUi.Idle
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        ui = SearchUi.Loading
        val answer = hister.search(query.trim()) ?: return@LaunchedEffect
        if (mine != generation) return@LaunchedEffect
        ui = SearchUi.Answer(answer)
        if (answer is HisterSearch.Results) {
            hits = answer.hits
            nextPage = answer.nextPage
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            enabled = service != null,
            placeholder = { Text(stringResource(R.string.search_all_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
        if (ui == SearchUi.Loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        val notice = when {
            service == null -> stringResource(R.string.search_all_not_configured)
            else -> when (val state = ui) {
                is SearchUi.Answer -> when (val result = state.result) {
                    is HisterSearch.Results -> if (result.hits.isEmpty()) stringResource(R.string.search_all_empty) else null
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
    }

    val highlight = matchStyle()
    LazyColumn(Modifier.fillMaxWidth()) {
        items(hits, key = { it.url }) { hit ->
            ListItem(
                headlineContent = {
                    Text(highlightMatches(hit.title ?: displayHost(hit.url), query, highlight), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Column {
                        // Plain text only: stored excerpts are never rendered as HTML.
                        hit.excerpt?.let { Text(highlightMatches(it, query, highlight), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Text(
                            listOfNotNull(displayHost(hit.url), if (hit.fromHistory) stringResource(R.string.search_all_from_history) else null).joinToString(" · "),
                            maxLines = 1,
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onOpen(hit.url) },
            )
        }
        nextPage?.let { page ->
            item {
                TextButton(
                    onClick = {
                        val mine = generation
                        scope.launch {
                            val more = hister.search(query.trim(), page) as? HisterSearch.Results ?: return@launch
                            if (mine != generation) return@launch
                            hits = hits + more.hits.filter { hit -> hits.none { it.url == hit.url } }
                            nextPage = more.nextPage
                        }
                    },
                    modifier = Modifier.padding(16.dp).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.search_all_more)) }
            }
        }
    }
}

private const val SEARCH_DEBOUNCE_MS = 400L
