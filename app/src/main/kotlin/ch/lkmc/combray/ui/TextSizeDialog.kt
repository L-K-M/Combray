package ch.lkmc.combray.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.TextSize

/**
 * More › Text size: steps the size of every page with − and +. Each step applies at once, so the
 * page behind the dialog shows the result; Reset returns to the pages' own size.
 */
@Composable
fun TextSizeDialog(size: TextSize, onChange: (TextSize) -> Unit, onDismiss: () -> Unit) {
    val smaller = size.smaller()
    val larger = size.larger()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        dismissButton = {
            TextButton(onClick = { onChange(TextSize.DEFAULT) }, enabled = size != TextSize.DEFAULT) {
                Text(stringResource(R.string.text_size_reset))
            }
        },
        title = { Text(stringResource(R.string.action_text_size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(onClick = { smaller?.let(onChange) }, enabled = smaller != null) {
                        Icon(MinusIcon, contentDescription = stringResource(R.string.text_size_smaller))
                    }
                    // TalkBack reads the new size after each step.
                    Text(
                        stringResource(R.string.text_size_percent, size.percent),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    FilledTonalIconButton(onClick = { larger?.let(onChange) }, enabled = larger != null) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.text_size_larger))
                    }
                }
                Text(stringResource(R.string.text_size_explanation), style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}

/** Material's "format_size" icon (Apache License 2.0, like the icon library), absent from the core set. */
internal val TextSizeIcon: ImageVector = materialIcon("FormatSize", "M9 4v3h5v12h3V7h5V4H9zm-6 8h3v7h3v-7h3V9H3v3z")

/** Material's "remove" icon (a minus sign), absent from the core set. */
private val MinusIcon: ImageVector = materialIcon("Remove", "M19 13H5v-2h14v2z")

private fun materialIcon(name: String, path: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
    .build()
