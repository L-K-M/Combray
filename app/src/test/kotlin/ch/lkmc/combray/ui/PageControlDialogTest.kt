package ch.lkmc.combray.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.UploadStaging
import ch.lkmc.combray.core.browser.PageControl
import ch.lkmc.combray.engine.ControlResponse
import ch.lkmc.combray.engine.PageControlContent
import ch.lkmc.combray.engine.SelectEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A multiple `<select>` answers its chosen option ids in a stable order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PageControlDialogTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val answers = mutableListOf<ControlResponse>()

    @Test
    fun aMultipleSelectAnswersIdsInASetableOrder() {
        show(multiple = true, selected = listOf(1))

        compose.onNodeWithText("C").performClick()
        compose.onNodeWithText("B").performClick()
        compose.onNodeWithText("OK").performClick()

        // The set's iteration order would change with rotation; the answer stays sorted.
        assertEquals(listOf(1, 2, 3), (answers.single() as ControlResponse.Select).ids)
    }

    @Test
    fun aSingleSelectAnswersAtOnce() {
        show(multiple = false, selected = emptyList())

        compose.onNodeWithText("B").performClick()

        assertEquals(listOf(2), (answers.single() as ControlResponse.Select).ids)
    }

    private fun show(multiple: Boolean, selected: List<Int>) {
        answers.clear()
        compose.setContent {
            CombrayTheme {
                PageControlDialog(
                    control = PageControl(
                        id = 1,
                        site = "example.org",
                        content = PageControlContent.Select(
                            options = listOf(
                                SelectEntry(3, "C", disabled = false, group = null),
                                SelectEntry(1, "A", disabled = false, group = null),
                                SelectEntry(2, "B", disabled = false, group = null),
                            ),
                            selected = selected,
                            multiple = multiple,
                        ),
                        offerBlocking = false,
                    ),
                    staging = UploadStaging(File(context.cacheDir, "uploads"), context.contentResolver),
                    uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    onAnswer = { response, _ -> answers += response },
                    onUploadFailed = {},
                    isWebAddress = { true },
                    onOpenInNewTab = {},
                    onOpenInBackground = {},
                    onSaveLink = {},
                    onShare = {},
                    onNotify = {},
                )
            }
        }
    }
}
