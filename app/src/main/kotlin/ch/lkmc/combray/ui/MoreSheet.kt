package ch.lkmc.combray.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
    onNewTab: () -> Unit,
    onPageInfo: () -> Unit,
    onSettings: () -> Unit,
    /** Null when Hister is not set up; otherwise whether capture is on. */
    captureEnabled: Boolean?,
    onPauseCapture: () -> Unit,
    readerOpen: Boolean,
    onReader: () -> Unit,
    onTextSize: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        MoreActions(tab, onForward, onReload, onNewTab, onPageInfo, onSettings, captureEnabled, onPauseCapture, readerOpen, onReader, onTextSize)
    }
}

/** The sheet's rows, apart from the sheet so a device test can check them without its window. */
@Composable
internal fun MoreActions(
    tab: ActiveTab?,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onNewTab: () -> Unit,
    onPageInfo: () -> Unit,
    onSettings: () -> Unit,
    captureEnabled: Boolean?,
    onPauseCapture: () -> Unit,
    /** Reader view is open over the page: the row leaves it. */
    readerOpen: Boolean,
    onReader: () -> Unit,
    onTextSize: () -> Unit,
) {
    val url = tab?.url
    Column(Modifier.padding(bottom = 16.dp)) {
        if (tab?.canGoForward == true) MoreRow(Icons.AutoMirrored.Filled.ArrowForward, R.string.action_forward, onForward)
        if (url != null) MoreRow(Icons.Filled.Refresh, R.string.action_reload, onReload)
        MoreRow(Icons.Filled.Add, R.string.action_new_tab, onNewTab)
        // Share is in the bottom bar.
        if (url != null) {
            MoreRow(Icons.Outlined.Info, R.string.action_page_info, onPageInfo)
            MoreRow(ReaderIcon, if (readerOpen) R.string.action_leave_reader_view else R.string.action_reader_view, onReader)
        }
        // Every page, not just this one: offered on the home screen too.
        MoreRow(TextSizeIcon, R.string.action_text_size, onTextSize)
        // Pausing is one tap; switching capture on goes through Settings, which explains it.
        when (captureEnabled) {
            true -> MoreRow(Icons.Filled.Close, R.string.action_pause_capture, onPauseCapture)
            false -> MoreRow(Icons.Filled.PlayArrow, R.string.action_resume_capture, onSettings)
            null -> Unit
        }
        MoreRow(Icons.Filled.Settings, R.string.action_settings, onSettings)
    }
}

@Composable
private fun MoreRow(icon: ImageVector, label: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
