package ch.lkmc.combray.ui

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollButtonPaddingTest {
    @Test
    fun withoutASnackbarTheButtonKeepsItsCornerMargin() {
        val padding = scrollButtonPadding()

        assertEquals(16.dp, padding.calculateBottomPadding())
        assertEquals(16.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(16.dp, padding.calculateLeftPadding(LayoutDirection.Rtl))
    }

    @Test
    fun aShownSnackbarLiftsTheButtonAboveIt() {
        // Material's one-line snackbar: 48dp, with 12dp around it in the host, 8dp above the page's edge.
        val reach = 80.dp
        val padding = scrollButtonPadding(reach)

        // The button's bottom edge clears the snackbar's top edge: no part of it is under the
        // snackbar, which would hide it and take its taps.
        assertTrue(padding.calculateBottomPadding() >= reach)
        assertEquals(16.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
    }

    @Test
    fun aSnackbarBelowTheButtonLeavesItInPlace() {
        assertEquals(16.dp, scrollButtonPadding(8.dp).calculateBottomPadding())
        assertEquals(16.dp, scrollButtonPadding((-40).dp).calculateBottomPadding())
    }
}
