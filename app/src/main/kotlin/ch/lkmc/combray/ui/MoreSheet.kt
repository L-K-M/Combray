package ch.lkmc.combray.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.ActiveTab

/** Less frequent actions. Stop is missing: Servo 0.6.0 has no API to stop a load. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSheet(
    tab: ActiveTab?,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onCloseTab: () -> Unit,
    onPageInfo: () -> Unit,
    onSettings: () -> Unit,
    readerOpen: Boolean,
    onReader: () -> Unit,
    onTextSize: () -> Unit,
    onFind: () -> Unit,
    onPlayMedia: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        MoreActions(tab, onForward, onReload, onCloseTab, onPageInfo, onSettings, readerOpen, onReader, onTextSize, onFind, onPlayMedia)
    }
}

/** The sheet's rows, apart from the sheet so a device test can check them without its window. */
@Composable
internal fun MoreActions(
    tab: ActiveTab?,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onCloseTab: () -> Unit,
    onPageInfo: () -> Unit,
    onSettings: () -> Unit,
    /** Reader view is open over the page: the row leaves it. */
    readerOpen: Boolean,
    onReader: () -> Unit,
    onTextSize: () -> Unit,
    onFind: () -> Unit,
    /** The page's cancelled audio or video ([ActiveTab.media]): the player, or a choice of several. */
    onPlayMedia: () -> Unit,
) {
    // Scrolls when the rows are taller than the window, as in landscape, so Settings stays reachable.
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        for (action in moreActions(tab, readerOpen)) {
            when (action) {
                MoreAction.FORWARD -> MoreRow(Icons.AutoMirrored.Filled.ArrowForward, R.string.action_forward, onForward)
                MoreAction.RELOAD -> MoreRow(Icons.Filled.Refresh, R.string.action_reload, onReload)
                MoreAction.CLOSE_TAB -> MoreRow(Icons.Filled.Close, R.string.action_close_tab, onCloseTab)
                MoreAction.PAGE_INFO -> MoreRow(Icons.Outlined.Info, R.string.action_page_info, onPageInfo)
                MoreAction.READER ->
                    MoreRow(ReaderIcon, if (readerOpen) R.string.action_leave_reader_view else R.string.action_reader_view, onReader)
                MoreAction.FIND -> MoreRow(Icons.Filled.Search, R.string.action_find_in_page, onFind)
                MoreAction.PLAY_MEDIA -> MoreRow(Icons.Filled.PlayArrow, R.string.action_play_media, onPlayMedia)
                MoreAction.TEXT_SIZE -> MoreRow(TextSizeIcon, R.string.action_text_size, onTextSize)
                MoreAction.SETTINGS -> MoreRow(Icons.Filled.Settings, R.string.action_settings, onSettings)
            }
        }
    }
}

/** A row of the More sheet. */
internal enum class MoreAction { FORWARD, RELOAD, CLOSE_TAB, PAGE_INFO, READER, FIND, PLAY_MEDIA, TEXT_SIZE, SETTINGS }

/** The rows More offers for [tab], in order. */
internal fun moreActions(tab: ActiveTab?, readerOpen: Boolean): List<MoreAction> = buildList {
    val onPage = tab?.url != null
    if (tab?.canGoForward == true) add(MoreAction.FORWARD)
    if (onPage) add(MoreAction.RELOAD)
    // New tab is the bottom bar's +, not a More row (owner requirement, 24 October 2026).
    // A new-tab home is a tab too; the Undo notice brings a closed tab back.
    if (tab != null) add(MoreAction.CLOSE_TAB)
    // Share is in the bottom bar.
    if (onPage) {
        add(MoreAction.PAGE_INFO)
        add(MoreAction.READER)
        // Find searches the page itself, which reader view covers.
        if (!readerOpen) add(MoreAction.FIND)
    }
    // The page notice's Play, once the notice has gone or for a tab that was hidden when its media loaded.
    if (tab?.media?.isNotEmpty() == true) add(MoreAction.PLAY_MEDIA)
    // Every page, not just this one: offered on the home screen too.
    add(MoreAction.TEXT_SIZE)
    // Hister capture is switched in Settings (owner decision, 23 October 2026).
    add(MoreAction.SETTINGS)
}

@Composable
private fun MoreRow(icon: ImageVector, label: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = sheetListItemColors(),
    )
}
