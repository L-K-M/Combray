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

    val unusedTabs: StateFlow<UnusedTabs> = unused.asStateFlow()

    fun setUnusedTabs(choice: UnusedTabs) {
        preferences.edit().putString(KEY_UNUSED_TABS, choice.name).apply()
        unused.value = choice
    }

    private fun readUnusedTabs(): UnusedTabs =
        UnusedTabs.entries.firstOrNull { it.name == preferences.getString(KEY_UNUSED_TABS, null) } ?: UnusedTabs.KEEP

    companion object {
        const val FILE_NAME = "browser_settings"
        private const val KEY_UNUSED_TABS = "unused_tabs"
    }
}
