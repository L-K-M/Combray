package ch.lkmc.combray.core.browser

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The browser's own preferences. Service configuration stays in the database, where it binds
 * queued work; these are plain user choices with nothing depending on them transactionally.
 */
class BrowserSettings(private val preferences: SharedPreferences) {
    private val unused = MutableStateFlow(readUnusedTabs())
    private val text = MutableStateFlow(readTextSize())

    val unusedTabs: StateFlow<UnusedTabs> = unused.asStateFlow()

    val textSize: StateFlow<TextSize> = text.asStateFlow()

    fun setUnusedTabs(choice: UnusedTabs) {
        preferences.edit().putString(KEY_UNUSED_TABS, choice.name).apply()
        unused.value = choice
    }

    /** Only stores the choice: [BrowserCoordinator.applyTextSize] passes it to the engine. */
    fun setTextSize(size: TextSize) {
        preferences.edit().putString(KEY_TEXT_SIZE, size.name).apply()
        text.value = size
    }

    private fun readUnusedTabs(): UnusedTabs =
        UnusedTabs.entries.firstOrNull { it.name == preferences.getString(KEY_UNUSED_TABS, null) } ?: UnusedTabs.KEEP

    private fun readTextSize(): TextSize =
        TextSize.entries.firstOrNull { it.name == preferences.getString(KEY_TEXT_SIZE, null) } ?: TextSize.DEFAULT

    companion object {
        const val FILE_NAME = "browser_settings"
        private const val KEY_UNUSED_TABS = "unused_tabs"
        private const val KEY_TEXT_SIZE = "text_size"
    }
}

/**
 * Text size of web pages, applied as the page zoom of every view: the page lays out again, so
 * text, pictures and layout grow together and lines rewrap to the screen. A choice of its own,
 * not Android's font scale: at the largest system font (200%) the same zoom would halve a
 * phone's CSS width and break most sites. Reader view follows the system font size instead.
 */
enum class TextSize(val percent: Int) {
    PERCENT_80(80),
    PERCENT_90(90),
    PERCENT_100(100),
    PERCENT_110(110),
    PERCENT_125(125),
    PERCENT_150(150),
    ;

    /** The engine's page zoom factor. */
    val zoom: Float get() = percent / PERCENT

    /** The next step up, or null at the largest. */
    fun larger(): TextSize? = entries.getOrNull(ordinal + 1)

    /** The next step down, or null at the smallest. */
    fun smaller(): TextSize? = entries.getOrNull(ordinal - 1)

    companion object {
        val DEFAULT = PERCENT_100
        private const val PERCENT = 100f
    }
}
