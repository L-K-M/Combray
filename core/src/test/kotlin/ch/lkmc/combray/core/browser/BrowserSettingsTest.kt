package ch.lkmc.combray.core.browser

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        val first = newSettings(preferences)
        assertTrue(first.setThemeMode(ThemeMode.DARK).awaitBlocking())
        assertTrue(first.setUnusedTabs(UnusedTabs.CLOSE).awaitBlocking())
        assertTrue(first.setTabCounter(TabCounter.SHOWN).awaitBlocking())
        assertTrue(first.setLightPagesInDark(LightPagesInDark.KEEP).awaitBlocking())

        val restarted = newSettings(preferences)

        assertEquals(ThemeMode.DARK, restarted.themeMode.value)
        assertEquals(UnusedTabs.CLOSE, restarted.unusedTabs.value)
        assertEquals(TabCounter.SHOWN, restarted.tabCounter.value)
        assertEquals(LightPagesInDark.KEEP, restarted.lightPagesInDark.value)
    }

    @Test
    fun aFreshInstallFollowsTheSystem() {
        val settings = newSettings(preferences)

        assertEquals(ThemeMode.SYSTEM, settings.themeMode.value)
        assertEquals(UnusedTabs.KEEP, settings.unusedTabs.value)
        assertEquals(TabCounter.HIDDEN, settings.tabCounter.value)
        assertEquals(LightPagesInDark.DARKEN, settings.lightPagesInDark.value)
    }

    @Test
    fun anUnknownStoredChoiceFallsBackToItsDefault() {
        // Pin the real storage keys first: if these ever move, the literals below must too.
        val probe = newSettings(preferences)
        assertTrue(probe.setThemeMode(ThemeMode.LIGHT).awaitBlocking())
        assertTrue(probe.setUnusedTabs(UnusedTabs.CLOSE).awaitBlocking())
        assertTrue(probe.setTabCounter(TabCounter.SHOWN).awaitBlocking())
        assertTrue(probe.setLightPagesInDark(LightPagesInDark.KEEP).awaitBlocking())
        assertEquals("LIGHT", preferences.getString("theme_mode", null))
        assertEquals("CLOSE", preferences.getString("unused_tabs", null))
        assertEquals("SHOWN", preferences.getString("tab_counter", null))
        assertEquals("KEEP", preferences.getString("light_pages_in_dark", null))

        preferences.edit().putString("theme_mode", "MIDNIGHT").putString("unused_tabs", "SOMETIMES")
            .putString("tab_counter", "SOMETIMES").putString("light_pages_in_dark", "SOMETIMES").apply()

        val settings = newSettings(preferences)

        assertEquals(ThemeMode.SYSTEM, settings.themeMode.value)
        assertEquals(UnusedTabs.KEEP, settings.unusedTabs.value)
        assertEquals(TabCounter.HIDDEN, settings.tabCounter.value)
        assertEquals(LightPagesInDark.DARKEN, settings.lightPagesInDark.value)
    }

    @Test
    fun hidingTheCounterUpdatesTheChoiceAndSurvivesARestart() {
        val settings = newSettings(preferences)
        assertTrue(settings.setTabCounter(TabCounter.SHOWN).awaitBlocking())
        assertEquals(TabCounter.SHOWN, settings.tabCounter.value)

        assertTrue(settings.setTabCounter(TabCounter.HIDDEN).awaitBlocking())

        assertEquals(TabCounter.HIDDEN, settings.tabCounter.value)
        assertEquals(TabCounter.HIDDEN, newSettings(preferences).tabCounter.value)
    }

    @Test
    fun theNewTabSectionsStartShownAndKeepTheirChoice() {
        val settings = newSettings(preferences)

        assertEquals(HomeSectionVisibility.SHOWN, settings.homeContinue.value)
        assertEquals(HomeSectionVisibility.SHOWN, settings.homeFrequentSites.value)

        assertTrue(settings.setHomeContinue(HomeSectionVisibility.HIDDEN).awaitBlocking())
        assertTrue(settings.setHomeFrequentSites(HomeSectionVisibility.HIDDEN).awaitBlocking())
        assertEquals(HomeSectionVisibility.HIDDEN, settings.homeContinue.value)
        assertEquals(HomeSectionVisibility.HIDDEN, settings.homeFrequentSites.value)

        val restarted = newSettings(preferences)
        assertEquals(HomeSectionVisibility.HIDDEN, restarted.homeContinue.value)
        assertEquals(HomeSectionVisibility.HIDDEN, restarted.homeFrequentSites.value)
    }

    /** The sections' keys are independent: hiding one leaves the other on screen. */
    @Test
    fun theNewTabSectionsHideIndependently() {
        val settings = newSettings(preferences)
        assertTrue(settings.setHomeFrequentSites(HomeSectionVisibility.HIDDEN).awaitBlocking())
        assertEquals("HIDDEN", preferences.getString("home_frequent_sites", null))

        assertEquals(HomeSectionVisibility.SHOWN, settings.homeContinue.value)
        assertNull(preferences.getString("home_continue", null))

        preferences.edit().putString("home_continue", "SOMETIMES").commit()
        assertEquals(HomeSectionVisibility.SHOWN, newSettings(preferences).homeContinue.value)
    }

    @Test
    fun pagesStartAtTheirOwnSize() {
        assertEquals(TextSize.PERCENT_100, newSettings(preferences).textSize.value)
        assertEquals(1f, TextSize.DEFAULT.zoom)
    }

    @Test
    fun aChosenTextSizeIsStoredAndReadBack() {
        val settings = newSettings(preferences)
        assertTrue(settings.setTextSize(TextSize.PERCENT_150).awaitBlocking())

        assertEquals(TextSize.PERCENT_150, settings.textSize.value)
        assertEquals(TextSize.PERCENT_150, newSettings(preferences).textSize.value)
    }

    /** A step a later version no longer offers must not leave pages at an unknown size. */
    @Test
    fun anUnknownStoredTextSizeFallsBackToTheDefault() {
        // Pin the real storage key first: if it ever moves, the literal below must too.
        assertTrue(newSettings(preferences).setTextSize(TextSize.PERCENT_150).awaitBlocking())
        assertEquals("PERCENT_150", preferences.getString("text_size", null))

        preferences.edit().putString("text_size", "PERCENT_300").commit()

        assertEquals(TextSize.DEFAULT, newSettings(preferences).textSize.value)
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

    @Test
    fun aDelayedAcknowledgementLeavesTheDurableChoiceUnchangedAndDoesNotBlockTheCaller() {
        val scripted = scripted()
        val latch = CountDownLatch(1)
        scripted.latch = latch
        val settings = BrowserSettings(scripted, scope())
        val deferred = settings.setTextSize(TextSize.PERCENT_150)

        assertFalse("the setter must return before disk acknowledges", deferred.isCompleted)
        assertEquals(TextSize.PERCENT_100, settings.textSize.value)
        latch.countDown()

        assertTrue(deferred.awaitBlocking())
        assertEquals(TextSize.PERCENT_150, settings.textSize.value)
        assertEquals(TextSize.PERCENT_150, BrowserSettings(scripted, scope()).textSize.value)
    }

    @Test
    fun aCommitNeverRunsOnTheMainThread() {
        val scripted = scripted()
        val latch = CountDownLatch(1)
        scripted.latch = latch
        val settings = BrowserSettings(scripted, scope())
        val deferred = settings.setTextSize(TextSize.PERCENT_150)
        latch.countDown()
        assertTrue(deferred.awaitBlocking())

        assertFalse(
            "commit() must not run on the main thread",
            scripted.commitThreads.contains(Looper.getMainLooper().thread),
        )
        assertFalse("A successful write must have entered commit()", scripted.commitThreads.isEmpty())
        assertNotEquals(Looper.getMainLooper().thread, scripted.commitThreads.peek())
    }

    @Test
    fun aFalseCommitDoesNotPublishAndReconcilesTheStoredValue() {
        val scripted = scripted()
        val settings = BrowserSettings(scripted, scope())
        assertTrue(settings.setThemeMode(ThemeMode.DARK).awaitBlocking())
        scripted.mode = ScriptedPreferences.Mode.Fail

        assertFalse(settings.setThemeMode(ThemeMode.LIGHT).awaitBlocking())

        assertEquals(ThemeMode.DARK, settings.themeMode.value)
        assertEquals("DARK", scripted.getString("theme_mode", null))
        assertEquals(ThemeMode.DARK, BrowserSettings(scripted, scope()).themeMode.value)
    }

    @Test
    fun aThrowingCommitDoesNotPublishAndReconcilesTheStoredValue() {
        val scripted = scripted()
        val settings = BrowserSettings(scripted, scope())
        assertTrue(settings.setTextSize(TextSize.PERCENT_150).awaitBlocking())
        scripted.mode = ScriptedPreferences.Mode.Throw

        assertFalse(settings.setTextSize(TextSize.PERCENT_80).awaitBlocking())

        assertEquals(TextSize.PERCENT_150, settings.textSize.value)
        assertEquals("PERCENT_150", scripted.getString("text_size", null))
        assertEquals(TextSize.PERCENT_150, BrowserSettings(scripted, scope()).textSize.value)
    }

    @Test
    fun aLaterDifferentKeyWriteCannotPersistAnEarlierFailedChoice() {
        val scripted = scripted()
        val settings = BrowserSettings(scripted, scope())
        assertTrue(settings.setTextSize(TextSize.PERCENT_100).awaitBlocking())
        scripted.mode = ScriptedPreferences.Mode.Fail
        assertFalse(settings.setTextSize(TextSize.PERCENT_150).awaitBlocking())

        scripted.mode = ScriptedPreferences.Mode.Acknowledge
        assertTrue(settings.setThemeMode(ThemeMode.LIGHT).awaitBlocking())

        assertEquals(TextSize.PERCENT_100, settings.textSize.value)
        assertEquals("PERCENT_100", scripted.getString("text_size", null))
        assertEquals(ThemeMode.LIGHT, settings.themeMode.value)
        assertEquals("LIGHT", scripted.getString("theme_mode", null))
        val restarted = BrowserSettings(scripted, scope())
        assertEquals(TextSize.PERCENT_100, restarted.textSize.value)
        assertEquals(ThemeMode.LIGHT, restarted.themeMode.value)
    }

    @Test
    fun sequentialRapidChoicesPreserveAdmissionOrder() {
        val scripted = scripted()
        val latch = CountDownLatch(1)
        scripted.latch = latch
        scripted.watchedKey = "text_size"
        val settings = BrowserSettings(scripted, scope())

        val deferreds = listOf(
            settings.setTextSize(TextSize.PERCENT_80),
            settings.setTextSize(TextSize.PERCENT_100),
            settings.setTextSize(TextSize.PERCENT_150),
        )
        latch.countDown()
        assertTrue(deferreds.all { it.awaitBlocking() })

        assertEquals(
            listOf("PERCENT_80", "PERCENT_100", "PERCENT_150"),
            scripted.watchedValues,
        )
        assertEquals(TextSize.PERCENT_150, settings.textSize.value)
        assertEquals(TextSize.PERCENT_150, BrowserSettings(scripted, scope()).textSize.value)
    }

    @Test
    fun workCancelledBeforeAdmissionDoesNotReportSuccess() {
        val scripted = scripted()
        val latch = CountDownLatch(1)
        scripted.latch = latch
        val settings = BrowserSettings(scripted, scope())

        val first = settings.setTextSize(TextSize.PERCENT_150)
        val second = settings.setTextSize(TextSize.PERCENT_80)
        second.cancel()
        latch.countDown()

        assertTrue(first.awaitBlocking())
        assertTrue(second.isCancelled)
        assertEquals(TextSize.PERCENT_150, settings.textSize.value)
        assertEquals("PERCENT_150", scripted.getString("text_size", null))
    }

    @Test
    fun cancellingAnAdmittedWriteStillAcknowledgesButReportsNoSuccess() {
        val scripted = scripted()
        val latch = CountDownLatch(1)
        scripted.latch = latch
        val settings = BrowserSettings(scripted, scope())
        val deferred = settings.setTextSize(TextSize.PERCENT_150)

        deferred.cancel()
        latch.countDown()

        // The admitted write finishes and publishes, but the cancelled caller never sees true.
        runBlocking {
            withTimeout(5_000) { settings.textSize.first { it == TextSize.PERCENT_150 } }
        }
        assertEquals(TextSize.PERCENT_150, settings.textSize.value)
        assertTrue(deferred.isCancelled)
        try {
            runBlocking { deferred.await() }
            fail("a cancelled write must not report success")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            // Expected.
        }
    }

    @Test
    fun cancellingTheOwnerScopeDoesNotLoseAnAcknowledgedWrite() {
        val scripted = scripted()
        val latch = CountDownLatch(1)
        scripted.latch = latch
        val owner = scope()
        val settings = BrowserSettings(scripted, owner)
        val result = settings.setTextSize(TextSize.PERCENT_150)
        try {
            assertTrue(scripted.commitEntered.await(5, TimeUnit.SECONDS))
            owner.cancel()
        } finally {
            latch.countDown()
        }
        assertTrue("An admitted write must retain its actual disk acknowledgement", result.awaitBlocking())
        assertEquals(TextSize.PERCENT_150, settings.textSize.value)
        assertEquals("PERCENT_150", scripted.getString("text_size", null))
    }

    @Test
    fun cancellingTheOwnerScopeCancelsAQueuedResult() {
        val scripted = scripted()
        val latch = CountDownLatch(1)
        scripted.latch = latch
        val owner = scope()
        val settings = BrowserSettings(scripted, owner)
        val first = settings.setTextSize(TextSize.PERCENT_150)
        assertTrue(scripted.commitEntered.await(5, TimeUnit.SECONDS))
        val queued = settings.setTextSize(TextSize.PERCENT_80)
        try {
            owner.cancel()
        } finally {
            latch.countDown()
        }
        runBlocking { withTimeout(5_000) { owner.coroutineContext[Job]!!.join() } }
        assertTrue("A queued result must cancel, not hang forever", queued.isCancelled)
        assertTrue(first.awaitBlocking())
        assertEquals(TextSize.PERCENT_150, settings.textSize.value)
    }

    @Test
    fun unknownKeysAndUnknownStringsSurviveALaterWrite() {
        val scripted = scripted()
        scripted.edit().putString("future_key", "keep").putString("theme_mode", "MIDNIGHT").commit()
        val settings = BrowserSettings(scripted, scope())
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.value)

        assertTrue(settings.setTextSize(TextSize.PERCENT_150).awaitBlocking())

        assertEquals("keep", scripted.getString("future_key", null))
        assertEquals("MIDNIGHT", scripted.getString("theme_mode", null))
        assertEquals("PERCENT_150", scripted.getString("text_size", null))
    }

    @Test
    fun anAbsentKnownKeyIsRestoredAfterAFailedWrite() {
        val scripted = scripted()
        val settings = BrowserSettings(scripted, scope())
        assertNull(scripted.getString("text_size", null))
        scripted.mode = ScriptedPreferences.Mode.Fail

        assertFalse(settings.setTextSize(TextSize.PERCENT_150).awaitBlocking())

        assertNull(scripted.getString("text_size", null))
        assertEquals(TextSize.PERCENT_100, settings.textSize.value)
    }

    private fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun newSettings(preferences: SharedPreferences): BrowserSettings =
        BrowserSettings(preferences, scope())

    private fun scripted(): ScriptedPreferences {
        val backing = context.getSharedPreferences("settings-test-scripted", Context.MODE_PRIVATE)
        check(backing.edit().clear().commit())
        return ScriptedPreferences(backing)
    }

    private fun Deferred<Boolean>.awaitBlocking(): Boolean = runBlocking { withTimeout(5_000) { await() } }
}

