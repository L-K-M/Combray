package ch.lkmc.combray.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ch.lkmc.combray.R
import ch.lkmc.combray.UploadStaging
import ch.lkmc.combray.core.browser.DialogBlocking
import ch.lkmc.combray.core.browser.PageControl
import ch.lkmc.combray.engine.ControlResponse
import ch.lkmc.combray.engine.PageControlContent
import ch.lkmc.combray.engine.SelectEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val ROW_HEIGHT = 48.dp
private const val DISABLED_ALPHA = 0.38f

/**
 * Presents what the focused page asks. Back answers with the default (cancel, keep the
 * selection); a tap outside does nothing, so a stray touch cannot answer a page's question.
 * Uploads are copied in [uploadScope], which must outlive the composition: a rotation during
 * the copy would otherwise drop the answer and leave the page waiting with nothing shown.
 */
@Composable
fun PageControlDialog(
    control: PageControl,
    staging: UploadStaging,
    uploadScope: CoroutineScope,
    onAnswer: (ControlResponse, DialogBlocking) -> Unit,
    onUploadFailed: () -> Unit,
    /** Whether an address may open in a tab (the address policy refuses `javascript:` and the like). */
    canOpenInNewTab: (String) -> Boolean,
    onOpenInNewTab: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val answer = { response: ControlResponse -> onAnswer(response, DialogBlocking.KEEP_SHOWING) }
    when (val content = control.content) {
        is PageControlContent.Select -> SelectDialog(control.id, content, answer)
        is PageControlContent.Files -> FilePicker(control.id, content, staging, uploadScope, answer, onUploadFailed)
        is PageControlContent.LinkMenu -> LinkMenu(content, canOpenInNewTab, onOpenInNewTab, onShare) { answer(ControlResponse.Dismiss) }
        is PageControlContent.SignIn -> SignInDialog(control.id, content, answer)
        else -> ScriptDialog(control, content, onAnswer)
    }
}

