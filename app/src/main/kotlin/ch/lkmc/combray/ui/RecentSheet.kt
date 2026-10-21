package ch.lkmc.combray.ui

import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.BrowserCoordinator
import ch.lkmc.combray.core.browser.RecentItem
import ch.lkmc.combray.core.integration.Integrations
import ch.lkmc.combray.core.policy.HostNames
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

/** What Recent shows. Only [HISTER] and [KARAKEEP] contact a server. */
private enum class RecentScope(val label: Int) {
    OPEN(R.string.recent_filter_open),
    ALL(R.string.recent_filter_all),
    HISTER(R.string.recent_scope_hister),
    KARAKEEP(R.string.recent_scope_karakeep),
}

/** What an empty local Recent list says, and whether All has rows to offer instead. */
internal enum class RecentEmpty(val message: Int, val offersAll: Boolean) {
    NOTHING(R.string.recent_empty, offersAll = false),
    NO_MATCHES(R.string.recent_no_matches, offersAll = false),
    NO_OPEN_TABS(R.string.recent_open_empty, offersAll = true),
    NO_OPEN_TAB_MATCHES(R.string.recent_open_no_matches, offersAll = true),
}

/**
 * Null while [shown] has rows. [matching] is every row [query] matches, in every scope (what
 * Recent's projection returned for it); [shown] is the part of it the scope shows.
 */
internal fun recentEmpty(query: String, matching: List<RecentItem>, shown: List<RecentItem>): RecentEmpty? = when {
    shown.isNotEmpty() -> null
    matching.isEmpty() -> if (query.isBlank()) RecentEmpty.NOTHING else RecentEmpty.NO_MATCHES
    // Rows exist but none is shown: only Open tabs hides rows, so they are pages under All.
    query.isBlank() -> RecentEmpty.NO_OPEN_TABS
    else -> RecentEmpty.NO_OPEN_TAB_MATCHES
}

