package ch.lkmc.combray.ui

import android.content.res.Resources
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.combray.BuildConfig
import ch.lkmc.combray.R
import ch.lkmc.combray.ThirdPartyNotices
import ch.lkmc.combray.core.browser.BrowserSettings
import ch.lkmc.combray.core.browser.Retention
import ch.lkmc.combray.core.browser.UnusedTabs
import ch.lkmc.combray.core.data.JobState
import ch.lkmc.combray.core.data.OutboxJobEntity
import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import ch.lkmc.combray.core.integration.AddressField
import ch.lkmc.combray.core.integration.Configured
import ch.lkmc.combray.core.integration.ConnectionCheck
import ch.lkmc.combray.core.integration.Integrations
import ch.lkmc.combray.core.integration.KarakeepList
import ch.lkmc.combray.core.integration.KarakeepLists
import ch.lkmc.combray.core.integration.KarakeepRead
import ch.lkmc.combray.core.integration.SecretName
import ch.lkmc.combray.core.integration.ServiceRootProblem
import ch.lkmc.combray.core.integration.ServiceUrls
import ch.lkmc.combray.core.policy.HostNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Where the source of this build, including the engine patches, is published (MPL-2.0 §3.2). */
private const val SOURCE_URL = "https://github.com/L-K-M/Combray"

/** Queue states the user can retry or discard one by one. */
private val ACTIONABLE_STATES = setOf(JobState.FAILED, JobState.RETRY_WAIT, JobState.AUTH_REQUIRED)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    integrations: Integrations,
    settings: BrowserSettings,
    /** Applies a changed tab choice now rather than at the next maintenance. */
    onUnusedTabsChanged: () -> Unit,
    notices: ThirdPartyNotices,
    onDismiss: () -> Unit,
) {
    var showNotices by rememberSaveable { mutableStateOf(false) }
    if (showNotices) NoticesDialog(notices) { showNotices = false }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            ServiceSettings(
                integrations = integrations,
                labels = ServiceLabels(
                    title = R.string.karakeep_title,
                    explanation = R.string.karakeep_explanation,
                    urlHint = R.string.karakeep_url_hint,
                    secret = R.string.karakeep_key,
                    secretSaved = R.string.karakeep_key_saved,
                    secretMissing = R.string.settings_key_missing,
                    secretInvalid = R.string.settings_key_invalid,
                    secretNotStored = R.string.check_key_not_stored,
                    rootInvalid = R.string.settings_root_invalid,
                    cleartextWarning = R.string.settings_root_cleartext,
                    unauthorized = R.string.check_unauthorized,
                    wrongAddress = R.string.check_wrong_address,
                    removeBody = R.plurals.karakeep_remove_body,
                    queue = R.string.queue_title,
                    queuePaused = R.string.queue_paused,
                    resume = R.string.action_resume_all,
                ),
                kind = ServiceKind.KARAKEEP,
                secret = SecretName.KARAKEEP_API_KEY,
                service = integrations.karakeepService,
                configure = integrations::configureKarakeep,
                check = integrations::checkKarakeep,
                remove = integrations::removeKarakeep,
            ) { service -> KarakeepListSetting(integrations, service) }
            ServiceSettings(
                integrations = integrations,
                labels = ServiceLabels(
                    title = R.string.hister_title,
                    explanation = R.string.hister_explanation,
                    urlHint = R.string.hister_url_hint,
                    secret = R.string.hister_token,
                    secretSaved = R.string.hister_token_saved,
                    secretMissing = R.string.settings_token_missing,
                    secretInvalid = R.string.settings_token_invalid,
                    secretNotStored = R.string.check_token_not_stored,
                    rootInvalid = R.string.settings_root_invalid_hister,
                    cleartextWarning = R.string.settings_root_cleartext_hister,
                    unauthorized = R.string.check_token_unauthorized,
                    wrongAddress = R.string.check_wrong_address_hister,
                    removeBody = R.plurals.hister_remove_body,
                    queue = R.string.hister_queue_title,
                    queuePaused = R.string.queue_paused_hister,
                    resume = R.string.action_resume_captures,
                ),
                kind = ServiceKind.HISTER,
                secret = SecretName.HISTER_TOKEN,
                service = integrations.hister.service,
                configure = integrations.hister::configure,
                check = integrations.hister::check,
                remove = integrations.hister::remove,
            ) { service -> HisterCaptureSettings(integrations, service) }
            SectionTitle(R.string.settings_history_title)
            val days = Retention().closedHistoryDays
            Text(pluralStringResource(R.plurals.settings_history_body, days, days))
            UnusedTabsSetting(settings, days, onUnusedTabsChanged)
            SectionTitle(R.string.settings_about_title)
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
            Text(stringResource(R.string.settings_engine, BuildConfig.SERVO_VERSION))
            SelectionContainer { Text(stringResource(R.string.settings_source, SOURCE_URL)) }
            OutlinedButton(onClick = { showNotices = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.notices_title))
            }
        }
    }
}

