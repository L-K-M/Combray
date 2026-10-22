package ch.lkmc.combray

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.browser.PageControl
import ch.lkmc.combray.core.integration.SaveStatus
import ch.lkmc.combray.engine.PageControlContent
import ch.lkmc.combray.engine.SelectEntry
import ch.lkmc.combray.ui.BottomBar
import ch.lkmc.combray.ui.CombrayTheme
import ch.lkmc.combray.ui.MoreActions
import ch.lkmc.combray.ui.PageControlDialog
import ch.lkmc.combray.ui.PageSaveStatus
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every element of the browser chrome with a click action is at least 48dp each way and has a
 * label TalkBack reads, at the narrowest common phone width and at Android's largest font size.
 *
 * Checked on Compose semantics: Android's Accessibility Test Framework skips Compose content on
 * this emulator ("Composable content is not evaluated in this environment"). The chrome runs on a
 * plain Compose host and the More sheet's rows without the sheet: under software emulation a
 * sheet's window does not take focus within Android's input timeout (docs/BUILDING.md).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theBottomBarOnAPageIsAccessible() {
        show(LARGE_FONT) {
            Bar(PAGE, openTabs = 3, SaveStatus.Pending)
        }

        assertTargetsAreAccessible()
        for (label in listOf("Back", "Saving to Karakeep", "Share", "Recent, 3 open tabs", "More")) {
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("example.org", substring = true).assertIsDisplayed()
    }

    @Test
    fun aNarrowBarGivesTheAddressARowAboveTheActions() {
        show(DEFAULT_FONT) {
            Bar(PAGE, openTabs = 1, SaveStatus.Unsaved)
        }

        val address = compose.onNodeWithContentDescription("example.org", substring = true).getUnclippedBoundsInRoot()
        val back = compose.onNodeWithContentDescription("Back").getUnclippedBoundsInRoot()
        val more = compose.onNodeWithContentDescription("More").getUnclippedBoundsInRoot()
        assertTrue(address.bottom <= back.top)
        // The chip spans the actions under it.
        assertTrue(address.left <= back.left && address.right >= more.right)
    }

    @Test
    fun aWideBarKeepsOneRow() {
        show(DEFAULT_FONT) {
            Bar(PAGE, openTabs = 1, SaveStatus.Unsaved, width = WIDE_WIDTH)
        }

        val address = compose.onNodeWithContentDescription("example.org", substring = true).getUnclippedBoundsInRoot()
        val back = compose.onNodeWithContentDescription("Back").getUnclippedBoundsInRoot()
        assertTrue(address.top < back.bottom && address.bottom > back.top)
        assertTrue(address.left >= back.right)
    }

    @Test
    fun addressCopyIsAnAccessibleLongClick() {
        show(DEFAULT_FONT) {
            Bar(PAGE, openTabs = 1, SaveStatus.Unsaved)
        }

        val node = compose.onNodeWithContentDescription("example.org", substring = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.OnLongClick)
        assertNotNull("the address chip must expose a long-click copy action", action)
        assertEquals("Copy address", action!!.label)
    }

    @Test
    fun copyingTheAddressDoesNotOpenTheEditor() {
        var copies = 0
        var edits = 0
        show(DEFAULT_FONT) {
            Bar(PAGE, openTabs = 1, SaveStatus.Unsaved, onAddress = { edits++ }, onCopyAddress = { copies++ })
        }

        // The exposed semantics action: this is not proof of the physical gesture or clipboard.
        compose.onNodeWithContentDescription("example.org", substring = true)
            .performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(1, copies)
        assertEquals(0, edits)

        compose.onNodeWithContentDescription("example.org", substring = true).performClick()
        assertEquals(1, edits)
        assertEquals(1, copies)
    }

    @Test
    fun aNewTabHasNoAddressCopyAction() {
        var copies = 0
        var edits = 0
        show(DEFAULT_FONT) {
            Bar(null, openTabs = 0, SaveStatus.NotConfigured, onAddress = { edits++ }, onCopyAddress = { copies++ })
        }

        val node = compose.onNodeWithContentDescription("Edit address", substring = true).fetchSemanticsNode()
        assertNull(node.config.getOrNull(SemanticsActions.OnLongClick))

        compose.onNodeWithContentDescription("Edit address", substring = true).performClick()
        assertEquals(1, edits)
        assertEquals(0, copies)
    }

    @Test
    fun aSaveWaitingForKarakeepIsLabeledAsSuch() {
        show(LARGE_FONT) {
            Bar(PAGE, openTabs = 1, SaveStatus.Waiting)
        }

        assertTargetsAreAccessible()
        compose.onNodeWithContentDescription("Waiting for Karakeep").assertIsDisplayed()
    }

    @Test
    fun theBottomBarOnANewTabIsAccessible() {
        show(LARGE_FONT) {
            Bar(null, openTabs = 0, SaveStatus.NotConfigured)
        }

        assertTargetsAreAccessible()
    }

    @Test
    fun theMoreActionsAreAccessible() {
        show(LARGE_FONT) {
            Column(Modifier.width(NARROW_WIDTH)) {
                MoreActions(PAGE.copy(canGoForward = true), {}, {}, {}, {}, {}, {}, captureEnabled = true, {}, readerOpen = false, {})
            }
        }

        assertTargetsAreAccessible()
        // The rows scroll when they outgrow the window: bring each into view before checking it.
        for (label in listOf("Close tab", "Pause history capture", "Settings")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun aRepeatedConfirmIsAccessible() = assertControlIsAccessible(PageControlContent.Confirm("Leave this page?"), offerBlocking = true)

    @Test
    fun aPromptIsAccessible() = assertControlIsAccessible(PageControlContent.Prompt("Your name?", "Ada"))

    @Test
    fun aSignInIsAccessible() = assertControlIsAccessible(PageControlContent.SignIn("http://intranet.test", proxy = false))

    @Test
    fun aSelectIsAccessible() = assertControlIsAccessible(
        PageControlContent.Select(
            listOf(
                SelectEntry(0, "Red", disabled = false, group = "Warm"),
                SelectEntry(1, "Orange", disabled = true, group = "Warm"),
                SelectEntry(2, "Blue", disabled = false, group = null),
            ),
            selected = listOf(2),
            multiple = false,
        ),
    )

    @Test
    fun aLinkMenuIsAccessible() {
        assertControlIsAccessible(PageControlContent.LinkMenu("https://example.org/a", image = null))
        for (label in listOf("Open in new tab", "Open in background", "Save link to Karakeep", "Copy link", "Share link")) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun anImageMenuIsAccessible() = assertControlIsAccessible(PageControlContent.LinkMenu(link = null, "https://example.org/a.png"))

    /**
     * A linked image lists both menus. At the largest font they can need to scroll on the
     * reference emulator's 360x720dp screen, and a row scrolled out of view has no touch area to
     * measure.
     */
    @Test
    fun aLinkedImageMenuIsAccessible() = assertControlIsAccessible(
        PageControlContent.LinkMenu("https://example.org/a", "https://example.org/a.png"),
        fontScale = DEFAULT_FONT,
    )

    /** The check itself: a target without a label fails it. */
    @Test
    fun anUnlabeledTargetFails() {
        show(DEFAULT_FONT) { Box(Modifier.size(MIN_TARGET).clickable {}) }

        assertThrows(AssertionError::class.java) { assertTargetsAreAccessible() }
    }

    /** The check itself: small targets side by side fail it, since neither gets 48dp to itself. */
    @Test
    fun crowdedSmallTargetsFail() {
        show(DEFAULT_FONT) {
            Row {
                for (label in listOf("One", "Two")) {
                    Box(Modifier.size(SMALL_TARGET).clickable {}.semantics { contentDescription = label })
                }
            }
        }

        assertThrows(AssertionError::class.java) { assertTargetsAreAccessible() }
    }

    /** A page's dialog, as the browser shows it, at the largest font size unless [fontScale] says otherwise. */
    private fun assertControlIsAccessible(content: PageControlContent, offerBlocking: Boolean = false, fontScale: Float = LARGE_FONT) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staging = UploadStaging(File(context.cacheDir, "accessibility-uploads"), context.contentResolver)
        val control = PageControl(id = 1, site = "example.org", content = content, offerBlocking = offerBlocking)
        show(fontScale) {
            PageControlDialog(
                control,
                staging,
                MainScope(),
                { _, _ -> },
                {},
                isWebAddress = { true },
                onOpenInNewTab = {},
                onOpenInBackground = {},
                onSaveLink = {},
                onShare = {},
                onNotify = {},
            )
        }

        assertTargetsAreAccessible()
    }

    private fun show(fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CombrayTheme(content = content)
            }
        }
    }

    private fun assertTargetsAreAccessible() {
        val targets = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        if (targets.isEmpty()) throw AssertionError("nothing to tap")
        val crowded = targets.flatMapIndexed { index, target ->
            targets.drop(index + 1)
                .filter { it.touchBoundsInRoot.overlaps(target.touchBoundsInRoot) }
                .map { "\"${name(target) ?: UNLABELED}\" and \"${name(it) ?: UNLABELED}\" share touch area" }
        }
        val problems = targets.mapNotNull(::problem) + crowded
        if (problems.isNotEmpty()) throw AssertionError(problems.joinToString("\n"))
    }

    /**
     * What is wrong with one tappable element, or null. Its touch bounds count, not its drawing:
     * Compose stretches a small target's hit area to 48dp (a 40dp Material icon button relies on
     * it), which is why overlapping touch bounds are checked as well.
     */
    private fun problem(node: SemanticsNode): String? {
        val bounds = node.touchBoundsInRoot
        val (width, height) = with(compose.density) { bounds.width.toDp() to bounds.height.toDp() }
        val name = name(node) ?: return "unlabeled target at $bounds"
        if (width < MIN_TARGET || height < MIN_TARGET) return "\"$name\" is ${width}x$height"
        return null
    }

    /** What TalkBack reads for [node], or null when it reads nothing. */
    private fun name(node: SemanticsNode): String? {
        val label = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        return label.firstOrNull { it.isNotBlank() }
    }

    /**
     * The bar at the narrow width, every action a no-op unless given. Named arguments keep a new
     * `() -> Unit` parameter from silently shifting the callbacks a test means to observe.
     */
    @Composable
    private fun Bar(
        tab: ActiveTab?,
        openTabs: Int,
        saveStatus: SaveStatus,
        onAddress: () -> Unit = {},
        onCopyAddress: () -> Unit = {},
        width: Dp = NARROW_WIDTH,
    ) = BottomBar(
        tab = tab,
        openTabs = openTabs,
        saveStatus = PageSaveStatus(tab?.url, saveStatus),
        backEnabled = tab?.canGoBack == true,
        onBack = {},
        onAddress = onAddress,
        onSave = {},
        onShare = {},
        onRecent = {},
        onRecentLongPress = {},
        onMore = {},
        onCopyAddress = onCopyAddress,
        onSwipeTab = {},
        // Required, not just preferred: the reference emulator is 360dp wide, and a wider bar must
        // still measure its own width to take the single-row layout.
        modifier = Modifier.requiredWidth(width),
    )

    private companion object {
        const val DEFAULT_FONT = 1f

        const val UNLABELED = "(unlabeled)"

        /** Android's largest font size setting. */
        const val LARGE_FONT = 2f

        /** Small phones are 320dp wide. */
        val NARROW_WIDTH = 320.dp

        /** Wide enough for the single-row bar. */
        val WIDE_WIDTH = 412.dp

        /** Android's and Material's minimum touch target. */
        val MIN_TARGET = 48.dp

        val SMALL_TARGET = 40.dp

        val PAGE = ActiveTab(
            id = "tab",
            url = "https://example.org/article",
            title = "Article",
            loading = true,
            canGoBack = true,
            canGoForward = false,
            crashed = false,
            hasView = true,
        )
    }
}
