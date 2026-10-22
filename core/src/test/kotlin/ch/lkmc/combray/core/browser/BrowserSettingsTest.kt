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
        first.setThemeMode(ThemeMode.DARK)
        first.setUnusedTabs(UnusedTabs.CLOSE)

        val restarted = BrowserSettings(preferences)

        assertEquals(ThemeMode.DARK, restarted.themeMode.value)
        assertEquals(UnusedTabs.CLOSE, restarted.unusedTabs.value)
    }

    @Test
    fun aFreshInstallFollowsTheSystem() {
        val settings = BrowserSettings(preferences)

        assertEquals(ThemeMode.SYSTEM, settings.themeMode.value)
        assertEquals(UnusedTabs.KEEP, settings.unusedTabs.value)
    }

    @Test
    fun anUnknownStoredChoiceFallsBackToItsDefault() {
        // Pin the real storage keys first: if these ever move, the literals below must too.
        val probe = BrowserSettings(preferences)
        probe.setThemeMode(ThemeMode.LIGHT)
        probe.setUnusedTabs(UnusedTabs.CLOSE)
        assertEquals("LIGHT", preferences.getString("theme_mode", null))
        assertEquals("CLOSE", preferences.getString("unused_tabs", null))

        preferences.edit().putString("theme_mode", "MIDNIGHT").putString("unused_tabs", "SOMETIMES").apply()

        val settings = BrowserSettings(preferences)

        assertEquals(ThemeMode.SYSTEM, settings.themeMode.value)
        assertEquals(UnusedTabs.KEEP, settings.unusedTabs.value)
    }
}
