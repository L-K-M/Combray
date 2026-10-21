package ch.lkmc.combray.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.FindOutcome
import ch.lkmc.combray.core.browser.FindState
import ch.lkmc.combray.core.browser.FindStep
import ch.lkmc.combray.core.browser.MAX_FIND_QUERY_LENGTH
import kotlinx.coroutines.delay

/**
 * Find in page, in place of the bottom bar (R3): the query, where the selected match is, and
 * Previous, Next and Close. Typing searches once it pauses; the keyboard's Search key goes to
 * the next match.
 */
@Composable
fun FindBar(state: FindState, onFind: (String, FindStep) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    val asked by rememberUpdatedState(state.query)
    val focus = remember { FocusRequester() }

    // One search per pause in typing, not per keystroke; Search or a step may have asked already.
    LaunchedEffect(query) {
        if (query == asked) return@LaunchedEffect
        delay(TYPING_PAUSE_MS)
        if (query != asked) onFind(query, FindStep.FIRST)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // The answer belongs to the query the page was asked about, not to text typed since.
    val outcome = state.outcome.takeIf { query == state.query }
    val stepping = outcome is FindOutcome.Found
    Surface(tonalElevation = 3.dp, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(MAX_FIND_QUERY_LENGTH) },
                singleLine = true,
                label = { Text(stringResource(R.string.action_find_in_page), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onFind(query, FindStep.NEXT) }),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).focusRequester(focus),
            )
            // A live region: TalkBack announces each new position or "No matches".
            Text(
                findLabel(outcome),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 96.dp).padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(onClick = { onFind(query, FindStep.PREVIOUS) }, enabled = stepping) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.action_find_previous))
            }
            IconButton(onClick = { onFind(query, FindStep.NEXT) }, enabled = stepping) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.action_find_next))
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close_find))
            }
        }
    }
}

/** "3 of 12", "3 of 999+" when the count is a lower bound, or why there is no position. */
@Composable
private fun findLabel(outcome: FindOutcome?): String = when (outcome) {
    null -> ""
    is FindOutcome.Found -> {
        val count = if (outcome.capped) stringResource(R.string.find_count_at_least, outcome.count) else outcome.count.toString()
        stringResource(R.string.find_position, outcome.index + 1, count)
    }
    is FindOutcome.NotFound -> stringResource(if (outcome.capped) R.string.find_none_in_part else R.string.find_none)
    FindOutcome.Failed -> stringResource(R.string.find_failed)
}

/** Typing pause after which the page is searched. */
private const val TYPING_PAUSE_MS = 250L
