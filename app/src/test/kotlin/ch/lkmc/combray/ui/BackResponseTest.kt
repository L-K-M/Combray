package ch.lkmc.combray.ui

import ch.lkmc.combray.engine.ControlResponse
import ch.lkmc.combray.engine.LeavePageChoice
import ch.lkmc.combray.engine.PageControlContent
import ch.lkmc.combray.engine.SelectEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** System Back answers an open page question instead of navigating the page behind it. */
class BackResponseTest {
    @Test
    fun aLeavePageQuestionIsAnsweredStay() {
        assertEquals(LeavePageChoice.STAY.response, backResponse(PageControlContent.LeavePage))
    }

    @Test
    fun aScriptDialogIsAnsweredWithTheDefault() {
        assertEquals(ControlResponse.Dismiss, backResponse(PageControlContent.Alert("message")))
        assertEquals(ControlResponse.Dismiss, backResponse(PageControlContent.Confirm("message")))
        assertEquals(ControlResponse.Dismiss, backResponse(PageControlContent.Prompt("message", "default")))
    }

    @Test
    fun aSelectIsAnsweredWithTheDefault() {
        val content = PageControlContent.Select(listOf(SelectEntry(1, "one", disabled = false, group = null)), listOf(1), multiple = false)
        assertEquals(ControlResponse.Dismiss, backResponse(content))
    }
}
