package ch.lkmc.combray.core.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The user's own preferences survive a restart and never block one (docs/IMPLEMENTATION_PLAN.md section 2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        preferences = context.getSharedPreferences("settings-test", Context.MODE_PRIVATE)
    }

    @Test
    fun choicesSurviveARestart() {
        val first = BrowserSettings(preferences)
        first.setAppearance(Appearance.DARK)
        first.setReaderTextSize(ReaderTextSize.LARGE)
        first.setUnusedTabs(UnusedTabs.CLOSE)

        val restarted = BrowserSettings(preferences)

        assertEquals(Appearance.DARK, restarted.appearance.value)
        assertEquals(ReaderTextSize.LARGE, restarted.readerTextSize.value)
        assertEquals(UnusedTabs.CLOSE, restarted.unusedTabs.value)
    }

    @Test
    fun aFreshInstallFollowsTheSystemAtDefaultSizes() {
        val settings = BrowserSettings(preferences)

        assertEquals(Appearance.SYSTEM, settings.appearance.value)
        assertEquals(ReaderTextSize.DEFAULT, settings.readerTextSize.value)
        assertEquals(UnusedTabs.KEEP, settings.unusedTabs.value)
    }

    @Test
    fun anUnknownStoredChoiceFallsBackToItsDefault() {
        preferences.edit().putString("appearance", "MIDNIGHT").putString("reader_text_size", "HUGE").apply()

        val settings = BrowserSettings(preferences)

        assertEquals(Appearance.SYSTEM, settings.appearance.value)
        assertEquals(ReaderTextSize.DEFAULT, settings.readerTextSize.value)
    }
}
