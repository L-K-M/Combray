package ch.lkmc.combray.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.AddressSuggestions
import ch.lkmc.combray.core.browser.RecentItem
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressInterpretation
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.core.policy.RejectReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Pause after the last keystroke before the matches are ranked again. */
private const val SUGGESTION_DELAY_MS = 150L

/** Home keeps its field when editing ends; a page's editor exists only while you edit. */
enum class AddressEditorMode { IDLE, EDITING }

/** What the editor starts with, which decides whether it offers to act on that text at once. */
enum class AddressPrefill {
    /** The page's own address (on a Kagi results page, its search words): where you already are. */
    CURRENT_PAGE,

    /** Text to act on, such as a search another app shared, or nothing at all. */
    DRAFT,
}

/**
 * The address and search editor in the bottom bar, above the keyboard: Home's field, and a
 * page's address while you edit it (the bar itself becomes the editor; there is no sheet).
 * [initialText] starts selected for replacement. While editing, one panel above the field lists
 * what Go will do, the other reading of the text, and up to five matching open tabs and recent
 * pages from [recent] (the local Recent projection, read once for the editor's life). The rows
 * nearest the field, and the thumb, are the likeliest. [target] is the tab the editor was opened
 * for (null: a new tab); typed text survives rotation, and a new [restartKey] starts over with
 * [initialText]. [prefill] says whether that text is the page you are on, which offers nothing
 * until you change it.
 */
@Composable
internal fun AddressEditor(
    target: String?,
    initialText: String?,
    restartKey: Int,
    recent: () -> Flow<List<RecentItem>>,
    onDecision: (AddressDecision) -> Unit,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit,
    mode: AddressEditorMode = AddressEditorMode.EDITING,
    prefill: AddressPrefill = AddressPrefill.DRAFT,
    focusKey: Int? = 0,
    onFocused: () -> Unit = {},
    @StringRes placeholder: Int = R.string.address_placeholder,
    modifier: Modifier = Modifier,
) {
    val initial = initialText.orEmpty()
    val saver = remember(target, restartKey) { addressDraftSaver(target, restartKey) }
    var draft by rememberSaveable(target, restartKey, saver = saver) {
        mutableStateOf(AddressEditorDraft(TextFieldValue(initial, TextRange(0, initial.length)), initial))
    }
    val field = draft.field
    val editing = mode == AddressEditorMode.EDITING
    // Keep the prefill with its draft: suggestions wait until that entry's text is edited.
    val edited = editing && AddressSuggestions.shouldSuggest(field.text, draft.prefilled)
    val matches = rememberMatches(recent, field.text.trim(), edited, target)

    AddressEditorContent(
        field = field,
        onFieldChange = { draft = draft.copy(field = it) },
        matches = matches,
        // Going to the page you are on changes nothing, so its address offers no action until
        // edited; Go on the keyboard still loads it again.
        offersDecision = editing && (edited || prefill == AddressPrefill.DRAFT),
        target = target,
        onDecision = onDecision,
        onSelectTab = onSelectTab,
        onNewTab = onNewTab,
        onDismiss = onDismiss,
        mode = mode,
        focusKey = focusKey,
        onFocused = onFocused,
        placeholder = placeholder,
        modifier = modifier,
    )
}

/**
 * Local matches for [query] once the text is [edited]: from this device only, so typing contacts
 * no server. One subscription for the editor's life; ranking parses every candidate's host, so it
 * waits for a pause in typing and runs off the main thread. Until it is done the last ranking
 * stays, less the rows the text no longer matches: typing never blanks the list (the panel would
 * collapse and grow again with each key), and no row stands for text it does not contain.
 */
