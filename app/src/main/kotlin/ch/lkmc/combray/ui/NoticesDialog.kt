package ch.lkmc.combray.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.lkmc.combray.Notice
import ch.lkmc.combray.Notices
import ch.lkmc.combray.R
import ch.lkmc.combray.ThirdPartyNotices

/** What the notices dialog shows while the bundled lists load. */
private sealed interface NoticesUi {
    data object Loading : NoticesUi
    data object Unavailable : NoticesUi
    data class Loaded(val notices: Notices) : NoticesUi
}

/** Every third-party component in the APK; tapping one shows its license texts. */
@Composable
fun NoticesDialog(source: ThirdPartyNotices, onDismiss: () -> Unit) {
    val ui by produceState<NoticesUi>(NoticesUi.Loading, source) {
        value = source.load()?.let { NoticesUi.Loaded(it) } ?: NoticesUi.Unavailable
    }
    // An index survives rotation; the list is the same after reloading.
    var opened by rememberSaveable { mutableStateOf<Int?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Inside the dialog's window: Back returns from a license to the list, then closes.
        BackHandler(enabled = opened != null) { opened = null }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                val loaded = (ui as? NoticesUi.Loaded)?.notices
                val notice = opened?.let { loaded?.components?.getOrNull(it) }
                Header(
                    title = notice?.let { label(it) } ?: stringResource(R.string.notices_title),
                    onBack = notice?.let { { opened = null } },
                    onClose = onDismiss,
                )
                when {
                    ui == NoticesUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    loaded == null -> Text(stringResource(R.string.notices_unavailable), Modifier.padding(24.dp))
                    notice != null -> LicenseTexts(notice, loaded)
                    else -> NoticeList(loaded) { opened = it }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, onBack: (() -> Unit)?, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
        }
    }
}

@Composable
private fun NoticeList(notices: Notices, onOpen: (Int) -> Unit) {
    LazyColumn {
        item { Text(stringResource(R.string.notices_intro), Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        items(notices.components.size) { index ->
            val notice = notices.components[index]
            ListItem(
                headlineContent = { Text(label(notice)) },
                supportingContent = { Text(notice.license) },
                modifier = Modifier.clickable { onOpen(index) },
            )
        }
    }
}

@Composable
private fun LicenseTexts(notice: Notice, notices: Notices) {
    // Texts run to tens of kilobytes (the MPL): one scrolling column, selectable for copying.
    SelectionContainer {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(notice.license, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 12.dp))
            for (index in notice.texts) {
                Text(notices.texts[index], fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun label(notice: Notice) = listOfNotNull(notice.name, notice.version).joinToString(" ")
