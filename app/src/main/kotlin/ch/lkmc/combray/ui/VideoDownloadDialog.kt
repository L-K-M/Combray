package ch.lkmc.combray.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.R
import ch.lkmc.combray.download.VideoDownloadFailure
import ch.lkmc.combray.download.VideoDownloadPhase
import ch.lkmc.combray.download.VideoDownloadState

@Composable
internal fun VideoDownloadDialog(state: VideoDownloadState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val working = state.phase == VideoDownloadPhase.DOWNLOADING
    val saving = state.phase == VideoDownloadPhase.SAVING
    val close = { if (working) onCancel() else if (!saving) onDismiss() }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.video_download_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val message = when (state.phase) {
                    VideoDownloadPhase.DOWNLOADING -> R.string.video_download_working
                    VideoDownloadPhase.SAVING -> R.string.video_download_saving
                    VideoDownloadPhase.SAVED -> R.string.video_download_saved
                    VideoDownloadPhase.FAILED -> when (state.failure) {
                        VideoDownloadFailure.LIVE -> R.string.video_download_live
                        VideoDownloadFailure.TOO_LARGE -> R.string.video_download_large
                        VideoDownloadFailure.TIMEOUT -> R.string.video_download_timeout
                        else -> R.string.video_download_failed
                    }
                }
                Text(stringResource(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (working || saving) {
                    val progress = state.progress
                    if (progress != null) LinearProgressIndicator(progress = { progress / 100f })
                    else LinearProgressIndicator()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = close, enabled = !saving) {
                Text(stringResource(if (working || saving) R.string.action_cancel else R.string.action_done))
            }
        },
    )
}
