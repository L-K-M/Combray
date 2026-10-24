package ch.lkmc.combray.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.AddressSuggestions
import ch.lkmc.combray.core.browser.RecentItem
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.core.policy.RejectReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Pause after the last keystroke before the matches are ranked again. */
private const val SUGGESTION_DELAY_MS = 150L

/**
 * Address and search editor above the keyboard. [initialText] (the current address, or text
 * another app sent) starts selected for replacement; the button says what submitting will do,
 * and the other reading is one tap away. [target] is the tab the editor was opened for (null: a
 * new tab); typed text survives rotation. A new [restartKey] starts over with [initialText].
 * Once you change the [initialText], matching open tabs and recent pages from [recent] (the local
 * Recent projection, read once for the sheet's life) are listed above the field; [onNewTab]'s +
 * stays beside it, both anchored above the keyboard while only the matches scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressSheet(
    target: String?,
    initialText: String?,
    restartKey: Int,
    recent: () -> Flow<List<RecentItem>>,
    onDecision: (AddressDecision) -> Unit,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = initialText.orEmpty()
    var field by rememberSaveable(target, restartKey, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    // Text another app sent is the prefill too: suggestions wait until it is edited.
    val prefilled = rememberSaveable(target, restartKey) { initial }

    // Matches come from this device only: typing contacts no server. One subscription for the
    // sheet's life; ranking parses every candidate's host, so it waits for a pause in typing and
    // runs off the main thread. The prefilled text lists none: it is where you are.
    val items by remember { recent() }.collectAsStateWithLifecycle(emptyList())
    val query = field.text.trim()
    val edited = AddressSuggestions.shouldSuggest(field.text, prefilled)
    val matches by produceState(emptyList<RecentItem>(), items, query, edited) {
        // The last query's rows hide at once: while this one waits out its pause and ranks,
        // stale matches must not pass for its own.
        value = emptyList()
        if (!edited) return@produceState
        delay(SUGGESTION_DELAY_MS)
        value = withContext(Dispatchers.Default) {
            AddressSuggestions.rank(items.filter { it.matches(query) }, query, target)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AddressSheetContent(field, { field = it }, matches, target, onDecision, onSelectTab, onNewTab)
    }
}

/**
 * The editor's layout, apart from the sheet so a device test can check it without the sheet's
 * window. The matches scroll in whatever room the keyboard leaves; the field, its + and the
 * submission buttons stay anchored beside it.
 */