@Composable
private fun rememberMatches(
    recent: () -> Flow<List<RecentItem>>,
    query: String,
    edited: Boolean,
    target: String?,
): List<RecentItem> {
    val items by remember { recent() }.collectAsStateWithLifecycle(emptyList(), context = Dispatchers.Main.immediate)
    val ranking by produceState(Ranking.NONE, items, query, edited) {
        if (!edited) {
            value = Ranking.NONE
            return@produceState
        }
        delay(SUGGESTION_DELAY_MS)
        // Ranked off the main thread, written on it, as for the flows the UI collects (BrowserScreen).
        withContext(Dispatchers.Main.immediate) {
            val rows = withContext(Dispatchers.Default) {
                AddressSuggestions.rank(items.filter { it.matches(query) }, query, target)
            }
            value = Ranking(items, rows)
        }
    }
    if (!edited) return emptyList()
    val rows = ranking.rows.filter { it.matches(query) }
    if (ranking.items === items) return rows
    // Recent changed since that ranking (a page loaded, a tab closed): until the next one, only
    // its rows still in Recent stay, so a closed tab is never offered.
    val current = remember(items) { items.mapTo(HashSet()) { it.key } }
    return rows.filter { it.key in current }
}

/** Ranked rows and the Recent list they were ranked from. */
private class Ranking(val items: List<RecentItem>, val rows: List<RecentItem>) {
    companion object {
        val NONE = Ranking(emptyList(), emptyList())
    }
}

private data class AddressEditorDraft(val field: TextFieldValue, val prefilled: String)

// Save the state itself: rejecting a snapshot must run the initializer, not restore a null value.
private fun addressDraftSaver(target: String?, restartKey: Int) = Saver<MutableState<AddressEditorDraft>, Any>(
    save = { state ->
        val draft = state.value
        with(TextFieldValue.Saver) { save(draft.field) }?.let { field ->
            listOf(target, restartKey, draft.prefilled, field)
        }
    },
    restore = restore@{ raw ->
        // rememberSaveable inputs reset live state, but are not checked against a restored
        // provider snapshot. A new Home entry must not resurrect an earlier draft or prefill.
        val values = raw as? List<*> ?: return@restore null
        if (values.size != 4 || values[0] != target || values[1] != restartKey) return@restore null
        val prefilled = values[2] as? String ?: return@restore null
        val field = values[3] ?: return@restore null
        TextFieldValue.Saver.restore(field)?.let { mutableStateOf(AddressEditorDraft(it, prefilled)) }
    },
)

/**
 * The editor's layout, apart from its state so a device test can check it. While editing:
 *
 * ```
 *  ┌──────────────────────────────────────┐
 *  │ ▭  Older match                       │   the panel: only as tall as its rows,
 *  │ ▭  Best match · Switch to tab        │   scrolling in the room the keyboard leaves
 *  │ →  Go to https://redd                │   the other reading of the text
 *  │ ⌕  Search Kagi for “redd”            │   what Go does, tinted
 *  ├──────────────────────────────────────┤
 *  │ ←  ( redd                      ✕ )  + │   Close, the field with Clear, New tab
 *  └──────────────────────────────────────┘
 *                 keyboard
 * ```
 *
 * Idle (Home's dock), only the field shows.
 */
@Composable
internal fun AddressEditorContent(
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    matches: List<RecentItem>,
    offersDecision: Boolean,
    target: String?,
    onDecision: (AddressDecision) -> Unit,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit,
    mode: AddressEditorMode = AddressEditorMode.EDITING,
    focusKey: Int? = 0,
    onFocused: () -> Unit = {},
    @StringRes placeholder: Int = R.string.address_placeholder,
    modifier: Modifier = Modifier,
) {
    val interpretation = AddressPolicy.interpret(field.text)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val editing = mode == AddressEditorMode.EDITING
    val paneTitle = stringResource(R.string.address_title)

    // Editing, the bar becomes a pane of its own: TalkBack announces it as it did the old sheet.
    Column(modifier.fillMaxWidth().then(if (editing) Modifier.semantics { this.paneTitle = paneTitle } else Modifier)) {
        if (editing) {
            // Measured after the field row: however many rows there are, the field stays on the
            // keyboard and only the panel scrolls.
            SuggestionPanel(
                interpretation = interpretation.takeIf { offersDecision },
                matches = matches,
                query = field.text.trim(),
                opensHere = target != null,
                onDecision = onDecision,
                onSelectTab = onSelectTab,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = if (editing) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Where the bar's Back is: it leaves the editor, as system Back does.
            if (editing) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_close))
                }
            }
            AddressField(
                field = field,
                onFieldChange = onFieldChange,
                clearable = editing,
                placeholder = placeholder,
                onGo = { if (interpretation.primary !is AddressDecision.Rejected) onDecision(interpretation.primary) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) onFocused() },
            )
            // + as in the bar, kept beside the field: a new tab is always one tap away.
            if (editing) {
                IconButton(onClick = onNewTab) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_new_tab))
                }
            }
        }
    }
    LaunchedEffect(focusKey) {
        if (focusKey == null) return@LaunchedEffect
        focus.requestFocus()
        keyboard?.show()
    }
}