/**
 * Open tabs and recently visited pages in one most-recently-used list (R5), opening on the
 * tabs. Filtering them stays on the device; Hister and Karakeep are separate, labeled scopes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentSheet(
    browser: BrowserCoordinator,
    integrations: Integrations,
    onOpenUrl: (String) -> Unit,
    onNewTab: () -> Unit,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(RecentScope.OPEN) }
    // Null until the first answer: an empty list before it would flash an empty-state message.
    val rows by remember(query) { browser.recent(query).map { LocalRows(query, it) } }
        .collectAsStateWithLifecycle(null)
    var forgetting by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = remember(rows, scope) {
        val items = rows?.items.orEmpty()
        if (scope == RecentScope.OPEN) items.filterIsInstance<RecentItem.OpenTab>() else items
    }
    // Relative times and day headers stay right while the sheet stays open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = System.currentTimeMillis()
        }
    }

    // A new filter or scope shows its best matches first, once its rows are there.
    val listState = rememberLazyListState()
    LaunchedEffect(rows?.query, scope) { listState.scrollToItem(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // The sheet is its own window: the Undo for a tab closed here must be shown inside it.
        // Tall enough that a snackbar sits below a search field, not over it.
        Box(Modifier.heightIn(min = 240.dp)) {
            Column {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.recent_title),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                stringResource(R.string.recent_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onNewTab, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(stringResource(R.string.action_new_tab), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp).selectableGroup(),
                    ) {
                        RecentScope.entries.forEach { option ->
                            FilterChip(
                                selected = scope == option,
                                onClick = { scope = option },
                                label = { Text(stringResource(option.label)) },
                                // One scope at a time: announced as tabs, not as checkboxes.
                                modifier = Modifier.semantics { role = Role.Tab },
                            )
                        }
                    }
                    if (scope == RecentScope.OPEN || scope == RecentScope.ALL) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.recent_filter_hint)) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                    }
                }

                // The server scopes start from the local filter's text; only their own field is sent.
                when (scope) {
                    RecentScope.HISTER -> HisterSearchPanel(integrations.hister, initialQuery = query, onOpen = onOpenUrl)
                    RecentScope.KARAKEEP -> KarakeepPanel(integrations, initialQuery = query, onOpen = onOpenUrl)
                    RecentScope.OPEN, RecentScope.ALL -> rows?.let { answered ->
                        LocalRecent(
                            browser = browser,
                            shown = shown,
                            empty = recentEmpty(answered.query, answered.items, shown),
                            query = answered.query,
                            now = now,
                            listState = listState,
                            onShowAll = { scope = RecentScope.ALL },
                            onDismiss = onDismiss,
                            onForgetSite = { forgetting = it },
                        )
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }

    forgetting?.let { host ->
        ForgetSiteDialog(
            host = host,
            onConfirm = { includeSubdomains ->
                browser.forgetSite(host, includeSubdomains)
                forgetting = null
            },
            onDismiss = { forgetting = null },
        )
    }
}

/** Tabs and history on this device, grouped by day. [query] is the filter [shown] answers. */
@Composable
private fun LocalRecent(
    browser: BrowserCoordinator,
    shown: List<RecentItem>,
    empty: RecentEmpty?,
    query: String,
    now: Long,
    listState: LazyListState,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit,
    onForgetSite: (String) -> Unit,
) {
    if (empty != null) {
        EmptyRecent(empty, onShowAll)
        return
    }

    val days = remember(shown) { shown.map { calendarDay(it.time) } }
    val today = calendarDay(now)
    LazyColumn(Modifier.fillMaxWidth(), state = listState) {
        var lastDay: Long? = null
        shown.forEachIndexed { index, item ->
            val day = days[index]
            if (day != lastDay) {
                lastDay = day
                item(key = "day:$day") { DayHeader(day, today) }
            }
            item(key = item.key) {
                RecentRow(
                    item = item,
                    query = query,
                    now = now,
                    onOpen = {
                        when (item) {
                            is RecentItem.OpenTab -> browser.selectTab(item.tabId)
                            is RecentItem.Visit -> browser.openVisit(item.visitId)
                        }
                        onDismiss()
                    },
                    onClose = { browser.closeTab((item as RecentItem.OpenTab).tabId) },
                    onRemove = { browser.forgetVisit((item as RecentItem.Visit).visitId) },
                    onForgetSite = { HostNames.of(item.url.orEmpty())?.let(onForgetSite) },
                )
            }
        }
    }
}

/** Why the list is empty and, when All has rows, a way there that keeps the filter. */
@Composable
private fun EmptyRecent(empty: RecentEmpty, onShowAll: () -> Unit) {
    Column(Modifier.padding(24.dp)) {
        Text(stringResource(empty.message))
        if (empty.offersAll) {
            TextButton(onClick = onShowAll, modifier = Modifier.heightIn(min = 48.dp)) {
                // With a filter, All shows the matching pages, not every page.
                Text(stringResource(if (empty == RecentEmpty.NO_OPEN_TAB_MATCHES) R.string.recent_show_matching else R.string.recent_show_all))
            }
        }
    }
}

