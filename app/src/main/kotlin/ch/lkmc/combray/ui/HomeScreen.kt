package ch.lkmc.combray.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.BrowserSettings
import ch.lkmc.combray.core.browser.HomeLink
import ch.lkmc.combray.core.browser.HomeLinksState
import ch.lkmc.combray.core.browser.HomeSectionVisibility
import ch.lkmc.combray.core.browser.RecentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

private val HOME_MARGIN = 24.dp
private val HOME_MAX_WIDTH = 600.dp
private val HOME_ICON_SIZE = 32.dp
private val HOME_ACTION_TARGET = 48.dp
private val MIN_LINK_WIDTH = 96.dp
private const val LINK_COLUMNS = 3
private const val HOME_CLOCK_TICK_MS = 60_000L

/** Subscribed only while Home is shown. Neither projection fetches a page or a service. */
@Composable
internal fun HomeScreen(
    links: Flow<HomeLinksState>,
    continuation: Flow<RecentItem.OpenTab?>,
    settings: BrowserSettings,
    onOpenUrl: (String) -> Unit,
    onContinue: (String) -> Unit,
    onEdit: (HomeLink?) -> Unit,
    onAdd: () -> Unit,
    onHide: (String) -> Unit,
    onUnpin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by links.collectAsStateWithLifecycle<HomeLinksState?>(null, context = Dispatchers.Main.immediate)
    // Keep order outside the lifecycle-scoped collector: stopping it must not reset this visit.
    var automaticOrder by rememberSaveable(links) { mutableStateOf(emptyList<String>()) }
    val current = state
    val automatic = current?.links.orEmpty().filter { it.pinId == null }.sortedBy { link ->
        automaticOrder.indexOf(link.id).takeIf { it >= 0 } ?: Int.MAX_VALUE
    }
    val displayed = current?.links.orEmpty().filter { it.pinId != null } + automatic
    LaunchedEffect(current?.links) {
        // Null is still loading, not an empty list that should erase a restored order.
        if (current != null) automaticOrder = automatic.map { it.id }
    }
    val continued by continuation.collectAsStateWithLifecycle(null, context = Dispatchers.Main.immediate)
    val showContinue by settings.homeContinue.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val showFrequent by settings.homeFrequentSites.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    var options by remember { mutableStateOf<HomeLink?>(null) }

    if (current == null) {
        // The dock stays usable; an empty-looking body must not move its targets on first load.
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { }
        return
    }

    HomeContent(
        links = displayed.filter { showFrequent == HomeSectionVisibility.SHOWN || it.pinId != null },
        continued = continued.takeIf { showContinue == HomeSectionVisibility.SHOWN },
        onOpenUrl = onOpenUrl,
        onContinue = onContinue,
        onEdit = { onEdit(null) },
        onAdd = onAdd,
        onOptions = { options = it },
        modifier = modifier,
    )

    // A removed or forgotten link cannot stay actionable in an already-open menu.
    val selected = options?.let { old -> current.links.firstOrNull { it.id == old.id && it.url == old.url } }
    if (selected != null) {
        HomeLinkOptions(
            link = selected,
            onEdit = { options = null; onEdit(selected) },
            onRemove = {
                options = null
                val pin = selected.pinId
                if (pin == null) onHide(selected.site) else onUnpin(pin)
            },
            onDismiss = { options = null },
        )
    }
}

