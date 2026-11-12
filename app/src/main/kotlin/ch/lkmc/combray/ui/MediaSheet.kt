package ch.lkmc.combray.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.PlayableMedia
import ch.lkmc.combray.engine.MediaKind

/** The page's media, newest first: play a row or download its video. Back dismisses it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSheet(media: List<PlayableMedia>, onPlay: (PlayableMedia) -> Unit, onDownload: (PlayableMedia) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        MediaEntries(media, onPlay, onDownload)
    }
}

/** The sheet's rows, apart from the sheet so a device test can check them without its window. */
@Composable
internal fun MediaEntries(media: List<PlayableMedia>, onPlay: (PlayableMedia) -> Unit, onDownload: (PlayableMedia) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Text(
            stringResource(R.string.media_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        )
        for (entry in media.take(MAX_MEDIA_ROWS)) {
            val video = entry.kind == MediaKind.VIDEO
            val label = stringResource(if (video) R.string.media_entry_label_video else R.string.media_entry_label_audio, entry.name, entry.site)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ListItem(
                    headlineContent = { Text(stringResource(if (video) R.string.media_entry_video else R.string.media_entry_audio, entry.name)) },
                    supportingContent = { Text(entry.site) },
                    leadingContent = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    modifier = Modifier.weight(1f).clickable { onPlay(entry) }.semantics { contentDescription = label },
                    colors = sheetListItemColors(),
                )
                if (video) {
                    IconButton(onClick = { onDownload(entry) }, modifier = Modifier.padding(end = 8.dp).size(48.dp)) {
                        Icon(DownloadIcon, contentDescription = stringResource(R.string.video_download_entry, entry.name, entry.site))
                    }
                }
            }
        }
    }
}

/** As many as the coordinator keeps per page. */
private const val MAX_MEDIA_ROWS = 8
