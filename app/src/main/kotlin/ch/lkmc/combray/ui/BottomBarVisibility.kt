package ch.lkmc.combray.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import ch.lkmc.combray.engine.ScrollEdge

internal enum class BottomBarBehavior { PINNED, SCROLLING }

internal fun bottomBarVisible(direction: ScrollEdge?, behavior: BottomBarBehavior): Boolean =
    behavior == BottomBarBehavior.PINNED || direction != ScrollEdge.BOTTOM

@Composable
internal fun rememberAccessibleControlsRequired(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var required by remember(manager) { mutableStateOf(manager?.requiresControls == true) }

    DisposableEffect(manager) {
        val update = { required = manager?.requiresControls == true }
        val stateListener = AccessibilityManager.AccessibilityStateChangeListener { update() }
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener { update() }
        manager?.addAccessibilityStateChangeListener(stateListener)
        manager?.addTouchExplorationStateChangeListener(touchListener)

        onDispose {
            manager?.removeAccessibilityStateChangeListener(stateListener)
            manager?.removeTouchExplorationStateChangeListener(touchListener)
        }
    }
    return required
}

// isEnabled also includes test automation, which does not need pinned controls.
private val AccessibilityManager.requiresControls: Boolean
    get() = isTouchExplorationEnabled ||
        getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).isNotEmpty()
