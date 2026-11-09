package ch.lkmc.combray.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.combray.R
import ch.lkmc.combray.core.integration.ReportDraft
import ch.lkmc.combray.core.integration.ReportEnvironment
import ch.lkmc.combray.core.integration.ReportField
import ch.lkmc.combray.core.integration.ReportRequest
import ch.lkmc.combray.core.integration.ScreenshotOmission
import ch.lkmc.combray.core.integration.SensitiveUrls
import ch.lkmc.combray.core.integration.SiteReports
import kotlinx.coroutines.Dispatchers

/**
 * Why a report was not queued: [message] as the sheet says it, [withoutSheet] as a snackbar
 * says it once the sheet, and the draft with it, is gone.
 */
internal enum class ReportProblem(
    @StringRes val message: Int,
    @StringRes val withoutSheet: Int = R.string.report_lost,
    val offersSettings: Boolean = false,
) {
    NOT_SET_UP(R.string.report_not_set_up, R.string.report_lost_not_set_up, offersSettings = true),
    TOO_MANY(R.string.report_too_many, R.string.report_lost_too_many, offersSettings = true),
    NOT_STORED(R.string.report_not_stored, offersSettings = true),
    PREVIOUSLY_STORED(R.string.report_previously_stored, R.string.report_lost_previously_stored, offersSettings = true),
    ADDRESS(R.string.report_invalid_address),
    COMMENT(R.string.report_comment_too_long),
    SCREENSHOT(R.string.report_screenshot_invalid),
    OTHER(R.string.report_invalid),
    ;

    companion object {
        fun of(result: ReportRequest): ReportProblem? = when (result) {
            ReportRequest.Queued -> null
            ReportRequest.NotConfigured -> NOT_SET_UP
            ReportRequest.TooMany -> TOO_MANY
            ReportRequest.NotStored -> NOT_STORED
            ReportRequest.PreviouslyStored -> PREVIOUSLY_STORED
            is ReportRequest.Invalid -> when (result.field) {
                ReportField.URL -> ADDRESS
                ReportField.COMMENT -> COMMENT
                ReportField.SCREENSHOT -> SCREENSHOT
                ReportField.ID, ReportField.DETAILS -> OTHER
            }
        }
    }
}

/**
 * More › Report this site: a preview of everything the report sends, which the user can trim
 * before tapping Send. Back, a swipe or Cancel ends the report without sending anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportSheet(
    draft: ReportDraftState,
    serviceHost: String,
    environment: ReportEnvironment,
    onSend: (ReportDraft) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ReportSheetContent(draft, environment, onSend = onSend, onCancel = onDismiss, onOpenSettings = onOpenSettings, serviceHost = serviceHost)
    }
}

/** The sheet's content, apart from the sheet so checks can render it on a plain Compose host. */
@Composable
internal fun ReportSheetContent(
    draft: ReportDraftState,
    environment: ReportEnvironment,
    onSend: (ReportDraft) -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** The service Send would use now; Settings opened over the sheet can change it. */
    serviceHost: String = draft.serviceHost,
) {
    val shot by draft.shot.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val progress by draft.progress.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val snapshot = draft.snapshot
    val starting = remember(draft.id) { ReportAddress.starting(snapshot.url) }
    var address by rememberSaveable(draft.id) { mutableStateOf(starting) }
    var title by rememberSaveable(draft.id) { mutableStateOf(snapshot.title.orEmpty()) }
    var comment by rememberSaveable(draft.id) { mutableStateOf("") }
    var includeScreenshot by rememberSaveable(draft.id) { mutableStateOf(true) }
    val addressProblem = ReportAddress.problem(address)
    val sensitive = remember(address) { SensitiveUrls.looksSensitive(address) }
    val trimmed = ReportAddress.siteAndPath(address)
    val encoding = includeScreenshot && shot is ReportShot.Pending
    val send: () -> Unit = { draft.toDraft(ReportForm(address, title, comment, includeScreenshot), shot)?.let(onSend) }

    Column(
        modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.report_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        // Proposed default (open question 2): reports may reach the agents' model provider; say so.
        Text(stringResource(R.string.report_recipient, serviceHost))

        when (val current = shot) {
            // A live region: encoding can still drop the screenshot, which removes the checkbox.
            is ReportShot.Omitted -> Text(
                stringResource(omissionMessage(current.reason)),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            else -> {
                // One accessibility node per row: TalkBack reads the label with the checkbox state.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TARGET)
                        .toggleable(value = includeScreenshot, role = Role.Checkbox) { includeScreenshot = it },
                ) {
                    Checkbox(checked = includeScreenshot, onCheckedChange = null)
                    Text(stringResource(R.string.report_include_screenshot), modifier = Modifier.padding(start = 8.dp))
                }
                val preview = when (current) {
                    is ReportShot.Pending -> current.preview
                    is ReportShot.Ready -> current.preview
                    else -> null
                }
                preview?.let {
                    Image(
                        bitmap = it,
                        contentDescription = stringResource(R.string.report_screenshot_description),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(max = PREVIEW_MAX_HEIGHT)
                            .alpha(if (includeScreenshot) 1f else UNTICKED_ALPHA),
                    )
                }
            }
        }

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.report_address)) },
            isError = addressProblem != null,
            supportingText = addressProblem?.let { problem -> { Text(stringResource(addressMessage(problem))) } },
            maxLines = ADDRESS_MAX_LINES,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        if (starting != snapshot.url && address == starting) {
            Text(stringResource(R.string.report_address_trimmed), style = MaterialTheme.typography.bodySmall)
        }
        if (sensitive) Text(stringResource(R.string.report_address_sensitive), color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (trimmed != address) {
                TextButton(onClick = { address = trimmed }, modifier = Modifier.heightIn(min = MIN_TARGET)) {
                    Text(stringResource(R.string.report_address_trim))
                }
            }
            if (address != snapshot.url) {
                TextButton(onClick = { address = snapshot.url }, modifier = Modifier.heightIn(min = MIN_TARGET)) {
                    Text(stringResource(R.string.report_address_restore))
                }
            }
        }

        // Editable: a title can hold personal data, such as a mail's subject. Empty sends none.
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.takeCodePoints(SiteReports.MAX_TITLE_CHARS) },
            label = { Text(stringResource(R.string.report_page_title)) },
            maxLines = TITLE_MAX_LINES,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = comment,
            onValueChange = { comment = it.takeCodePoints(SiteReports.MAX_COMMENT_CHARS) },
            label = { Text(stringResource(R.string.report_comment)) },
            minLines = COMMENT_MIN_LINES,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(stringResource(R.string.report_details_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        ReportDetails(snapshot, environment)

        progress.problem?.let { problem ->
            Text(
                stringResource(problem.message),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (problem.offersSettings) {
                TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = MIN_TARGET)) { Text(stringResource(R.string.action_open_settings)) }
            }
        }
        if (encoding) {
            Text(stringResource(R.string.report_screenshot_preparing), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = MIN_TARGET)) { Text(stringResource(R.string.action_cancel)) }
            Button(
                onClick = send,
                enabled = !progress.sending && addressProblem == null && !encoding,
                modifier = Modifier.heightIn(min = MIN_TARGET),
            ) { Text(stringResource(R.string.action_send)) }
        }
    }
}

