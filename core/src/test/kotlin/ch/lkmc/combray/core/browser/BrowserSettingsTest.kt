package ch.lkmc.combray.core.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun preferences(): SharedPreferences = context.getSharedPreferences(BrowserSettings.FILE_NAME, Context.MODE_PRIVATE)

    @Test
    fun pagesStartAtTheirOwnSize() {
        assertEquals(TextSize.PERCENT_100, BrowserSettings(preferences()).textSize.value)
        assertEquals(1f, TextSize.DEFAULT.zoom)
    }

    @Test
    fun aChosenTextSizeIsStoredAndReadBack() {
        val settings = BrowserSettings(preferences())
        settings.setTextSize(TextSize.PERCENT_150)

        assertEquals(TextSize.PERCENT_150, settings.textSize.value)
        assertEquals(TextSize.PERCENT_150, BrowserSettings(preferences()).textSize.value)
    }

    /** A step a later version no longer offers must not leave pages at an unknown size. */
    @Test
    fun anUnknownStoredTextSizeFallsBackToTheDefault() {
        preferences().edit().putString("text_size", "PERCENT_300").commit()

        assertEquals(TextSize.DEFAULT, BrowserSettings(preferences()).textSize.value)
    }

    @Test
    fun textSizeStepsGoUpAndDownAndStopAtTheEnds() {
        assertEquals(listOf(80, 90, 100, 110, 125, 150), TextSize.entries.map { it.percent })
        assertEquals(TextSize.PERCENT_110, TextSize.PERCENT_100.larger())
        assertEquals(TextSize.PERCENT_90, TextSize.PERCENT_100.smaller())
        assertNull(TextSize.PERCENT_150.larger())
        assertNull(TextSize.PERCENT_80.smaller())
        assertEquals(1.25f, TextSize.PERCENT_125.zoom)
    }
}
