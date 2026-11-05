package ch.lkmc.combray.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.core.integration.EncodedScreenshot
import ch.lkmc.combray.core.integration.ReportDisplay
import ch.lkmc.combray.core.integration.ReportDraft
import ch.lkmc.combray.core.integration.ReportEnvironment
import ch.lkmc.combray.core.integration.ScreenshotOmission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The report preview: what it starts with, what the user can leave out or trim, and that Send
 * sends exactly what it shows. At the narrowest supported width, so the 48dp checks are real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w320dp-h640dp")
class ReportSheetTest {
    @get:Rule val compose = createComposeRule()

    private val sent = mutableListOf<ReportDraft>()

    /** Made in the test's sandbox, as the app makes it: a bitmap needs Robolectric's graphics. */
    private val preview = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888).asImageBitmap()
    private var cancelled = 0
    private var settingsOpened = 0

    @Test
    fun aReportSendsTheScreenshotAndTheFullAddressByDefault() {
        show()
        compose.onNodeWithText("Include screenshot").assertIsOn()
        compose.onNodeWithContentDescription("Screenshot of the page").assertIsDisplayed()
        compose.onNodeWithText("Sends what you see here to your report service, reports.example.org. Your report service and the agents that read it get everything shown here.")
            .assertIsDisplayed()
        send()

        val report = sent.single()
        assertSame(SCREENSHOT, report.screenshot)
        assertNull(report.omitted)
        assertEquals(PAGE_URL, report.url)
        assertFalse(report.urlEdited)
        assertEquals("Article", report.title)
        assertNull(report.comment)
    }

    @Test
    fun untickingTheScreenshotLeavesItOut() {
        show()
        compose.onNodeWithText("Include screenshot").performClick().assertIsOff()
        send()
        assertNull(sent.single().screenshot)
        assertEquals(ScreenshotOmission.USER, sent.single().omitted)
    }

    @Test
    fun theTrimButtonKeepsSiteAndPathAndRestoreBringsTheRestBack() {
        show()
        compose.onNodeWithText("Keep only the site and path").performScrollTo().performClick()
        address().assert(hasText("https://example.org/article"))
        compose.onNodeWithText("Keep only the site and path").assertDoesNotExist()
        compose.onNodeWithText("Restore the full address").performScrollTo().performClick()
        address().assert(hasText(PAGE_URL))

        compose.onNodeWithText("Keep only the site and path").performScrollTo().performClick()
        send()
        assertEquals("https://example.org/article", sent.single().url)
        assertTrue(sent.single().urlEdited)
    }

    /** Proposed default (open question 1): a sign-in address starts trimmed; one tap restores it. */
    @Test
    fun aSensitiveAddressStartsTrimmedWithAWarningOnceRestored() {
        val sensitive = "https://example.org/callback#access_token=abc&state=1"
        show(draft(snapshot = SNAPSHOT.copy(url = sensitive)))
        address().assert(hasText("https://example.org/callback"))
        compose.onNodeWithText(TRIMMED_NOTE).assertExists()
        compose.onNodeWithText(SENSITIVE_WARNING).assertDoesNotExist()

        compose.onNodeWithText("Restore the full address").performScrollTo().performClick()
        address().assert(hasText(sensitive))
        compose.onNodeWithText(SENSITIVE_WARNING).assertExists()
        compose.onNodeWithText(TRIMMED_NOTE).assertDoesNotExist()
        send()
        assertEquals(sensitive, sent.single().url)
        assertFalse(sent.single().urlEdited)
    }

    /** A servlet session in the path is a code too: the address starts without it. */
    @Test
    fun aSessionInThePathIsLeftOutAtTheStart() {
        show(draft(snapshot = SNAPSHOT.copy(url = "https://example.org/cart;jsessionid=8F3A?item=2")))
        address().assert(hasText("https://example.org/cart"))
        compose.onNodeWithText(TRIMMED_NOTE).assertExists()
        compose.onNodeWithText(SENSITIVE_WARNING).assertDoesNotExist()
    }

    /** The service refuses every control character, not only line breaks; the field says so. */
    @Test
    fun aTabInTheAddressCannotBeSent() {
        show()
        address().performTextClearance()
        address().performTextInput("https://example.org/\t")
        compose.onNodeWithText("Remove line breaks, tabs and other invisible characters from the address.").assertExists()
        sendButton().assertIsNotEnabled()
    }

    @Test
    fun aBlankAddressCannotBeSent() {
        show()
        address().performTextClearance()
        compose.onNodeWithText("Enter the page’s address.").assertExists()
        sendButton().assertIsNotEnabled()
        address().performTextInput("https://example.org/")
        sendButton().assertIsEnabled()
    }

    /** Proposed default (open question 3): reader view reports without a screenshot and says why. */
    @Test
    fun readerViewSaysWhyThereIsNoScreenshot() {
        show(draft(snapshot = SNAPSHOT.copy(display = ReportDisplay.READER), shot = ReportShot.Omitted(ScreenshotOmission.READER_VIEW)))
        compose.onNodeWithText("No screenshot: reader view covers the page. Leave reader view to include one.").assertIsDisplayed()
        compose.onNodeWithText("Include screenshot").assertDoesNotExist()
        send()
        assertEquals(ScreenshotOmission.READER_VIEW, sent.single().omitted)
        assertEquals(ReportDisplay.READER, sent.single().display)
    }

    @Test
    fun sendWaitsForTheScreenshotUnlessItIsLeftOut() {
        show(draft(shot = ReportShot.Pending(preview)))
        compose.onNodeWithText("Preparing the screenshot…").assertExists()
        sendButton().assertIsNotEnabled()
        compose.onNodeWithText("Include screenshot").performScrollTo().performClick()
        send()
        assertEquals(ScreenshotOmission.USER, sent.single().omitted)
    }

    /** A screenshot dropped after encoding replaces the checkbox: TalkBack has to hear why. */
    @Test
    fun aScreenshotDroppedWhileEncodingIsAnnounced() {
        val shots = MutableStateFlow<ReportShot>(ReportShot.Pending(preview))
        show(draft(shots = shots))
        shots.value = ReportShot.Omitted(ScreenshotOmission.TOO_LARGE)
        compose.onNodeWithText("No screenshot: it is too large to send, even scaled down.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("Include screenshot").assertDoesNotExist()
    }

    @Test
    fun theCommentAndTitleAreSentAsEdited() {
        show()
        field("Page title").performTextClearance()
        field("What went wrong? (optional)").performTextInput("The menu covers the first paragraph.")
        send()
        assertNull(sent.single().title)
        assertEquals("The menu covers the first paragraph.", sent.single().comment)
    }

    @Test
    fun theDetailsShowTheVersionsSent() {
        show()
        compose.onNodeWithText("Combray 0.1.0 (debug) · Servo 0.7.0 at 6fbfc3d · Android 15 (API 35)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Text size 125% · Display density 2.75").assertExists()
    }

    @Test
    fun aProblemStaysInTheSheetWithAWayToSettings() {
        val draft = draft()
        draft.progress.value = ReportProgress(problem = ReportProblem.TOO_MANY)
        show(draft)
        compose.onNodeWithText("Too many reports are waiting to be sent. See Settings.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        assertEquals(1, settingsOpened)
    }

    /** Settings opened over the sheet can change the service: the sheet names the current one. */
    @Test
    fun theSheetNamesTheServiceItIsGiven() {
        show(serviceHost = "new.example.org")
        compose.onNodeWithText("Sends what you see here to your report service, new.example.org. Your report service and the agents that read it get everything shown here.")
            .assertIsDisplayed()
    }

    @Test
    fun whileStoringSendIsDisabled() {
        val draft = draft()
        draft.progress.value = ReportProgress(sending = true)
        show(draft)
        sendButton().assertIsNotEnabled()
    }

    @Test
    fun cancelEndsTheReportWithoutSending() {
        show()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(1, cancelled)
        assertTrue(sent.isEmpty())
    }

    /**
     * As the device check does for the real sheet: every target labelled, 48dp and apart, at
     * twice the font size. Tall enough to lay every control out without scrolling.
     */
    @Test
    @Config(qualifiers = "w320dp-h3000dp")
    fun everyControlIsLabelledAndLargeEnough() {
        val withProblem = draft(snapshot = SNAPSHOT.copy(url = "https://example.org/a?session=1"))
        withProblem.progress.value = ReportProgress(problem = ReportProblem.NOT_SET_UP)
        show(withProblem, fontScale = 2f)
        val targets = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val problems = targets.mapNotNull(::problem) + targets.flatMapIndexed { index, target ->
            targets.drop(index + 1).filter { it.touchBoundsInRoot.overlaps(target.touchBoundsInRoot) }.map { "${name(target)} and ${name(it)} overlap" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        // The checkbox, Restore, three fields, Settings, Cancel and Send.
        assertEquals(8, targets.size)
    }

    private fun problem(node: SemanticsNode): String? {
        val bounds = node.touchBoundsInRoot
        val (width, height) = with(compose.density) { bounds.width.toDp() to bounds.height.toDp() }
        val name = name(node) ?: return "unlabelled target at $bounds"
        return if (width < 48.dp || height < 48.dp) "\"$name\" is ${width}x$height" else null
    }

    private fun name(node: SemanticsNode): String? {
        val label = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        return label.firstOrNull { it.isNotBlank() }
    }

    private fun show(draft: ReportDraftState = draft(), fontScale: Float = 1f, serviceHost: String = draft.serviceHost) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CombrayTheme {
                    ReportSheetContent(
                        draft = draft,
                        environment = ENVIRONMENT,
                        onSend = { sent += it },
                        onCancel = { cancelled++ },
                        onOpenSettings = { settingsOpened++ },
                        serviceHost = serviceHost,
                    )
                }
            }
        }
    }

    private fun address() = field("Address")

    private fun field(label: String): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo()

    private fun sendButton() = compose.onNodeWithText("Send").performScrollTo()

    private fun send() {
        sendButton().assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    private fun draft(
        snapshot: ReportSnapshot = SNAPSHOT,
        shot: ReportShot = ReportShot.Ready(preview, SCREENSHOT),
        shots: StateFlow<ReportShot> = MutableStateFlow(shot),
    ) = ReportDraftState("6f1c2b9e-1d2a-4a5b-9c3d-2e1f0a9b8c7d", 1_762_247_564_123L, snapshot, "reports.example.org", shots, preparation = null)

    private companion object {
        const val PAGE_URL = "https://example.org/article?x=1#part"
        const val TRIMMED_NOTE = "Only the site and path were kept because the address seems to contain a sign-in or session code."
        const val SENSITIVE_WARNING = "This address seems to contain a sign-in or session code."
        val SNAPSHOT = ReportSnapshot(
            url = PAGE_URL,
            title = "Article",
            display = ReportDisplay.PAGE,
            loadFailed = false,
            crashed = false,
            zoomPercent = 125,
            density = 2.75f,
        )
        val ENVIRONMENT = ReportEnvironment("0.1.0", 1, "debug", "0.7.0", "6fbfc3d70ad83df5443445d3ff4c43871aae41dd", "15", 35)
        val SCREENSHOT = EncodedScreenshot(ByteArray(8), 20, 40, scaled = false)
    }
}
