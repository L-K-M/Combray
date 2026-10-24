package ch.lkmc.combray

import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.browser.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Painted close feedback, including Home's padding over the native page surface. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NoticeThemeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private lateinit var originalTheme: ThemeMode

    @Before
    fun setUp() {
        originalTheme = device.settings.themeMode.value
        device.awaitEngine()
        device.closeAllTabs()
    }

    @After
    fun restoreTheme() {
        device.settings.setThemeMode(originalTheme)
    }

    @Test
    fun aCloseNoticeAndItsHomeBackdropStayDark() = checkCloseNotice(ThemeMode.DARK)

    @Test
    fun aCloseNoticeAndItsHomeBackdropStayLight() = checkCloseNotice(ThemeMode.LIGHT)

    private fun checkCloseNotice(theme: ThemeMode) {
        device.settings.setThemeMode(theme)
        val url = Fixtures.solid(Device.GREEN)
        device.browser.openUrl(url, Placement.NEW_TAB)
        val tab = device.awaitPage(url).activeTab!!.id
        device.browser.closeTab(tab)
        device.awaitState { it.activeTab == null }

        val message = compose.activity.getString(R.string.notice_tab_closed)
        compose.waitUntilAtLeastOneExists(hasText(message), Device.LOAD_TIMEOUT_MS)
        compose.mainClock.advanceTimeBy(SNACKBAR_ANIMATION_MS)
        val bounds = compose.onNodeWithText(message).fetchSemanticsNode().boundsInWindow
        device.dismissSystemDialogs()
        val screen = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            // Left of the text is the notice's fill; at the screen edge is Home's backdrop.
            val notice = screen.getPixel((bounds.left - SAMPLE_INSET_PX).toInt(), bounds.center.y.toInt())
            val backdrop = screen.getPixel(SAMPLE_INSET_PX, bounds.center.y.toInt())
            val dark = theme == ThemeMode.DARK
            assertTrue(
                "$theme notice=${Integer.toHexString(notice)}, Home backdrop=${Integer.toHexString(backdrop)}",
                dark == isDark(notice) && dark == isDark(backdrop),
            )
        } finally {
            screen.recycle()
        }

        compose.onNodeWithText(compose.activity.getString(R.string.action_undo)).performClick()
        val restored = device.awaitState { it.activeTab?.id == tab }
        assertEquals(1, restored.openTabCount)
    }

    private fun isDark(color: Int) = Color.luminance(color) < MID_LUMINANCE

    private companion object {
        const val MID_LUMINANCE = 0.5f
        const val SAMPLE_INSET_PX = 4
        const val SNACKBAR_ANIMATION_MS = 500L
    }
}