/** Opt-in: open tabs unused for the retention period close like history expires. */
@Composable
private fun UnusedTabsSetting(settings: BrowserSettings, days: Int, onChanged: () -> Unit) {
    val choice by settings.unusedTabs.collectAsStateWithLifecycle()
    val closing = choice == UnusedTabs.CLOSE
    // One accessibility node per row: TalkBack reads the label with the switch state.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)
            .toggleable(value = closing, role = Role.Switch) { enabled ->
                settings.setUnusedTabs(if (enabled) UnusedTabs.CLOSE else UnusedTabs.KEEP)
                onChanged()
            },
    ) {
        Text(pluralStringResource(R.plurals.settings_close_unused_tabs, days, days), modifier = Modifier.weight(1f))
        Switch(checked = closing, onCheckedChange = null)
    }
    Text(stringResource(R.string.settings_close_unused_tabs_body), style = MaterialTheme.typography.bodySmall)
}

/** Each service's own wording: an API key for Karakeep, an access token for Hister. */
private data class ServiceLabels(
    val title: Int,
    val explanation: Int,
    val urlHint: Int,
    val secret: Int,
    val secretSaved: Int,
    val secretMissing: Int,
    val secretInvalid: Int,
    val secretNotStored: Int,
    val rootInvalid: Int,
    val cleartextWarning: Int,
    val unauthorized: Int,
    val wrongAddress: Int,
    val removeBody: Int,
    val queue: Int,
    val queuePaused: Int,
    val resume: Int,
)

/**
 * Server address and credential for one service. The credential is write-only: a saved one is
 * never shown again, only replaced. Checking the connection is read-only.
 */
