package ch.lkmc.combray.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ch.lkmc.combray.R
import ch.lkmc.combray.engine.ScrollEdge

/** A full-size touch target above the bottom bar, shared by the page and reader view. */
@Composable
internal fun ScrollEdgeButton(edge: ScrollEdge, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = if (edge == ScrollEdge.TOP) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(if (edge == ScrollEdge.TOP) R.string.action_scroll_top else R.string.action_scroll_bottom),
        )
    }
}
