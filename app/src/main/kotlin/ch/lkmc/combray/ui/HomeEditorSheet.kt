package ch.lkmc.combray.ui

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.BrowserSettings
import ch.lkmc.combray.core.browser.HomeLink
import ch.lkmc.combray.core.browser.HomeLinks
import ch.lkmc.combray.core.browser.HomeLinksState
import ch.lkmc.combray.core.browser.HomePin
import ch.lkmc.combray.core.browser.HomePinResult
import ch.lkmc.combray.core.browser.HomeSectionVisibility
import ch.lkmc.combray.core.browser.PinDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class HomeEditPage { OVERVIEW, LINK, REPLACE }
private const val MAX_PIN_NAME = 200
private const val MAX_PIN_ADDRESS = 8192
private val HOME_EDITOR_ACTION_TARGET = 48.dp

/** One editor for pins, hidden sites and visibility; changes pass through application services. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeEditorSheet(
    home: HomeLinks,
    states: Flow<HomeLinksState>,
    settings: BrowserSettings,
    initialLink: HomeLink?,
    initialPage: HomeEditPage,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Observe and edit state in the composition of the window that displays it.
        val state by states.collectAsStateWithLifecycle(HomeLinksState(emptyList(), emptyList(), emptyList()))
        val continuation by settings.homeContinue.collectAsStateWithLifecycle()
        val frequent by settings.homeFrequentSites.collectAsStateWithLifecycle()
        var page by rememberSaveable { mutableStateOf(initialPage) }
        var id by rememberSaveable { mutableStateOf(initialLink?.pinId) }
        var address by rememberSaveable { mutableStateOf(initialLink?.url.orEmpty()) }
        var name by rememberSaveable { mutableStateOf(initialLink?.let { it.title ?: displayHost(it.url) }.orEmpty()) }
        var error by remember { mutableStateOf<Int?>(null) }
        var saving by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val change: (suspend () -> Unit) -> Unit = { action ->
            // An accepted local edit finishes even if its window is dismissed or recreated.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                error = null
                try {
                    withContext(NonCancellable) { action() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = R.string.home_change_failed
                }
            }
        }
        val save: (String?) -> Unit = { replace ->
            if (!saving) {
                saving = true
                val requestedId = id
                val requestedAddress = address
                val requestedName = name
                change {
                    try {
                        when (home.savePin(requestedId, requestedAddress, requestedName, replace)) {
                            HomePinResult.SAVED -> { page = HomeEditPage.OVERVIEW; error = null }
                            HomePinResult.INVALID_ADDRESS -> { page = HomeEditPage.LINK; error = R.string.home_invalid_address }
                            HomePinResult.SENSITIVE_ADDRESS -> { page = HomeEditPage.LINK; error = R.string.home_sensitive_address }
                            HomePinResult.FULL -> page = HomeEditPage.REPLACE
                            HomePinResult.NOT_FOUND -> { page = HomeEditPage.LINK; error = R.string.home_link_missing }
                        }
                    } finally {
                        saving = false
                    }
                }
            }
        }
        val edit: (HomeLink?) -> Unit = { link ->
            id = link?.pinId
            address = link?.url.orEmpty()
            name = link?.let { it.title ?: displayHost(it.url) }.orEmpty()
            error = null
            page = HomeEditPage.LINK
        }

        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(when (page) {
                        HomeEditPage.OVERVIEW -> R.string.home_edit_title
                        HomeEditPage.LINK -> if (id == null) R.string.home_add_link else R.string.home_edit_link
                        HomeEditPage.REPLACE -> R.string.home_replace_title
                    }),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                when (page) {
                    HomeEditPage.OVERVIEW -> {
                        HomeVisibilityRow(R.string.home_show_continue, continuation, settings::setHomeContinue)
                        HomeVisibilityRow(R.string.home_show_frequent, frequent, settings::setHomeFrequentSites)
                        HorizontalDivider()
                        HomeSectionTitle(R.string.home_pinned)
                        TextButton(onClick = { edit(null) }, modifier = Modifier.heightIn(min = HOME_EDITOR_ACTION_TARGET)) { Text(stringResource(R.string.home_add_link)) }
                        if (state.pins.isEmpty()) Text(stringResource(R.string.home_no_pins), style = MaterialTheme.typography.bodyMedium)
                        state.pins.forEachIndexed { index, pin ->
                            key(pin.id) {
                                HomePinRow(
                                    pin = pin,
                                    index = index,
                                    lastIndex = state.pins.lastIndex,
                                    onEdit = { edit(HomeLink(pin.id, "", pin.url, pin.title, pin.id)) },
                                    onMove = { direction -> change { home.movePin(pin.id, direction) } },
                                    onUnpin = { change { home.unpin(pin.id) } },
                                )
                            }
                        }
                        val automatic = state.links.filter { it.pinId == null }
                        if (automatic.isNotEmpty()) {
                            HorizontalDivider()
                            HomeSectionTitle(R.string.home_edit_frequent)
                            automatic.forEach { link ->
                                Column {
                                    Text(displayHost(link.url), style = MaterialTheme.typography.titleMedium)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { edit(link) }, modifier = Modifier.heightIn(min = HOME_EDITOR_ACTION_TARGET)) { Text(stringResource(R.string.home_pin)) }
                                        TextButton(onClick = { change { home.hideSite(link.site) } }, modifier = Modifier.heightIn(min = HOME_EDITOR_ACTION_TARGET)) {
                                            Text(stringResource(R.string.home_hide_site))
                                        }
                                    }
                                }
                            }
                        }
                        if (state.hiddenSites.isNotEmpty()) {
                            HorizontalDivider()
                            HomeSectionTitle(R.string.home_hidden_sites)
                            state.hiddenSites.forEach { site ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(displayHost("https://$site"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                    val label = stringResource(R.string.home_restore_site, site)
                                    TextButton(
                                        onClick = { change { home.restoreSite(site) } },
                                        modifier = Modifier.heightIn(min = HOME_EDITOR_ACTION_TARGET).semantics { contentDescription = label },
                                    ) { Text(stringResource(R.string.home_restore)) }
                                }
                            }
                        }
                    }
                    HomeEditPage.LINK -> {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(MAX_PIN_NAME) },
                            label = { Text(stringResource(R.string.home_link_name)) },
                            singleLine = true,
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it.take(MAX_PIN_ADDRESS); error = null },
                            label = { Text(stringResource(R.string.home_link_address)) },
                            singleLine = true,
                            enabled = !saving,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onDone = { save(null) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    HomeEditPage.REPLACE -> {
                        Text(stringResource(R.string.home_replace_body))
                        state.pins.forEach { pin ->
                            TextButton(onClick = { save(pin.id) }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = HOME_EDITOR_ACTION_TARGET)) {
                                Text(stringResource(R.string.home_replace_named, pin.title))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { if (page == HomeEditPage.OVERVIEW) onDismiss() else { page = HomeEditPage.OVERVIEW; error = null } },
                    enabled = !saving,
                    modifier = Modifier.heightIn(min = HOME_EDITOR_ACTION_TARGET),
                ) { Text(stringResource(if (page == HomeEditPage.OVERVIEW) R.string.action_done else R.string.action_cancel)) }
                if (page == HomeEditPage.LINK) Button(onClick = { save(null) }, enabled = !saving, modifier = Modifier.heightIn(min = HOME_EDITOR_ACTION_TARGET)) {
                    Text(stringResource(R.string.home_save_link))
                }
            }
        }
    }
}

@Composable
private fun HomeSectionTitle(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun HomeVisibilityRow(label: Int, value: HomeSectionVisibility, onChange: (HomeSectionVisibility) -> Unit) {
    val checked = value == HomeSectionVisibility.SHOWN
    Row(
        Modifier.fillMaxWidth().heightIn(min = HOME_EDITOR_ACTION_TARGET)
            .toggleable(checked, role = Role.Switch, onValueChange = { onChange(if (it) HomeSectionVisibility.SHOWN else HomeSectionVisibility.HIDDEN) }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun HomePinRow(pin: HomePin, index: Int, lastIndex: Int, onEdit: () -> Unit, onMove: (PinDirection) -> Unit, onUnpin: () -> Unit) {
    Column {
        val edit = stringResource(R.string.home_edit_named, pin.title)
        TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = HOME_EDITOR_ACTION_TARGET).semantics { contentDescription = edit }) {
            Column(Modifier.fillMaxWidth()) {
                Text(pin.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(displayHost(pin.url), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMove(PinDirection.EARLIER) }, enabled = index > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.home_move_earlier, pin.title))
            }
            IconButton(onClick = { onMove(PinDirection.LATER) }, enabled = index < lastIndex) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.home_move_later, pin.title))
            }
            val unpin = stringResource(R.string.home_unpin_named, pin.title)
            TextButton(onClick = onUnpin, modifier = Modifier.heightIn(min = HOME_EDITOR_ACTION_TARGET).semantics { contentDescription = unpin }) {
                Text(stringResource(R.string.home_unpin))
            }
        }
    }
}
