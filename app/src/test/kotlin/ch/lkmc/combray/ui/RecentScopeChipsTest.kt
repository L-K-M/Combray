package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scope chips wrap inside the row, so Open tabs, All, Hister and Karakeep all stay within
 * the sheet's width instead of scrolling off with no cue. The row is deliberately narrower than
 * the four chips so a single-line scroller would run past it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class RecentScopeChipsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun chipsWrapInsideTheRow() {
        var rowWidth = 0
        compose.setContent {
            Box(Modifier.width(100.dp).onGloballyPositioned { rowWidth = it.size.width }) {
                RecentScopeChips(selected = RecentScope.ALL, onSelect = {})
            }
        }

        for (label in listOf("Open tabs", "All", "Hister", "Karakeep")) {
            val bounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            // A chip scrolled out of a single-line row has no laid-out bounds.
            assertTrue("$label has no laid-out bounds", bounds.width > 0f && bounds.height > 0f)
            assertTrue("$label runs past the row: ${bounds.right} > $rowWidth", bounds.right <= rowWidth + 1f)
        }
    }
}