/** The versions and display values the report sends, read only: the preview shows all of it. */
@Composable
private fun ReportDetails(snapshot: ReportSnapshot, environment: ReportEnvironment) {
    Text(
        stringResource(
            R.string.report_details_versions,
            environment.appVersion,
            environment.buildType,
            environment.engineVersion,
            environment.engineRevision.take(SHORT_REVISION),
            environment.androidRelease,
            environment.androidSdk,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(stringResource(R.string.report_details_display, snapshot.zoomPercent, snapshot.density.toString()), style = MaterialTheme.typography.bodySmall)
    if (snapshot.loadFailed) Text(stringResource(R.string.report_details_load_failed), style = MaterialTheme.typography.bodySmall)
    if (snapshot.crashed) Text(stringResource(R.string.report_details_crashed), style = MaterialTheme.typography.bodySmall)
}

@StringRes
private fun omissionMessage(reason: ScreenshotOmission): Int = when (reason) {
    ScreenshotOmission.USER -> R.string.report_no_screenshot_user
    ScreenshotOmission.READER_VIEW -> R.string.report_no_screenshot_reader
    ScreenshotOmission.NOT_SHOWN -> R.string.report_no_screenshot_not_shown
    ScreenshotOmission.PAGE_CHANGED -> R.string.report_no_screenshot_page_changed
    ScreenshotOmission.COPY_FAILED -> R.string.report_no_screenshot_copy_failed
    ScreenshotOmission.TOO_LARGE -> R.string.report_no_screenshot_too_large
}

@StringRes
private fun addressMessage(problem: ReportAddressProblem): Int = when (problem) {
    ReportAddressProblem.BLANK -> R.string.report_address_blank
    ReportAddressProblem.TOO_LONG -> R.string.report_address_too_long
    ReportAddressProblem.CONTROL_CHARACTERS -> R.string.report_address_control
}

/** At most [count] code points: a cut never splits a surrogate pair. */
private fun String.takeCodePoints(count: Int): String =
    if (codePointCount(0, length) <= count) this else substring(0, offsetByCodePoints(0, count))

private val MIN_TARGET = 48.dp
private val PREVIEW_MAX_HEIGHT = 240.dp
private const val UNTICKED_ALPHA = 0.38f
private const val ADDRESS_MAX_LINES = 4
private const val TITLE_MAX_LINES = 3
private const val COMMENT_MIN_LINES = 3

/** As Git abbreviates: enough to recognise the engine pin; the report carries all of it. */
private const val SHORT_REVISION = 7
