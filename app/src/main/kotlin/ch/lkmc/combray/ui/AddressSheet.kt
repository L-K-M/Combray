package ch.lkmc.combray.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Pause after the last keystroke before Recent is queried for matches. */
private const val SUGGESTION_DELAY_MS = 150L

/**
 * Address and search editor above the keyboard. The current address starts selected for
 * replacement; the button says what submitting will do, and the other reading is one tap away.
 * [target] is the tab the editor was opened for (null: a new tab); typed text survives rotation.
 * Once you change the [initialText], matching open tabs and recent pages from [suggestions] (the
 * local Recent projection) are listed between the field and the buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressSheet(
    target: String?,
    initialText: String?,
    suggestions: (String) -> Flow<List<RecentItem>>,
    onDecision: (AddressDecision) -> Unit,
    onSelectTab: (String) -> Unit,
    onOpenVisit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = initialText.orEmpty()
    var field by rememberSaveable(target, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val prefilled = rememberSaveable(target) { initial }
    val interpretation = AddressPolicy.interpret(field.text)
    val focus = remember { FocusRequester() }

    // Matches come from this device only: typing contacts no server. The prefilled text lists
    // none (it is where you are), and the pause spares the database a query per keystroke.
    val query = field.text.trim()
    val edited = query.isNotEmpty() && field.text != prefilled
    val matches by remember(query, edited) {
        if (!edited) {
            flowOf(emptyList())
        } else {
            flow {
                delay(SUGGESTION_DELAY_MS)
                emitAll(suggestions(query).map { AddressSuggestions.rank(it, query, target) })
            }.flowOn(Dispatchers.Default)
        }
    }.collectAsStateWithLifecycle(emptyList())
    val inset = Modifier.padding(horizontal = 16.dp)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.address_placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = {
                    if (interpretation.primary !is AddressDecision.Rejected) onDecision(interpretation.primary)
                }),
                modifier = inset.fillMaxWidth().focusRequester(focus),
            )
            // Only the room left above the keyboard, scrolling within it: the buttons below
            // stay next to the keyboard, where the thumb is, however many rows there are.
            if (matches.isNotEmpty()) {
                Column(Modifier.weight(1f, fill = false).padding(top = 8.dp).verticalScroll(rememberScrollState())) {
                    for (item in matches) {
                        key(item.key) {
                            SuggestionRow(item) {
                                when (item) {
                                    is RecentItem.OpenTab -> onSelectTab(item.tabId)
                                    is RecentItem.Visit -> onOpenVisit(item.visitId)
                                }
                            }
                        }
                    }
                }
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
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** A local match: an open tab to switch to, or a recent page to open in a new tab (as in Recent). */
@Composable
private fun SuggestionRow(item: RecentItem, onClick: () -> Unit) {
    val host = item.url?.let { displayHost(it) }
    val title = item.title?.takeIf { it.isNotBlank() } ?: host.orEmpty()
    val isTab = item is RecentItem.OpenTab
    val switchToTab = stringResource(R.string.address_switch_to_tab)
    // "Switch to tab" leads the second line instead of trailing the row: a trailing label would
    // leave the title little room at large font sizes on a narrow phone.
    val details = if (isTab) listOfNotNull(switchToTab, host) else listOfNotNull(host)

    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(details.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(if (isTab) TabIcon else HistoryIcon, contentDescription = null) },
        colors = if (isTab) {
            ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = MaterialTheme.colorScheme.primary)
        } else {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        },
        modifier = Modifier.clickable(
            onClickLabel = if (isTab) switchToTab else stringResource(R.string.address_open_in_new_tab),
            onClick = onClick,
        ),
    )
}

/** One line shortened in the middle: the start of an address (its host) and its end stay. */
@Composable
private fun DecisionLabel(decision: AddressDecision, text: String) {
    decisionIcon(decision)?.let { icon ->
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    }
    Text(text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
}

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
