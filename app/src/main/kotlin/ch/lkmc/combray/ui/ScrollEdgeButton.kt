package ch.lkmc.combray.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.R
import ch.lkmc.combray.engine.ScrollEdge
import kotlinx.coroutines.delay

/** Space between a scroll button and the page's edges. */
private val SCROLL_BUTTON_MARGIN = 16.dp
private const val SCROLL_BUTTON_IDLE_MS = 2_000L
private const val SCROLL_BUTTON_FADE_MS = 200

/**
 * Where a scroll button sits in the page's bottom-left corner. [snackbarReach] is how far up from
 * the page's bottom edge a shown snackbar reaches (zero or less without one). Snackbars show over
 * that corner and Material's Surface takes the touches under it, so the button moves above one,
 * as a Scaffold's floating action button does, instead of being hidden there.
 */
internal fun scrollButtonPadding(snackbarReach: Dp = 0.dp): PaddingValues = PaddingValues(
    start = SCROLL_BUTTON_MARGIN,
    top = SCROLL_BUTTON_MARGIN,
    end = SCROLL_BUTTON_MARGIN,
    bottom = maxOf(SCROLL_BUTTON_MARGIN, snackbarReach),
)

/** [scrollActivity] advances on every scroll movement, even when [edge] stays the same. */
@Composable
internal fun ScrollEdgeButton(edge: ScrollEdge, onClick: () -> Unit, modifier: Modifier = Modifier, scrollActivity: Int = 0) {
    var visible by remember(scrollActivity) { mutableStateOf(true) }
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        SCROLL_BUTTON_IDLE_MS, containsIcons = true, containsControls = true,
    ) ?: SCROLL_BUTTON_IDLE_MS

    LaunchedEffect(scrollActivity, timeout) {
        delay(timeout)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(SCROLL_BUTTON_FADE_MS)),
        exit = fadeOut(tween(SCROLL_BUTTON_FADE_MS)),
    ) {
        FloatingActionButton(onClick = onClick) {
            Icon(
                imageVector = if (edge == ScrollEdge.TOP) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(if (edge == ScrollEdge.TOP) R.string.action_scroll_top else R.string.action_scroll_bottom),
            )
        }
    }
}
