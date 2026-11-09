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

/**
 * A viewport request sent to the engine: the bottom [inset] pixels of its surface are covered.
 * [id] is null when no engine ran to take it.
 */
internal data class ViewportRequest(val id: Long?, val inset: Int)

/**
 * Whether a bar the user's scroll hid stays drawn: until the engine has painted the page in the
 * pixels the bar covered, where it had laid out nothing, so taking the bar away never shows
 * pixels the engine has not drawn. [sent] is the latest request (it asks for an uncovered
 * surface once the hide has reached the engine), [painted] the latest request the engine
 * answered, and [expired] ends the wait whatever the engine does.
 */
internal fun bottomBarHeld(sent: ViewportRequest?, painted: Long?, expired: Boolean): Boolean {
    if (expired || sent == null) return false
    // The hide has not reached the engine yet: the request still covers the bar's pixels.
    if (sent.inset != 0) return true
    val id = sent.id ?: return false
    return painted == null || painted < id
}

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