@Composable
private fun RecentRow(
    item: RecentItem,
    query: String,
    now: Long,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onRemove: () -> Unit,
    onForgetSite: () -> Unit,
) {
    val title = item.title?.takeIf { it.isNotBlank() }
        ?: item.url?.let { displayHost(it) }
        ?: stringResource(R.string.recent_new_tab_row)
    val host = item.url?.let { displayHost(it) }
    // The site actions name the exact host they act on ("www.example.org", not "example.org").
    val site = item.url?.let(HostNames::of)?.let(HostNames::forDisplay)
    val colors = MaterialTheme.colorScheme
    val isCurrentTab = item is RecentItem.OpenTab && item.active
    val marker = when {
        item !is RecentItem.OpenTab -> null
        item.active -> stringResource(R.string.recent_active_marker)
        else -> stringResource(R.string.recent_open_marker)
    }
    // Open tabs show their time too, so two tabs on one page can be told apart.
    val details = buildAnnotatedString {
        marker?.let {
            withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium)) { append(it) }
            append(DETAIL_SEPARATOR)
        }
        append(listOfNotNull(host, relativeTime(item.time, now)).joinToString(DETAIL_SEPARATOR))
    }

    ListItem(
        leadingContent = { SiteTile(monogram(item.url)) },
        headlineContent = {
            val style = matchStyle()
            Text(
                remember(title, query, style) { highlightMatches(title, query, style) },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            when (item) {
                is RecentItem.OpenTab -> IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.recent_close_tab, title))
                }
                is RecentItem.Visit -> VisitMenu(title, site, onRemove, onForgetSite)
            }
        },
        // Rows take the sheet's colour; only the current tab stands out.
        colors = ListItemDefaults.colors(
            containerColor = if (isCurrentTab) colors.secondaryContainer.copy(alpha = CURRENT_TAB_ALPHA) else Color.Transparent,
            headlineColor = colors.onSurface,
        ),
        modifier = Modifier.clickable(
            onClickLabel = stringResource(if (item is RecentItem.OpenTab) R.string.recent_switch_to_tab else R.string.recent_open_in_new_tab),
            onClick = onOpen,
        ),
    )
}

/** The site's initial on a small tile, so a list of similar titles scans faster. Decorative. */
@Composable
private fun SiteTile(letter: String) {
    // Fixed like the tile around it: the title beside it grows with the font scale instead.
    val letterSize = with(LocalDensity.current) { TILE_LETTER_SIZE.toSp() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(TILE_SIZE)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(TILE_CORNER))
            .clearAndSetSemantics {},
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = letterSize, lineHeight = letterSize),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun VisitMenu(title: String, host: String?, onRemove: () -> Unit, onForgetSite: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.recent_row_actions, title))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.recent_remove)) },
                onClick = {
                    expanded = false
                    onRemove()
                },
            )
            if (host != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.recent_forget_site, host)) },
                    onClick = {
                        expanded = false
                        onForgetSite()
                    },
                )
            }
        }
    }
}

@Composable
private fun ForgetSiteDialog(host: String, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var includeSubdomains by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recent_forget_site_title, HostNames.forDisplay(host))) },
        text = {
            Column {
                Text(stringResource(R.string.recent_forget_site_body))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(value = includeSubdomains, role = Role.Checkbox) { includeSubdomains = it }.heightIn(min = 48.dp),
                ) {
                    Checkbox(checked = includeSubdomains, onCheckedChange = null)
                    Text(stringResource(R.string.recent_forget_subdomains))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(includeSubdomains) }) { Text(stringResource(R.string.recent_forget_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** [day] and [today] are [calendarDay]s. */
@Composable
private fun DayHeader(day: Long, today: Long) {
    val locale = LocalConfiguration.current.locales[0]
    val name = dayName(day, today)
    val label = when (name) {
        DayName.TODAY -> stringResource(R.string.recent_today)
        DayName.YESTERDAY -> stringResource(R.string.recent_yesterday)
        DayName.WEEKDAY, DayName.DATE -> remember(day, name, locale) {
            val start = Date(LocalDate.ofEpochDay(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            val format = if (name == DayName.WEEKDAY) {
                SimpleDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, WEEKDAY_SKELETON), locale)
            } else {
                DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
            }
            format.format(start)
        }
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** Local Recent rows and the filter text they answer. */
private data class LocalRows(val query: String, val items: List<RecentItem>)

private const val CLOCK_TICK_MS = 30_000L
private const val DETAIL_SEPARATOR = " · "
private const val CURRENT_TAB_ALPHA = 0.5f
private const val WEEKDAY_SKELETON = "EEEE"
private val TILE_SIZE = 32.dp
private val TILE_CORNER = 9.dp
private val TILE_LETTER_SIZE = 14.dp
