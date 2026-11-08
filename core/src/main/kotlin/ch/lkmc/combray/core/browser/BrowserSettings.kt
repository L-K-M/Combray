package ch.lkmc.combray.core.browser

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How the app picks its colors: follow the system, or one theme whatever the system says. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Whether the Recent button shows the number of open tabs. */
enum class TabCounter { HIDDEN, SHOWN }

/** Whether one of the new-tab page's optional sections is on screen. */
enum class HomeSectionVisibility { SHOWN, HIDDEN }

/**
 * The browser's own preferences. Service configuration stays in the database, where it binds
 * queued work; these are plain user choices with nothing depending on them transactionally.
 *
 * A public [StateFlow] is the durable value: it changes only after `commit()` acknowledged the
 * write. Every setter returns a [Deferred] that completes with `true` for an acknowledged write
 * and `false` for a `commit()` that returned false or threw. The disk write runs off the caller's
 * thread; the caller decides when to await it.
 */
class BrowserSettings(
    private val preferences: SharedPreferences,
    private val scope: CoroutineScope,
) {
    /**
     * The last value disk acknowledged for every known key: the exact stored string, or null
     * when the key is absent. A candidate write is built from this snapshot plus its own change,
     * so a failed earlier choice cannot ride along with a later write.
     */
    private val acknowledged = HashMap<String, String?>()

    /** Serialises writes; UNDISPATCHED admission keeps the caller's order. */
    private val writes = Mutex()

    private val unused = MutableStateFlow(acknowledge(KEY_UNUSED_TABS, UnusedTabs.entries, UnusedTabs.KEEP))
    private val theme = MutableStateFlow(acknowledge(KEY_THEME_MODE, ThemeMode.entries, ThemeMode.SYSTEM))
    private val text = MutableStateFlow(acknowledge(KEY_TEXT_SIZE, TextSize.entries, TextSize.DEFAULT))
    private val counter = MutableStateFlow(acknowledge(KEY_TAB_COUNTER, TabCounter.entries, TabCounter.HIDDEN))
    private val continueCard = MutableStateFlow(
        acknowledge(KEY_HOME_CONTINUE, HomeSectionVisibility.entries, HomeSectionVisibility.SHOWN),
    )
    private val frequentSites = MutableStateFlow(
        acknowledge(KEY_HOME_FREQUENT, HomeSectionVisibility.entries, HomeSectionVisibility.SHOWN),
    )

    val unusedTabs: StateFlow<UnusedTabs> = unused.asStateFlow()

    val themeMode: StateFlow<ThemeMode> = theme.asStateFlow()

    val textSize: StateFlow<TextSize> = text.asStateFlow()

    val tabCounter: StateFlow<TabCounter> = counter.asStateFlow()

    /** The new-tab page's Continue card. */
    val homeContinue: StateFlow<HomeSectionVisibility> = continueCard.asStateFlow()

    /** The new-tab page's automatic frequent-site links. */
    val homeFrequentSites: StateFlow<HomeSectionVisibility> = frequentSites.asStateFlow()

    fun setUnusedTabs(choice: UnusedTabs): Deferred<Boolean> = write(KEY_UNUSED_TABS, unused, choice)

    fun setThemeMode(choice: ThemeMode): Deferred<Boolean> = write(KEY_THEME_MODE, theme, choice)

    /** Only stores the choice: [BrowserCoordinator.applyTextSize] passes it to the engine. */
    fun setTextSize(size: TextSize): Deferred<Boolean> = write(KEY_TEXT_SIZE, text, size)

    fun setTabCounter(choice: TabCounter): Deferred<Boolean> = write(KEY_TAB_COUNTER, counter, choice)

    fun setHomeContinue(choice: HomeSectionVisibility): Deferred<Boolean> = write(KEY_HOME_CONTINUE, continueCard, choice)

    fun setHomeFrequentSites(choice: HomeSectionVisibility): Deferred<Boolean> = write(KEY_HOME_FREQUENT, frequentSites, choice)

    /** An unknown stored name falls back to its default: preferences never block startup. */
    private fun <T : Enum<T>> acknowledge(key: String, values: List<T>, default: T): T {
        val raw = preferences.getString(key, null)
        acknowledged[key] = raw
        return values.firstOrNull { it.name == raw } ?: default
    }

    /**
     * Publishes [choice] only after disk acknowledged it. The request is admitted synchronously,
     * so rapid calls keep their order; the disk write itself runs off the caller's thread. The
     * write runs in [scope], not in the returned [Deferred], so caller cancellation or dismissal
     * cannot interrupt an admitted write or its bookkeeping; work cancelled while waiting for the
     * lock never reaches the disk and never reports success.
     */
    private fun <T : Enum<T>> write(key: String, state: MutableStateFlow<T>, choice: T): Deferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                writes.withLock {
                    if (result.isCancelled) return@withLock
                    val previous = acknowledged.getValue(key)
                    val stored = try {
                        withContext(Dispatchers.IO) { commit(key, choice) }
                    } catch (_: Exception) {
                        false
                    }
                    if (stored) {
                        acknowledged[key] = choice.name
                        state.value = choice
                    } else {
                        // commit() may already have mutated the in-memory map; put the last
                        // acknowledged raw value back so a recreated wrapper does not read it.
                        restore(key, previous)
                    }
                    result.complete(stored)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                result.complete(false)
            }
        }
        return result
    }

    /**
     * Writes the complete acknowledged snapshot plus this request's change. Re-putting every
     * known key means a failed earlier choice is reconciled even if its own restore was lost.
     * Unknown keys are left alone.
     */
    private fun commit(key: String, choice: Enum<*>): Boolean {
        val editor = preferences.edit()
        acknowledged.forEach { (known, raw) ->
            when {
                known == key -> editor.putString(known, choice.name)
                raw == null -> editor.remove(known)
                else -> editor.putString(known, raw)
            }
        }
        return editor.commit()
    }

    /**
     * Best-effort in-memory reconciliation after a failed write. The disk already holds the
     * previous value (or does not hold this one); this is not acknowledged persistence, which is
     * why the setter still reports false.
     */
    private fun restore(key: String, raw: String?) {
        val editor = preferences.edit()
        if (raw == null) editor.remove(key) else editor.putString(key, raw)
        editor.apply()
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