/** Long-press on a link or image. The app acts on the address; Servo's own menu is dismissed. */
@Composable
private fun LinkMenu(
    content: PageControlContent.LinkMenu,
    canOpenInNewTab: (String) -> Boolean,
    onOpenInNewTab: (String) -> Unit,
    onShare: (String) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val copy = { url: String ->
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(url, url))
    }
    val actions = buildList {
        content.link?.let { link ->
            if (canOpenInNewTab(link)) add(R.string.link_open_new_tab to { onOpenInNewTab(link) })
            add(R.string.link_copy to { copy(link) })
            add(R.string.link_share to { onShare(link) })
        }
        content.image?.let { image ->
            if (canOpenInNewTab(image)) add(R.string.image_open_new_tab to { onOpenInNewTab(image) })
            add(R.string.image_copy to { copy(image) })
        }
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = {
            Text(
                content.link ?: content.image.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                actions.forEach { (label, action) ->
                    TextButton(
                        onClick = {
                            onDone()
                            action()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT),
                    ) { Text(stringResource(label), Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ScriptDialog(
    control: PageControl,
    content: PageControlContent,
    onAnswer: (ControlResponse, DialogBlocking) -> Unit,
) {
    var block by rememberSaveable(control.id) { mutableStateOf(false) }
    val blocking = if (block) DialogBlocking.BLOCK_FOR_PAGE else DialogBlocking.KEEP_SHOWING
    val (message, prompt) = when (content) {
        is PageControlContent.Alert -> content.message to null
        is PageControlContent.Confirm -> content.message to null
        is PageControlContent.Prompt -> content.message to content.default
        else -> return
    }
    var text by rememberSaveable(control.id) { mutableStateOf(prompt.orEmpty()) }
    val cancellable = content !is PageControlContent.Alert

    AlertDialog(
        onDismissRequest = { onAnswer(ControlResponse.Dismiss, blocking) },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(
                control.site?.let { stringResource(R.string.page_dialog_title, it) } ?: stringResource(R.string.page_dialog_title_unknown),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (message.isNotEmpty()) Text(message)
                if (prompt != null) {
                    // A label of its own: the page's message above may be long, or empty.
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.page_dialog_answer)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (control.offerBlocking) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).toggleable(block, role = Role.Checkbox) { block = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = block, onCheckedChange = null)
                        Text(stringResource(R.string.page_dialog_block), Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(if (prompt != null) ControlResponse.Text(text) else ControlResponse.Accept, blocking) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = if (cancellable) {
            { TextButton(onClick = { onAnswer(ControlResponse.Dismiss, blocking) }) { Text(stringResource(R.string.action_cancel)) } }
        } else {
            null
        },
    )
}

/**
 * HTTP sign-in. Names the server asking, not the page that linked there, and warns when the
 * password would travel unencrypted. The dialog keeps nothing: the fields use plain [remember],
 * so a password never reaches saved instance state, and a rotation clears them. What Servo
 * keeps of an answer is in docs/ENGINE_SPIKE.md section 13.
 */
@Composable
private fun SignInDialog(id: Long, content: PageControlContent.SignIn, onAnswer: (ControlResponse) -> Unit) {
    var username by remember(id) { mutableStateOf("") }
    var password by remember(id) { mutableStateOf("") }
    val unencrypted = content.origin.startsWith("http:", ignoreCase = true)
    val signIn = { onAnswer(ControlResponse.Credentials(username, password)) }
    val focus = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = { onAnswer(ControlResponse.Dismiss) },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(
                stringResource(if (content.proxy) R.string.sign_in_proxy_title else R.string.sign_in_title, content.origin),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (unencrypted) Text(stringResource(R.string.sign_in_unencrypted), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.sign_in_username)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.sign_in_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { signIn() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = signIn) {
                Text(stringResource(R.string.sign_in_action))
            }
        },
        dismissButton = { TextButton(onClick = { onAnswer(ControlResponse.Dismiss) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SelectDialog(id: Long, content: PageControlContent.Select, onAnswer: (ControlResponse) -> Unit) {
    var chosen by rememberSaveable(id) { mutableStateOf(content.selected.toSet()) }
    val pick = { option: SelectEntry ->
        if (content.multiple) {
            chosen = if (option.id in chosen) chosen - option.id else chosen + option.id
        } else {
            // A single choice answers at once, like a native dropdown.
            onAnswer(ControlResponse.Select(listOf(option.id)))
        }
    }

    AlertDialog(
        onDismissRequest = { onAnswer(ControlResponse.Dismiss) },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.page_select_title), Modifier.semantics { heading() }) },
        text = {
            LazyColumn {
                items(content.options.withGroupHeaders()) { row ->
                    when (row) {
                        is SelectRow.Group -> Text(
                            row.label,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
                        )
                        is SelectRow.Choice -> OptionRow(row.option, row.option.id in chosen, content.multiple) { pick(row.option) }
                    }
                }
            }
        },
        confirmButton = {
            if (content.multiple) {
                TextButton(onClick = { onAnswer(ControlResponse.Select(chosen.toList())) }) { Text(stringResource(R.string.action_ok)) }
            }
        },
        dismissButton = { TextButton(onClick = { onAnswer(ControlResponse.Dismiss) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun OptionRow(option: SelectEntry, selected: Boolean, multiple: Boolean, onClick: () -> Unit) {
    val role = if (multiple) Role.Checkbox else Role.RadioButton
    Row(
        Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).selectable(selected, enabled = !option.disabled, role = role, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multiple) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = !option.disabled)
        } else {
            RadioButton(selected = selected, onClick = null, enabled = !option.disabled)
        }
        Text(
            option.label,
            modifier = Modifier.padding(start = 12.dp),
            color = if (option.disabled) MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA) else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private sealed interface SelectRow {
    data class Group(val label: String) : SelectRow

    data class Choice(val option: SelectEntry) : SelectRow
}

/** A header before each run of options that share an `<optgroup>`. */
private fun List<SelectEntry>.withGroupHeaders(): List<SelectRow> {
    val rows = ArrayList<SelectRow>(size)
    var group: String? = null
    for (option in this) {
        val label = option.group
        if (label != null && label != group) rows += SelectRow.Group(label)
        group = label
        rows += SelectRow.Choice(option)
    }
    return rows
}

/** Opens Android's document picker once per control; the picked files are copied for Servo. */
@Composable
private fun FilePicker(
    id: Long,
    content: PageControlContent.Files,
    staging: UploadStaging,
    scope: CoroutineScope,
    onAnswer: (ControlResponse) -> Unit,
    onUploadFailed: () -> Unit,
) {
    val deliver = { uris: List<Uri> ->
        if (uris.isEmpty()) {
            onAnswer(ControlResponse.Dismiss)
        } else {
            scope.launch {
                val paths = staging.stage(uris)
                if (paths == null) onUploadFailed()
                onAnswer(paths?.let { ControlResponse.Files(it) } ?: ControlResponse.Dismiss)
            }
        }
    }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> deliver(listOfNotNull(uri)) }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> deliver(uris) }

    // Saved: after a configuration change the picker is still open and must not open twice.
    var launchedFor by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(id) {
        if (launchedFor == id) return@LaunchedEffect
        launchedFor = id
        val types = UploadStaging.mimeTypesFor(content.extensions)
        if (content.multiple) many.launch(types) else one.launch(types)
    }
}
