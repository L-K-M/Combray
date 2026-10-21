package ch.lkmc.combray.core.browser

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the app picks its colors: follow the system, or one theme. */
enum class Appearance { SYSTEM, LIGHT, DARK }

/**
 * Reader view text size: a multiplier on the article's sp sizes, so the system font scale
 * still applies. Reader is rendered in Combray's own type, so the size is Combray's choice,
 * not the page's.
 */
enum class ReaderTextSize(val scale: Float) { SMALL(0.85f), DEFAULT(1f), LARGE(1.25f) }

/**
 * The browser's own preferences. Service configuration stays in the database, where it binds
 * queued work; these are plain user choices with nothing depending on them transactionally.
 */
class BrowserSettings(private val preferences: SharedPreferences) {
    private val unused = MutableStateFlow(read(KEY_UNUSED_TABS, UnusedTabs.entries, UnusedTabs.KEEP))
    private val look = MutableStateFlow(read(KEY_APPEARANCE, Appearance.entries, Appearance.SYSTEM))
    private val reader = MutableStateFlow(read(KEY_READER_SIZE, ReaderTextSize.entries, ReaderTextSize.DEFAULT))

    val unusedTabs: StateFlow<UnusedTabs> = unused.asStateFlow()

    val appearance: StateFlow<Appearance> = look.asStateFlow()

    val readerTextSize: StateFlow<ReaderTextSize> = reader.asStateFlow()

    fun setUnusedTabs(choice: UnusedTabs) = write(KEY_UNUSED_TABS, unused, choice)

    fun setAppearance(choice: Appearance) = write(KEY_APPEARANCE, look, choice)

    fun setReaderTextSize(choice: ReaderTextSize) = write(KEY_READER_SIZE, reader, choice)

    /** An unknown stored name falls back to its default: preferences never block startup. */
    private fun <T : Enum<T>> read(key: String, values: List<T>, default: T): T =
        values.firstOrNull { it.name == preferences.getString(key, null) } ?: default

    private fun <T : Enum<T>> write(key: String, state: MutableStateFlow<T>, choice: T) {
        preferences.edit().putString(key, choice.name).apply()
        state.value = choice
    }

    companion object {
        const val FILE_NAME = "browser_settings"
        private const val KEY_UNUSED_TABS = "unused_tabs"
        private const val KEY_APPEARANCE = "appearance"
        private const val KEY_READER_SIZE = "reader_text_size"
    }
}