/**
 * A real Robolectric [SharedPreferences] with a scripted editor: [mode] decides whether
 * `commit()` acknowledges, fails or throws, and [latch] delays the acknowledgement. The delegate's
 * real `commit()` runs first, so a false or throwing result still leaves the in-memory map
 * mutated, exactly as Android's `SharedPreferencesImpl` does.
 */
private class ScriptedPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    var mode: Mode = Mode.Acknowledge
    var latch: CountDownLatch? = null
    var watchedKey: String? = null
    val watchedValues = mutableListOf<String?>()
    val commitThreads = ConcurrentLinkedQueue<Thread>()
    val commitEntered = CountDownLatch(1)

    override fun edit(): SharedPreferences.Editor {
        val editor = delegate.edit()
        return object : SharedPreferences.Editor by editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key == watchedKey) watchedValues += value
                editor.putString(key, value)
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key == watchedKey) watchedValues += null
                editor.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                editor.clear()
                return this
            }

            override fun commit(): Boolean {
                commitThreads += Thread.currentThread()
                commitEntered.countDown()
                latch?.await()
                editor.commit()
                return when (mode) {
                    Mode.Acknowledge -> true
                    Mode.Fail -> false
                    Mode.Throw -> throw IllegalStateException("scripted commit failure")
                }
            }

            override fun apply() {
                editor.apply()
            }
        }
    }

    enum class Mode { Acknowledge, Fail, Throw }
}
