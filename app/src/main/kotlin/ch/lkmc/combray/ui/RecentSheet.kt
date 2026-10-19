package ch.lkmc.combray.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.BrowserCoordinator
import ch.lkmc.combray.core.browser.RecentItem
import ch.lkmc.combray.core.integration.HisterIntegration
import ch.lkmc.combray.core.policy.HostNames
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private enum class RecentFilter { ALL, OPEN }

/**
 * Open tabs and recently visited pages in one most-recently-used list (R5). Filtering stays on
 * the device; nothing typed here leaves it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentSheet(
    browser: BrowserCoordinator,
    hister: HisterIntegration,
    onOpenUrl: (String) -> Unit,
    onNewTab: () -> Unit,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchAll by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(RecentFilter.ALL) }
    val items by remember(query) { browser.recent(query) }.collectAsStateWithLifecycle(emptyList())
    var forgetting by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = if (filter == RecentFilter.OPEN) items.filterIsInstance<RecentItem.OpenTab>() else items
    val now = System.currentTimeMillis()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (searchAll) {
            // Tall enough that a snackbar sits below the search field, not over it.
            Box(Modifier.heightIn(min = 240.dp)) {
                Column { HisterSearchPanel(hister, initialQuery = query, onOpen = onOpenUrl, onBack = { searchAll = false }) }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
            return@ModalBottomSheet
        }
        // The sheet is its own window: the Undo for a tab closed here must be shown inside it.
        Box {
            Column {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.recent_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        TextButton(onClick = onNewTab, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(stringResource(R.string.action_new_tab), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.recent_filter_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        FilterChip(
                            selected = filter == RecentFilter.ALL,
                            onClick = { filter = RecentFilter.ALL },
                            label = { Text(stringResource(R.string.recent_filter_all)) },
                        )
                        FilterChip(
                            selected = filter == RecentFilter.OPEN,
                            onClick = { filter = RecentFilter.OPEN },
                            label = { Text(stringResource(R.string.recent_filter_open)) },
                        )
                    }
                    // A separate, labeled scope: local filtering above never contacts Hister.
                    TextButton(onClick = { searchAll = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.search_all_history))
                    }
                }

                if (shown.isEmpty()) {
                    Text(
                        stringResource(if (query.isBlank()) R.string.recent_empty else R.string.recent_no_matches),
                        modifier = Modifier.padding(24.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    var lastDay: Long? = null
                    for (item in shown) {
                        val day = startOfDay(item.time)
                        if (day != lastDay) {
                            lastDay = day
                            item(key = "day:$day") { DayHeader(day, now) }
                        }
                        item(key = item.key) {
                            RecentRow(
                                item = item,
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
                                onForgetSite = { forgetting = HostNames.of(item.url.orEmpty()) },
                            )
                        }
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

@Composable
private fun RecentRow(
    item: RecentItem,
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

    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val details = when (item) {
                is RecentItem.OpenTab -> listOfNotNull(
                    stringResource(if (item.active) R.string.recent_active_marker else R.string.recent_open_marker),
                    host,
                )
                is RecentItem.Visit -> listOfNotNull(host, relativeTime(item.time, now))
            }
            Text(details.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            when (item) {
                is RecentItem.OpenTab -> IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.recent_close_tab, title))
                }
                is RecentItem.Visit -> VisitMenu(title, site, onRemove, onForgetSite)
            }
        },
        colors = if (item is RecentItem.OpenTab) {
            ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.primary)
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
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

@Composable
private fun DayHeader(day: Long, now: Long) {
    val today = startOfDay(now)
    val label = when (day) {
        today -> stringResource(R.string.recent_today)
        startOfDay(today - 1) -> stringResource(R.string.recent_yesterday)
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(day))
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
}

private fun startOfDay(time: Long): Long = Calendar.getInstance().run {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}
