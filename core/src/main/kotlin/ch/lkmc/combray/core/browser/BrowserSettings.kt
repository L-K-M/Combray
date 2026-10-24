package ch.lkmc.combray.core.browser

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the app picks its colors: follow the system, or one theme whatever the system says. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Whether the Recent button shows the number of open tabs. */
enum class TabCounter { HIDDEN, SHOWN }

/** Whether one of the new-tab page's optional sections is on screen. */
enum class HomeSectionVisibility { SHOWN, HIDDEN }

/**
 * The browser's own preferences. Service configuration stays in the database, where it binds
 * queued work; these are plain user choices with nothing depending on them transactionally.
 */
class BrowserSettings(private val preferences: SharedPreferences) {
    private val unused = MutableStateFlow(read(KEY_UNUSED_TABS, UnusedTabs.entries, UnusedTabs.KEEP))
    private val theme = MutableStateFlow(read(KEY_THEME_MODE, ThemeMode.entries, ThemeMode.SYSTEM))
    private val text = MutableStateFlow(read(KEY_TEXT_SIZE, TextSize.entries, TextSize.DEFAULT))
    private val counter = MutableStateFlow(read(KEY_TAB_COUNTER, TabCounter.entries, TabCounter.HIDDEN))
    private val continueCard = MutableStateFlow(
        read(KEY_HOME_CONTINUE, HomeSectionVisibility.entries, HomeSectionVisibility.SHOWN),
    )
    private val frequentSites = MutableStateFlow(
        read(KEY_HOME_FREQUENT, HomeSectionVisibility.entries, HomeSectionVisibility.SHOWN),
    )

    val unusedTabs: StateFlow<UnusedTabs> = unused.asStateFlow()

    val themeMode: StateFlow<ThemeMode> = theme.asStateFlow()

    val textSize: StateFlow<TextSize> = text.asStateFlow()

    val tabCounter: StateFlow<TabCounter> = counter.asStateFlow()

    /** The new-tab page's Continue card. */
    val homeContinue: StateFlow<HomeSectionVisibility> = continueCard.asStateFlow()

    /** The new-tab page's automatic frequent-site links. */
    val homeFrequentSites: StateFlow<HomeSectionVisibility> = frequentSites.asStateFlow()

    fun setUnusedTabs(choice: UnusedTabs) = write(KEY_UNUSED_TABS, unused, choice)

    fun setThemeMode(choice: ThemeMode) = write(KEY_THEME_MODE, theme, choice)

    /** Only stores the choice: [BrowserCoordinator.applyTextSize] passes it to the engine. */
    fun setTextSize(size: TextSize) = write(KEY_TEXT_SIZE, text, size)

    fun setTabCounter(choice: TabCounter) = write(KEY_TAB_COUNTER, counter, choice)

    fun setHomeContinue(choice: HomeSectionVisibility) = write(KEY_HOME_CONTINUE, continueCard, choice)

    fun setHomeFrequentSites(choice: HomeSectionVisibility) = write(KEY_HOME_FREQUENT, frequentSites, choice)

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
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_TAB_COUNTER = "tab_counter"
        private const val KEY_HOME_CONTINUE = "home_continue"
        private const val KEY_HOME_FREQUENT = "home_frequent_sites"
    }
}

/**
 * Text size of web pages, applied as the page zoom of every view: the page lays out again, so
 * text, pictures and layout grow together and lines rewrap to the screen. A choice of its own,
 * not Android's font scale: at the largest system font (200%) the same zoom would halve a
 * phone's CSS width and break most sites. Reader view scales its type by the same step, on top of
 * the system font size.
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
