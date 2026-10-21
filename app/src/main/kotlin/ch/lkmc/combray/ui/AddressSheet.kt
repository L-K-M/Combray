package ch.lkmc.combray.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.R
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.core.policy.RejectReason

/**
 * Address and search editor above the keyboard. [initialText] (the current address, or text
 * another app sent) starts selected for replacement; the button says what submitting will do,
 * and the other reading is one tap away. [target] is the tab the editor was opened for (null: a
 * new tab); typed text survives rotation. A new [restartKey] starts over with [initialText].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressSheet(
    target: String?,
    initialText: String?,
    restartKey: Int,
    onDecision: (AddressDecision) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = initialText.orEmpty()
    var field by rememberSaveable(target, restartKey, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val interpretation = AddressPolicy.interpret(field.text)
    val focus = remember { FocusRequester() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
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