@Composable
internal fun AddressSheetContent(
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    matches: List<RecentItem>,
    target: String?,
    onDecision: (AddressDecision) -> Unit,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
) {
    val interpretation = AddressPolicy.interpret(field.text)
    val query = field.text.trim()
    val focus = remember { FocusRequester() }
    val inset = Modifier.padding(horizontal = 16.dp)

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        // Only the room left above the keyboard, scrolling within it: the field row below stays
        // next to the keyboard, where the thumb is, however many rows there are.
        if (matches.isNotEmpty()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Bottom) {
                Text(
                    stringResource(R.string.address_suggestions),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = inset.padding(vertical = 4.dp).semantics { heading() },
                )
                for (item in matches) {
                    key(item.key) {
                        SuggestionRow(item, query, opensHere = target != null) {
                            when (item) {
                                is RecentItem.OpenTab -> onSelectTab(item.tabId)
                                // Like the typed address: it opens in the tab being edited.
                                is RecentItem.Visit -> AddressSuggestions.navigation(item)?.let(onDecision)
                            }
                        }
                    }
                }
            }
        }
        // + as in the bottom bar, kept beside the field: a new tab is always one tap away.
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNewTab) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_new_tab))
            }
            OutlinedTextField(
                value = field,
                onValueChange = onFieldChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.address_placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = {
                    if (interpretation.primary !is AddressDecision.Rejected) onDecision(interpretation.primary)
                }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        val primary = interpretation.primary
        when {
            // Nothing typed yet is not a mistake: a new tab opens without an error.
            primary is AddressDecision.Rejected && primary.reason == RejectReason.EMPTY -> Unit
            primary is AddressDecision.Rejected -> Text(
                rejectionText(primary.reason),
                color = MaterialTheme.colorScheme.error,
                modifier = inset.padding(top = 12.dp),
            )
            else -> Button(
                onClick = { onDecision(primary) },
                modifier = inset.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp),
            ) {
                DecisionLabel(primary, describe(primary))
            }
        }
        interpretation.alternative?.let { alternative ->
            TextButton(onClick = { onDecision(alternative) }, modifier = inset.fillMaxWidth().heightIn(min = 48.dp)) {
                DecisionLabel(alternative, stringResource(R.string.address_instead, describe(alternative)))
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * A local match: an open tab to switch to, or a recent page to open where the typed address
 * would ([opensHere]: in the edited tab; otherwise in a new tab). The query's words are
 * marked in the title and host.
 */
@Composable
private fun SuggestionRow(item: RecentItem, query: String, opensHere: Boolean, onClick: () -> Unit) {
    val host = item.url?.let { displayHost(it) }
    val title = item.title?.takeIf { it.isNotBlank() } ?: host.orEmpty()
    val isTab = item is RecentItem.OpenTab
    val switchToTab = stringResource(R.string.address_switch_to_tab)
    val highlight = matchStyle()

    ListItem(
        headlineContent = {
            Text(
                remember(title, query, highlight) { highlightMatches(title, query, highlight) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            // "Switch to tab" leads the second line instead of trailing the row: a trailing label
            // would leave the title little room at large font sizes on a narrow phone. The host
            // is its own text, shortened from its start like the address chip's: a page's title
            // can name any site, and a long host must not lose the registrable domain.
            Row {
                if (isTab) {
                    Text(if (host != null) "$switchToTab ·" else switchToTab, maxLines = 1, modifier = Modifier.padding(end = 4.dp))
                }
                host?.let {
                    Text(
                        remember(it, query, highlight) { highlightMatches(it, query, highlight) },
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                }
            }
        },
        leadingContent = { Icon(if (isTab) TabIcon else HistoryIcon, contentDescription = null) },
        colors = if (isTab) {
            ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = MaterialTheme.colorScheme.primary)
        } else {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        },
        modifier = Modifier.clickable(
            onClickLabel = when {
                isTab -> switchToTab
                opensHere -> stringResource(R.string.address_open_here)
                else -> stringResource(R.string.address_open_in_new_tab)
            },
            onClick = onClick,
        ),
    )
}

/**
 * One line. An address shows its host in its own text, which shortens from its start like the
 * address chip's, so a long host keeps the registrable domain that names the site. The words and
 * scheme before it stay whole; the path after it gets what room is left. A search shortens in
 * the middle.
 */
@Composable
private fun DecisionLabel(decision: AddressDecision, text: String) {
    decisionIcon(decision)?.let { icon ->
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    }
    val url = when (decision) {
        is AddressDecision.Navigate -> decision.url
        is AddressDecision.External -> decision.url
        is AddressDecision.Search, is AddressDecision.Rejected -> null
    }
    val split = url?.let { splitAtHost(text, it) }
    if (split == null) {
        Text(text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        return
    }
    // The button's row measures these in order: the host gets the room the words leave, and
    // the path whatever is left after it.
    Text(split.lead, maxLines = 1)
    Text(split.host, maxLines = 1, overflow = TextOverflow.StartEllipsis)
    Text(split.tail, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A button's label around an address: the words and scheme before its host, the host, the rest. */
internal data class HostSplit(val lead: String, val host: String, val tail: String)

/**
 * [text] split around the host (with any port) of [url], which it contains. Null when [url] has
 * no authority after its scheme (mailto:, tel:) or [text] does not contain it.
 */
internal fun splitAtHost(text: String, url: String): HostSplit? {
    val start = AUTHORITY_START.find(url)?.value?.length ?: return null
    val end = url.indexOfAny(HOST_ENDS, start).takeIf { it >= 0 } ?: url.length
    if (end == start) return null
    val at = text.lastIndexOf(url).takeIf { it >= 0 } ?: return null
    return HostSplit(text.substring(0, at + start), url.substring(start, end), text.substring(at + end))
}

/** Only "scheme://" at the very start introduces an authority; one further in is path or query. */
private val AUTHORITY_START = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

/** Where a host ends: its path, query or fragment. The engine reads "\" as "/" in a web address. */
private val HOST_ENDS = charArrayOf('/', '?', '#', '\\')

private fun decisionIcon(decision: AddressDecision): ImageVector? = when (decision) {
    is AddressDecision.Navigate -> Icons.AutoMirrored.Filled.ArrowForward
    is AddressDecision.Search -> Icons.Filled.Search
    is AddressDecision.External -> Icons.AutoMirrored.Filled.ExitToApp
    is AddressDecision.Rejected -> null
}

@Composable
private fun describe(decision: AddressDecision): String = when (decision) {
    is AddressDecision.Navigate -> stringResource(R.string.address_go, decision.url)
    is AddressDecision.Search -> stringResource(R.string.address_search, decision.query)
    is AddressDecision.External -> stringResource(R.string.address_external, decision.url)
    is AddressDecision.Rejected -> rejectionText(decision.reason)
}

@Composable
private fun rejectionText(reason: RejectReason): String = stringResource(
    when (reason) {
        RejectReason.EMPTY -> R.string.address_reject_empty
        RejectReason.UNSUPPORTED_SCHEME -> R.string.address_reject_scheme
        RejectReason.EMBEDDED_CREDENTIALS -> R.string.address_reject_credentials
        RejectReason.MALFORMED_ADDRESS -> R.string.address_reject_malformed
    },
)

/** Material's "tab" icon (Apache License 2.0, like the icon library), absent from the core set. */
private val TabIcon: ImageVector = ImageVector.Builder("Tab", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes("M21 3H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H3V5h10v4h8v10z"),
        fill = SolidColor(Color.Black),
    )
    .build()

/** Material's "history" icon (Apache License 2.0, like the icon library), absent from the core set. */
private val HistoryIcon: ImageVector = ImageVector.Builder("History", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes(
            "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7" +
                "c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9z" +
                "m-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z",
        ),
        fill = SolidColor(Color.Black),
    )
    .build()
