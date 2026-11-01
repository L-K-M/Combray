package ch.lkmc.combray.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.R

/** Show how far to pull without moving the native rendering surface. */
@Composable
internal fun ReloadPullIndicator(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0f) return
    val description = stringResource(if (progress < 1f) R.string.pull_to_reload else R.string.release_to_reload)

    Surface(modifier = modifier, shape = CircleShape, shadowElevation = 4.dp) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.padding(8.dp).size(24.dp).semantics { contentDescription = description },
            strokeWidth = 3.dp,
        )
    }
}