/** The native start surface, independent of the engine and repositories for previews and checks. */
@Composable
internal fun HomeContent(
    links: List<HomeLink>,
    continued: RecentItem.OpenTab?,
    onOpenUrl: (String) -> Unit,
    onContinue: (String) -> Unit,
    onEdit: () -> Unit,
    onAdd: () -> Unit,
    onOptions: (HomeLink) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val height = maxHeight
            Column(
                Modifier.widthIn(max = HOME_MAX_WIDTH).fillMaxWidth().verticalScroll(rememberScrollState())
                    .heightIn(min = height).padding(HOME_MARGIN),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Bottom),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(HOME_ICON_SIZE).background(colorResource(R.color.ic_launcher_background))) {
                            Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
                        }
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        stringResource(R.string.home_heading),
                        fontFamily = FontFamily.Serif,
                        fontSize = 32.sp,
                        lineHeight = 40.sp,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                continued?.let { ContinueCard(it, onContinue) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.home_links_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = HOME_ACTION_TARGET)) { Text(stringResource(R.string.home_edit)) }
                    }
                    if (links.isEmpty()) {
                        TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = HOME_ACTION_TARGET)) { Text(stringResource(R.string.home_add_link)) }
                    } else {
                        QuickLinks(links, onOpenUrl, onOptions)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(tab: RecentItem.OpenTab, onContinue: (String) -> Unit) {
    val url = tab.url ?: return
    val title = tab.title?.takeIf { it.isNotBlank() } ?: displayHost(url)
    val label = stringResource(R.string.home_continue_tab, title)
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(HOME_CLOCK_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.home_continue),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(
            onClick = { onContinue(tab.tabId) },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label; role = Role.Button },
        ) {
            Row(Modifier.heightIn(min = 64.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOf(displayHost(url), stringResource(R.string.recent_open_marker), relativeTime(tab.time, now)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Reflows instead of shrinking labels or sharing touch targets at large text sizes. */
@Composable
internal fun QuickLinks(links: List<HomeLink>, onOpenUrl: (String) -> Unit, onOptions: (HomeLink) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val columns = (maxWidth.value / (MIN_LINK_WIDTH.value * scale)).toInt().coerceIn(1, LINK_COLUMNS)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            links.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { link -> HomeTile(link, onOpenUrl, onOptions, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun HomeTile(link: HomeLink, onOpenUrl: (String) -> Unit, onOptions: (HomeLink) -> Unit, modifier: Modifier) {
    val title = link.title?.takeIf { link.pinId != null && it.isNotBlank() } ?: displayHost(link.url)
    val open = stringResource(R.string.home_open_site, title)
    val options = stringResource(R.string.home_link_options, title)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 88.dp)
                .combinedClickable(onClick = { onOpenUrl(link.url) }, onLongClick = { onOptions(link) }, onLongClickLabel = options, role = Role.Button)
                .semantics(mergeDescendants = true) { contentDescription = open }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(32.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(monogram(link.url), color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.titleMedium)
            }
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HomeLinkOptions(link: HomeLink, onEdit: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(link.title?.takeIf { link.pinId != null } ?: displayHost(link.url)) },
        text = {
            Column {
                TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = HOME_ACTION_TARGET)) {
                    Text(stringResource(if (link.pinId == null) R.string.home_pin else R.string.home_edit_link))
                }
                TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().heightIn(min = HOME_ACTION_TARGET)) {
                    Text(stringResource(if (link.pinId == null) R.string.home_hide_site else R.string.home_unpin))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Preview(name = "Populated", widthDp = 360, heightDp = 640)
@Preview(name = "Dark", widthDp = 360, heightDp = 640, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow large text", widthDp = 320, heightDp = 640, fontScale = 2f)
@Preview(name = "Landscape", widthDp = 640, heightDp = 320)
@Preview(name = "RTL", widthDp = 360, heightDp = 640, locale = "ar")
@Composable
private fun HomePreview() {
    CombrayTheme {
        HomeContent(
            links = listOf("library.example", "garden.example", "notes.example", "maps.example", "books.example", "archive.example").map {
                HomeLink(id = it, site = it, url = "https://$it", title = null, pinId = null)
            },
            continued = RecentItem.OpenTab("preview", "https://example.org/garden", "A garden through the seasons", System.currentTimeMillis(), false, createdAt = 0),
            onOpenUrl = {}, onContinue = {}, onEdit = {}, onAdd = {}, onOptions = {},
        )
    }
}

@Preview(name = "First run", widthDp = 360, heightDp = 640)
@Composable
private fun EmptyHomePreview() {
    CombrayTheme { HomeContent(emptyList(), null, {}, {}, {}, {}, {}) }
}
