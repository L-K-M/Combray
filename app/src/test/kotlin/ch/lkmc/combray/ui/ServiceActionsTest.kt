package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A service's Save settings / Check connection row: the actions wrap to another line instead of
 * leaving a too-narrow row, so both stay reachable at any font scale. The exact 200%-font
 * overflow at 320dp cannot be measured here (Robolectric measures text near-zero width), so the
 * test pins the wrapping mechanism in a constrained row; the font-scale case is manual.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h640dp")
class ServiceActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theActionsWrapInsteadOfLeavingARowTooNarrowForThem() {
        compose.setContent {
            CombrayTheme {
                Box(Modifier.width(100.dp)) {
                    ServiceActions(saveEnabled = true, checkEnabled = true, onSave = {}, onCheck = {})
                }
            }
        }
        compose.waitForIdle()
        val limit = with(compose.density) { 100.dp.toPx() }
        val targets = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals(2, targets.size)
        val (first, second) = targets.map { it.boundsInRoot }
        assertTrue("first action at $first leaves the row", first.left >= 0f && first.right <= limit)
        assertTrue("second action at $second leaves the row", second.left >= 0f && second.right <= limit)
        assertTrue("second action at $second must wrap below the first at $first", second.top > first.top)
    }
}
