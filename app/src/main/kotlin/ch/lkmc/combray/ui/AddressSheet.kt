package ch.lkmc.combray.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import ch.lkmc.combray.core.browser.BrowserCoordinator
import ch.lkmc.combray.core.browser.RecentItem
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.core.policy.RejectReason

/**
 * Address and search editor above the keyboard. The current address starts selected for
 * replacement; the button says what submitting will do, and the other reading is one tap away.
 * While the text is edited, open tabs and recent pages from this device appear under the
 * field — one subscription for the sheet's life, filtered locally, no network.
 * [target] is the tab the editor was opened for (null: a new tab); typed text survives rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressSheet(
    target: String?,
    currentUrl: String?,
    browser: BrowserCoordinator,
    onDecision: (AddressDecision) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = currentUrl.orEmpty()
    var field by rememberSaveable(target, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val interpretation = AddressPolicy.interpret(field.text)
    val focus = remember { FocusRequester() }
    val items by remember(browser) { browser.recent() }.collectAsStateWithLifecycle(emptyList())
    val suggestions = addressSuggestions(items, field.text, initial)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Scrollable: five suggestions plus buttons and a large font must not clip.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.address_placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = {
                    if (interpretation.primary !is AddressDecision.Rejected) onDecision(interpretation.primary)
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (suggestions.isNotEmpty()) {
                Text(
                    stringResource(R.string.address_suggestions),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
                )
                suggestions.forEach { item -> SuggestionRow(item, onDecision) }
            }
            val primary = interpretation.primary
            if (primary is AddressDecision.Rejected) {
                Text(
                    rejectionText(primary.reason),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                Button(
                    onClick = { onDecision(primary) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp),
                ) {
                    Text(describe(primary), maxLines = 2)
                }
            }
            interpretation.alternative?.let { alternative ->
                TextButton(onClick = { onDecision(alternative) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.address_instead, describe(alternative)), maxLines = 2)
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** One local history row; opening it goes through the same address policy as typed text. */
@Composable
private fun SuggestionRow(item: RecentItem, onDecision: (AddressDecision) -> Unit) {
    val url = item.url ?: return
    val title = item.title?.takeIf { it.isNotBlank() } ?: displayHost(url)
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(displayHost(url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.clickable {
            (AddressPolicy.interpret(url).primary as? AddressDecision.Navigate)?.let(onDecision)
        },
    )
}

/**
 * History rows offered under the editor: only while the text differs from what the editor
 * opened with — on open the field holds the current address, and re-suggesting it (or
 * nothing, for a blank field) is noise. Rows must have an address and must actually open
 * through the address policy (a stored URL the policy now rejects is not offered as a dead
 * row — the typed path never submits a rejected reading either). One row per address,
 * newest first.
 *
 * [resembles] mirrors the Recent filter (title or address, case-insensitive) so this change
 * does not depend on that branch; once both land, collapse them onto one predicate.
 */
internal fun addressSuggestions(
    items: List<RecentItem>,
    text: String,
    initial: String,
    limit: Int = SUGGESTION_LIMIT,
): List<RecentItem> {
    if (text.isBlank() || text == initial) return emptyList()
    val needle = text.trim()
    return items
        .filter { item ->
            val url = item.url
            url != null &&
                resembles(item, needle) &&
                AddressPolicy.interpret(url).primary is AddressDecision.Navigate
        }
        .sortedByDescending { it.time }
        .distinctBy { it.url }
        .take(limit)
}

private fun resembles(item: RecentItem, needle: String): Boolean =
    (item.title?.contains(needle, ignoreCase = true) ?: false) ||
        (item.url?.contains(needle, ignoreCase = true) ?: false)

private const val SUGGESTION_LIMIT = 5

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
