package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHostState
import ch.lkmc.combray.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A report whose sheet closed before it was stored: the draft went with the sheet, so the
 * snackbar cannot ask the user to try again, and offers Settings where Settings can help.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ReportLostTest {
    private val resources = RuntimeEnvironment.getApplication().resources
    private val snackbar = SnackbarHostState()
    private var settingsOpened = 0

    @Test
    fun tooManyReportsOffersSettings() {
        val shown = showing(ReportProblem.TOO_MANY) { it.performAction() }
        assertEquals("The report was not stored because too many reports are waiting to be sent.", shown.visuals.message)
        assertEquals(resources.getString(R.string.action_open_settings), shown.visuals.actionLabel)
        assertEquals(1, settingsOpened)
    }

    @Test
    fun aMissingServiceOffersSettings() {
        val shown = showing(ReportProblem.NOT_SET_UP) { it.performAction() }
        assertEquals("The report was not stored because your report service is not set up.", shown.visuals.message)
        assertEquals(resources.getString(R.string.action_open_settings), shown.visuals.actionLabel)
        assertEquals(1, settingsOpened)
    }

    @Test
    fun anyOtherProblemSaysToReportAgain() {
        for (problem in ReportProblem.entries.filterNot { it.offersSettings }) {
            val shown = showing(problem) { it.dismiss() }
            assertEquals(problem.name, "The report was not stored. Report the page again.", shown.visuals.message)
            assertNull(problem.name, shown.visuals.actionLabel)
        }
    }

    private fun showing(problem: ReportProblem, answer: (SnackbarData) -> Unit): SnackbarData = runBlocking {
        val job = launch { reportLost(problem, snackbar, resources) { settingsOpened++ } }
        yield()
        val shown = checkNotNull(snackbar.currentSnackbarData) { "no snackbar for $problem" }
        answer(shown)
        job.join()
        shown
    }
}