/**
 * A pill with the address chip's shape, colour and height, so the chip seems to open into it.
 * Its ✕ empties the field ([clearable]); leaving the editor is the row's ←, Back or a tap
 * outside.
 */
@Composable
private fun AddressField(
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    clearable: Boolean,
    @StringRes placeholder: Int,
    onGo: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyLarge
    val clear = clearable && field.text.isNotEmpty()
    BasicTextField(
        value = field,
        onValueChange = onFieldChange,
        singleLine = true,
        textStyle = textStyle.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier,
        decorationBox = { innerField ->
            Row(
                Modifier
                    .clip(FIELD_SHAPE)
                    .background(colors.surfaceContainerHighest)
                    .heightIn(min = FIELD_HEIGHT)
                    .padding(start = 16.dp, end = if (clear) 0.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (field.text.isEmpty()) {
                        Text(
                            stringResource(placeholder),
                            style = textStyle,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerField()
                }
                if (clear) {
                    IconButton(onClick = { onFieldChange(TextFieldValue()) }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear_address))
                    }
                }
            }
        },
    )
}

/**
 * The rows above the field, bottom up: what Go does ([interpretation]'s primary, tinted, or why
 * the text cannot be opened), its other reading, then [matches] best first. Nothing while there
 * is nothing to offer; it grows and shrinks briefly instead of jumping.
 */
@Composable
private fun SuggestionPanel(
    interpretation: AddressInterpretation?,
    matches: List<RecentItem>,
    query: String,
    opensHere: Boolean,
    onDecision: (AddressDecision) -> Unit,
    onSelectTab: (String) -> Unit,
    modifier: Modifier,
) {
    // Nothing typed yet is not a mistake: no row says so.
    val primary = interpretation?.primary?.takeUnless { it is AddressDecision.Rejected && it.reason == RejectReason.EMPTY }
    val alternative = interpretation?.alternative?.takeIf { primary != null }
    val rows = primary != null || matches.isNotEmpty()
    Column(
        modifier
            .fillMaxWidth()
            // Anchored at the bottom: the rows by the field stay put while the top edge moves.
            .animateContentSize(PANEL_RESIZE, alignment = Alignment.BottomCenter)
            // Starts at the bottom: rows that do not fit leave the top, away from the field.
            .verticalScroll(rememberScrollState(), reverseScrolling = true)
            .padding(top = if (rows) 8.dp else 0.dp),
    ) {
        for (item in matches.asReversed()) {
            key(item.key) {
                SuggestionRow(item, query, opensHere) {
                    when (item) {
                        is RecentItem.OpenTab -> onSelectTab(item.tabId)
                        // Like the typed address: it opens in the tab being edited.
                        is RecentItem.Visit -> AddressSuggestions.navigation(item)?.let(onDecision)
                    }
                }
            }
        }
        alternative?.let { DecisionRow(it, RowEmphasis.PLAIN) { onDecision(it) } }
        when (primary) {
            null -> Unit
            is AddressDecision.Rejected -> RejectionRow(primary.reason)
            else -> DecisionRow(primary, RowEmphasis.DEFAULT) { onDecision(primary) }
        }
    }
}

/** The row Go would choose stands out; the others are plain. */
private enum class RowEmphasis { DEFAULT, PLAIN }

