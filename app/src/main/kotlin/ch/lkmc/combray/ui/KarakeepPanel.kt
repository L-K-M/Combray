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
import ch.lkmc.combray.core.integration.Integrations
import ch.lkmc.combray.core.integration.KarakeepBookmark
import ch.lkmc.combray.core.integration.KarakeepPage
import ch.lkmc.combray.core.integration.KarakeepRead
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface BookmarksUi {
    data object Idle : BookmarksUi

    data object Loading : BookmarksUi

    data class Answer(val read: KarakeepRead<KarakeepPage>) : BookmarksUi
}

/**
 * The Karakeep scope of Recent: the newest bookmarks, or Karakeep's search for the text typed
 * here. Only this field's text is sent. A newer query's answer always wins over an older one.
 */
@Composable
fun KarakeepPanel(integrations: Integrations, initialQuery: String, onOpen: (String) -> Unit) {
    val service by integrations.karakeepService.collectAsStateWithLifecycle(null)
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var ui by remember { mutableStateOf<BookmarksUi>(BookmarksUi.Idle) }
    var bookmarks by remember { mutableStateOf<List<KarakeepBookmark>>(emptyList()) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query, service) {
        val mine = ++generation
        ui = BookmarksUi.Idle
        bookmarks = emptyList()
        nextCursor = null
        if (service == null) return@LaunchedEffect

        // The newest bookmarks show at once; a search waits for a pause in typing.
        if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        ui = BookmarksUi.Loading
        val answer = integrations.karakeepBookmarks(query) ?: return@LaunchedEffect
        if (mine != generation) return@LaunchedEffect
        ui = BookmarksUi.Answer(answer)
        if (answer is KarakeepRead.Ok) {
            bookmarks = answer.value.bookmarks
            nextCursor = answer.value.nextCursor
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            enabled = service != null,
            placeholder = { Text(stringResource(R.string.karakeep_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
        if (ui == BookmarksUi.Loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
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
    }

    LazyColumn(Modifier.fillMaxWidth()) {
        items(bookmarks, key = { it.id }) { bookmark ->
            ListItem(
                headlineContent = { Text(bookmark.title ?: displayHost(bookmark.url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(displayHost(bookmark.url), maxLines = 1) },
                modifier = Modifier.clickable { onOpen(bookmark.url) },
            )
        }
        nextCursor?.let { cursor ->
            item {
                TextButton(
                    onClick = {
                        val mine = generation
                        scope.launch {
                            val more = integrations.karakeepBookmarks(query, cursor) as? KarakeepRead.Ok ?: return@launch
                            if (mine != generation) return@launch
                            bookmarks = bookmarks + more.value.bookmarks.filter { next -> bookmarks.none { it.id == next.id } }
                            nextCursor = more.value.nextCursor
                        }
                    },
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