@Composable
private fun ServiceSettings(
    integrations: Integrations,
    labels: ServiceLabels,
    kind: ServiceKind,
    secret: SecretName,
    service: Flow<ServiceEntity?>,
    /** Address, credential (null keeps the stored one), alternate address. */
    configure: suspend (String, String?, String?) -> Configured,
    check: suspend () -> ConnectionCheck?,
    remove: suspend () -> Unit,
    extra: @Composable (ServiceEntity) -> Unit = {},
) {
    val current by service.collectAsStateWithLifecycle(null)
    val jobs by remember(kind) { integrations.openJobs(kind) }.collectAsStateWithLifecycle(emptyList())
    val parked by remember(kind) { integrations.parkedJobs(kind) }.collectAsStateWithLifecycle(emptyList())
    // Null until edited: the field shows the stored address, and an edit survives rotation.
    var editedAddress by rememberSaveable { mutableStateOf<String?>(null) }
    val address = editedAddress ?: current?.baseUrl.orEmpty()
    var editedAlternate by rememberSaveable { mutableStateOf<String?>(null) }
    val alternate = editedAlternate ?: current?.alternateUrl.orEmpty()
    var credential by remember { mutableStateOf("") }
    var credentialSaved by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    LaunchedEffect(current) { credentialSaved = current?.let { integrations.hasCredential(secret, it) } == true }

    SectionTitle(labels.title)
    Text(stringResource(labels.explanation))
    OutlinedTextField(
        value = address,
        onValueChange = { editedAddress = it },
        label = { Text(stringResource(R.string.karakeep_url)) },
        placeholder = { Text(stringResource(labels.urlHint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        value = alternate,
        onValueChange = { editedAlternate = it },
        label = { Text(stringResource(R.string.settings_alternate_url)) },
        placeholder = { Text(stringResource(R.string.settings_alternate_url_hint)) },
        supportingText = { Text(stringResource(R.string.settings_alternate_url_help)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = credential,
        onValueChange = { credential = it },
        label = { Text(stringResource(labels.secret)) },
        supportingText = if (credentialSaved) ({ Text(stringResource(labels.secretSaved)) }) else null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Button(
            onClick = {
                scope.launch {
                    val result = configure(address, credential.ifBlank { null }, alternate)
                    message = describe(resources, labels, result)
                    if (result == Configured.Saved) {
                        editedAddress = null
                        editedAlternate = null
                    }
                    credential = ""
                }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.action_save_settings)) }
        OutlinedButton(
            onClick = {
                message = resources.getString(R.string.check_running)
                scope.launch { message = check()?.let { describe(resources, labels, it) } }
            },
            enabled = current != null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.action_check_connection)) }
    }
    if (current != null) {
        TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.action_remove)) }
    }
    // Removing cancels every unsent item for the service: say how many before doing it.
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_service_title, stringResource(labels.title))) },
            text = { Text(pluralStringResource(labels.removeBody, jobs.size + parked.size, jobs.size + parked.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    editedAddress = null
                    editedAlternate = null
                    scope.launch { remove(); message = null }
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    message?.let { Text(it, modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
    if (current?.roots?.any(ServiceUrls::isCleartext) == true) {
        Text(stringResource(labels.cleartextWarning), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
    }
    current?.let { extra(it) }

    Text(stringResource(labels.queue), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
    if (jobs.isEmpty() && parked.isEmpty()) Text(stringResource(R.string.queue_empty))
    jobs.forEach { job -> QueueRow(integrations, labels, job) }
    if (jobs.any { it.state == JobState.AUTH_REQUIRED }) {
        OutlinedButton(onClick = { integrations.resume(kind) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(labels.resume))
        }
    }
    // Only saves are parked by a main address change (captures are cancelled), hence the wording.
    // They are never sent to the new address without this explicit choice.
    if (parked.isNotEmpty()) {
        val discardLabel = stringResource(R.string.action_discard_parked)
        Text(pluralStringResource(R.plurals.queue_parked, parked.size, parked.size), modifier = Modifier.padding(top = 8.dp))
        OutlinedButton(onClick = { integrations.sendParkedToCurrent(kind) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.action_send_to_current_address))
        }
        TextButton(
            onClick = { confirmDiscard = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = discardLabel },
        ) { Text(stringResource(R.string.action_discard)) }
    }
    // Discarding cannot be undone, and the saves were the user's own: confirm first.
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_parked_title)) },
            text = { Text(pluralStringResource(R.plurals.queue_parked, parked.size, parked.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    integrations.discardParked(kind)
                }) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** The Karakeep list saves are added to. Its lists are read from the server when choosing. */
@Composable
private fun KarakeepListSetting(integrations: Integrations, service: ServiceEntity) {
    var choosing by remember { mutableStateOf<KarakeepLists?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<KarakeepRead<*>?>(null) }
    var serverChanged by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text(stringResource(R.string.karakeep_list_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
    Text(stringResource(R.string.karakeep_list_explanation), style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(service.listName ?: stringResource(R.string.karakeep_list_none), modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = {
                loading = true
                failure = null
                serverChanged = false
                scope.launch {
                    val lists = integrations.karakeepLists()
                    loading = false
                    if (lists?.read is KarakeepRead.Ok) choosing = lists else failure = lists?.read
                }
            },
            enabled = !loading,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.karakeep_list_choose)) }
    }
    val problem = when {
        serverChanged -> stringResource(R.string.karakeep_list_server_changed)
        else -> failure?.let { karakeepProblem(it) }
    }
    problem?.let { Text(it, modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }) }

    choosing?.let { lists ->
        KarakeepListDialog(
            lists = (lists.read as KarakeepRead.Ok).value,
            selected = service.listId,
            onChoose = { list ->
                choosing = null
                scope.launch { serverChanged = !integrations.setKarakeepList(lists.fingerprint, list) }
            },
            onDismiss = { choosing = null },
        )
    }
}

/** One tap chooses: "No list" or one of the manual lists the user may add to. */
@Composable
private fun KarakeepListDialog(lists: List<KarakeepList>, selected: String?, onChoose: (KarakeepList?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.karakeep_list_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ListOption(stringResource(R.string.karakeep_list_none), selected == null) { onChoose(null) }
                lists.forEach { list -> ListOption(list.name, selected == list.id) { onChoose(list) } }
                if (lists.isEmpty()) Text(stringResource(R.string.karakeep_list_empty), modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ListOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Opt-in capture, its budget, and the sites that are never sent. */
@Composable
private fun HisterCaptureSettings(integrations: Integrations, service: ServiceEntity) {
    val hister = integrations.hister
    val exclusions by hister.exclusions.collectAsStateWithLifecycle(emptyList())
    val queued by hister.queuedBytes.collectAsStateWithLifecycle(0L)
    val budgetReached by hister.captureBudgetReached.collectAsStateWithLifecycle(false)
    var site by rememberSaveable { mutableStateOf("") }
    var includeSubdomains by rememberSaveable { mutableStateOf(true) }
    var problem by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val subdomains = stringResource(R.string.hister_exclusion_subdomains)

    // One accessibility node per row: TalkBack reads the label with the switch state.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)
            .toggleable(value = service.captureEnabled, role = Role.Switch) { enabled -> scope.launch { hister.setCaptureEnabled(enabled) } },
    ) {
        Text(stringResource(R.string.hister_capture), modifier = Modifier.weight(1f))
        Switch(checked = service.captureEnabled, onCheckedChange = null)
    }
    Text(stringResource(R.string.hister_capture_explanation), style = MaterialTheme.typography.bodySmall)
    val size = Formatter.formatShortFileSize(context, queued)
    Text(
        if (budgetReached) stringResource(R.string.hister_capture_paused_budget, size) else stringResource(R.string.hister_queue_size, size),
        modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )

    Text(stringResource(R.string.hister_exclusions), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
    exclusions.forEach { exclusion ->
        val site = HostNames.forDisplay(exclusion.host)
        val label = if (exclusion.includeSubdomains) "$site ($subdomains)" else site
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            IconButton(onClick = { scope.launch { hister.removeExclusion(exclusion.host) } }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.hister_exclusion_remove, site))
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = site,
            onValueChange = { site = it; problem = false },
            placeholder = { Text(stringResource(R.string.hister_exclusion_hint)) },
            singleLine = true,
            isError = problem,
            supportingText = if (problem) ({ Text(stringResource(R.string.hister_exclusion_invalid)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            scope.launch {
                if (hister.addExclusion(site, includeSubdomains)) site = "" else problem = true
            }
        }) { Text(stringResource(R.string.hister_exclusion_add)) }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 48.dp).toggleable(value = includeSubdomains, role = Role.Checkbox) { includeSubdomains = it },
    ) {
        Checkbox(checked = includeSubdomains, onCheckedChange = null)
        Text(stringResource(R.string.hister_exclusion_subdomains))
    }
}

@Composable
private fun QueueRow(integrations: Integrations, labels: ServiceLabels, job: OutboxJobEntity) {
    val error = job.error.orEmpty()
    val state = when (job.state) {
        JobState.PENDING, JobState.RUNNING -> stringResource(R.string.queue_pending)
        JobState.RETRY_WAIT -> stringResource(R.string.queue_retry, error)
        JobState.AUTH_REQUIRED -> stringResource(labels.queuePaused, error)
        else -> stringResource(R.string.queue_failed, error)
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(integrations.jobUrl(job)?.let(::displayHost) ?: job.dedupeKey, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(state, style = MaterialTheme.typography.bodySmall)
        // A paused item can be dropped on its own: "Resume all" is not the only way out.
        if (job.state in ACTIONABLE_STATES) {
            Row {
                TextButton(onClick = { integrations.retry(job.id) }) { Text(stringResource(R.string.action_retry)) }
                TextButton(onClick = { integrations.discard(job.id) }) { Text(stringResource(R.string.action_discard)) }
            }
        }
    }
}

private fun describe(resources: Resources, labels: ServiceLabels, result: Configured): String = when (result) {
    Configured.Saved -> resources.getString(R.string.settings_saved)
    Configured.MissingCredential -> resources.getString(labels.secretMissing)
    Configured.InvalidCredential -> resources.getString(labels.secretInvalid)
    Configured.CredentialNotStored -> resources.getString(R.string.settings_credential_not_stored)
    is Configured.InvalidRoot -> resources.getString(
        when {
            result.problem == ServiceRootProblem.HAS_CREDENTIALS_OR_QUERY -> R.string.settings_root_query
            result.field == AddressField.ALTERNATE -> R.string.settings_alternate_invalid
            else -> labels.rootInvalid
        },
    )
}

private fun describe(resources: Resources, labels: ServiceLabels, check: ConnectionCheck): String = when (check) {
    is ConnectionCheck.Connected -> resources.getString(R.string.check_connected)
    ConnectionCheck.Unauthorized -> resources.getString(labels.unauthorized)
    ConnectionCheck.CredentialMissing -> resources.getString(labels.secretNotStored)
    is ConnectionCheck.WrongAddress -> resources.getString(labels.wrongAddress, check.status)
    is ConnectionCheck.Unreachable -> resources.getString(R.string.check_unreachable, check.category)
}

@Composable
fun SectionTitle(label: Int) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** The page's address and what the engine can say about its connection. */
@Composable
fun PageInfoDialog(url: String, onDismiss: () -> Unit) {
    val secure = url.startsWith("https:", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(displayHost(url)) },
        text = {
            Column {
                Text(stringResource(R.string.page_info_address), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(url) }
                Text(
                    stringResource(R.string.page_info_connection),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(stringResource(if (secure) R.string.page_info_https else R.string.page_info_http))
            }
        },
    )
}
