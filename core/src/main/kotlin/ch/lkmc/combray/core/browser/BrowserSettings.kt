package ch.lkmc.combray.core.browser

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the app picks its colors: follow the system, or one theme whatever the system says. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The browser's own preferences. Service configuration stays in the database, where it binds
 * queued work; these are plain user choices with nothing depending on them transactionally.
 */
class BrowserSettings(private val preferences: SharedPreferences) {
    private val unused = MutableStateFlow(read(KEY_UNUSED_TABS, UnusedTabs.entries, UnusedTabs.KEEP))
    private val theme = MutableStateFlow(read(KEY_THEME_MODE, ThemeMode.entries, ThemeMode.SYSTEM))

    val unusedTabs: StateFlow<UnusedTabs> = unused.asStateFlow()

    val themeMode: StateFlow<ThemeMode> = theme.asStateFlow()

    fun setUnusedTabs(choice: UnusedTabs) = write(KEY_UNUSED_TABS, unused, choice)

    fun setThemeMode(choice: ThemeMode) = write(KEY_THEME_MODE, theme, choice)

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
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
