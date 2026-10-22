package ch.lkmc.combray.core.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Theme choice persists across instances; the default follows the system. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserSettingsTest {
    private val preferences get() =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(BrowserSettings.FILE_NAME, Context.MODE_PRIVATE)

    @Before
    fun clear() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `theme defaults to following the system`() {
        assertEquals(ThemeMode.SYSTEM, BrowserSettings(preferences).themeMode.value)
    }

    @Test
    fun `theme choice survives a new instance`() {
        BrowserSettings(preferences).setThemeMode(ThemeMode.DARK_OLED)
        assertEquals(ThemeMode.DARK_OLED, BrowserSettings(preferences).themeMode.value)
    }
}