/**
 * One panel row: a leading icon and [content], with the row's ripple (and a [DEFAULT] tint)
 * inside rounded corners. Not clickable without [onClick].
 */
@Composable
private fun PanelRow(
    icon: ImageVector,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    emphasis: RowEmphasis = RowEmphasis.PLAIN,
    iconTint: Color = Color.Unspecified,
    onClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tinted = emphasis == RowEmphasis.DEFAULT
    CompositionLocalProvider(LocalContentColor provides if (tinted) colors.onSecondaryContainer else colors.onSurface) {
        Row(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .clip(ROW_SHAPE)
                .background(if (tinted) colors.secondaryContainer else Color.Transparent)
                .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
                .heightIn(min = ROW_HEIGHT)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = if (iconTint == Color.Unspecified) LocalContentColor.current else iconTint)
            Spacer(Modifier.width(16.dp))
            content()
        }
    }
}

/** What submitting does, or its other reading: one line, the host kept whole where it can be. */
@Composable
private fun DecisionRow(decision: AddressDecision, emphasis: RowEmphasis, onClick: () -> Unit) {
    PanelRow(decisionIcon(decision) ?: Icons.AutoMirrored.Filled.ArrowForward, onClick, emphasis = emphasis) {
        DecisionText(decision, describe(decision))
    }
}

/** Why the text cannot be opened, announced as it changes; Go does nothing. */
@Composable
private fun RejectionRow(reason: RejectReason) {
    val error = MaterialTheme.colorScheme.error
    PanelRow(
        Icons.Filled.Warning,
        onClick = null,
        iconTint = error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(rejectionText(reason), color = error)
    }
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
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodyMedium

    PanelRow(
        if (isTab) TabIcon else HistoryIcon,
        onClick = onClick,
        iconTint = secondary,
        onClickLabel = when {
            isTab -> switchToTab
            opensHere -> stringResource(R.string.address_open_here)
            else -> stringResource(R.string.address_open_in_new_tab)
        },
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                remember(title, query, highlight) { highlightMatches(title, query, highlight) },
                color = if (isTab) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // "Switch to tab" leads the second line instead of trailing the row: a trailing label
            // would leave the title little room at large font sizes on a narrow phone. The host
            // is its own text, shortened from its start like the address chip's: a page's title
            // can name any site, and a long host must not lose the registrable domain.
            Row {
                if (isTab) {
                    Text(
                        if (host != null) "$switchToTab ·" else switchToTab,
                        style = small,
                        color = secondary,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                host?.let {
                    Text(
                        remember(it, query, highlight) { highlightMatches(it, query, highlight) },
                        style = small,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                }
            }
        }
    }
}

/**
 * One line. An address shows its host in its own text, which shortens from its start like the
 * address chip's, so a long host keeps the registrable domain that names the site. The words and
 * scheme before it stay whole; the path after it gets what room is left. A search shortens in
 * the middle.
 */
@Composable
private fun RowScope.DecisionText(decision: AddressDecision, text: String) {
    val url = when (decision) {
        is AddressDecision.Navigate -> decision.url
        is AddressDecision.External -> decision.url
        is AddressDecision.Search, is AddressDecision.Rejected -> null
    }
    val split = url?.let { splitAtHost(text, it) }
    if (split == null) {
        Text(text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
        return
    }
    // The row measures these in order: the host gets the room the words leave, and the path
    // whatever is left after it.
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

/** The address chip's height and shape (BottomBar), so the chip seems to open into the field. */
private val FIELD_HEIGHT = 48.dp
private val FIELD_SHAPE = RoundedCornerShape(24.dp)

/** A panel row: a 48dp touch target, two lines for a match. */
private val ROW_HEIGHT = 48.dp
private val ROW_SHAPE = RoundedCornerShape(16.dp)

/** Rows come and go as you type: the panel's edge glides instead of jumping. */
private val PANEL_RESIZE = tween<IntSize>(durationMillis = 150, easing = FastOutSlowInEasing)

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
