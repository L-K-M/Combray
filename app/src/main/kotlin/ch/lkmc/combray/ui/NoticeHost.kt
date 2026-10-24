package ch.lkmc.combray.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Browser and Recent notices follow the app's appearance, including their action labels. */
@Composable
internal fun NoticeHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        val colors = MaterialTheme.colorScheme
        Snackbar(
            snackbarData = data,
            containerColor = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
            actionColor = colors.primary,
            dismissActionContentColor = colors.onSurfaceVariant,
        )
    }
}
