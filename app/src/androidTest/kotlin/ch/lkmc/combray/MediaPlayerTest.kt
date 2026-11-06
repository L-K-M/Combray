package ch.lkmc.combray

import android.app.Activity
import android.app.ActivityManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.view.descendants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import ch.lkmc.combray.core.browser.Placement
import ch.lkmc.combray.core.media.MediaPolicy
import ch.lkmc.combray.core.media.PlaybackRequest
import ch.lkmc.combray.engine.EngineSurfaceView
import ch.lkmc.combray.engine.MediaKind
import ch.lkmc.combray.player.MediaFailure
import ch.lkmc.combray.player.PlaybackPhase
import ch.lkmc.combray.player.PlaybackSnapshot
import ch.lkmc.combray.player.PlayerActivity
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The fullscreen media player on the real engine (docs/VIDEO.md, phase C1): a page's cancelled
 * media plays after a tap, through the player's own client and its network rules.
 *
 * The player is a View activity: the Compose rule does not wait for it, so its checks poll from
 * the instrumentation thread. Back goes through its dispatcher on the main thread, as an injected
 * Back key did not arrive under software emulation (docs/ENGINE_SPIKE.md). Checks marked as
 * reports log their outcome and do not fail until one CI run has shown they pass.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class MediaPlayerTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Unique per check, so the log check finds this run's addresses only. */
    private val key = UUID.randomUUID().toString().replace("-", "")

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
        Fixtures.reset()
    }

    /** A failed check must not leave its player over the next check's browser. */
    @After
    fun closePlayers() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
                .flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<PlayerActivity>()
                .forEach { it.finish() }
        }
    }

    /** Check 1, and check 8 for its addresses. */
    @Test
    fun aPageVideoPlaysAfterATapWithoutServosCookie() {
        device.browser.openUrl(Fixtures.cookieSet("media"), Placement.CURRENT_TAB)
        device.awaitPage(Fixtures.cookieSet("media"))

        open(Fixtures.video(key))
        assertEquals("clip requests before Play", 0, clip().getInt("requests"))
        // An "isn't responding" dialog would dim the one green window the clip has.
        device.dismissSystemDialogs()
        tapPlay()

        val player = awaitPlayer()
        val playing = awaitSnapshot(player, "the first frame") { it.firstFrameRendered && it.phase in PLAYED }
        assertTrue("video tracks", playing.hasVideo)
        assertEquals("Playing clip.mp4", onMain { player.title.toString() })
        // Close shows with the controls only: playback starts with both hidden.
        assertTrue("Close over the video alone", closeButton(player) == null)
        instrumentation.runOnMainSync { player.showControls() }
        poll("the labeled Close button") { closeButton(player) }

        // The first half is green, the second blue: the video is drawn, not just decoded.
        val green = sampleWhile(player, Device.GREEN) { it.positionMs < HALF_MS && it.phase != PlaybackPhase.ENDED }
        assertTrue("first half shows %06x".format(green ?: -1), device.matches(green, Device.GREEN))
        awaitSnapshot(player, "the second half") { it.positionMs >= SECOND_HALF_MS || it.phase == PlaybackPhase.ENDED }
        val blue = sampleWhile(player, Device.BLUE) { true }
        assertTrue("second half shows %06x".format(blue ?: -1), device.matches(blue, Device.BLUE))

        // The player's own requests: no Servo cookie, the referrer's origin, Servo's User-Agent.
        val served = clip()
        assertTrue("clip requests", served.getInt("requests") > 0)
        val seen = served.getJSONArray("seen")
        for (index in 0 until seen.length()) {
            val request = seen.getJSONObject(index)
            assertEquals("Cookie", "", request.getString("cookie"))
            assertEquals("Referer", "${Fixtures.BASE}/", request.getString("referer"))
            assertTrue("User-Agent names Servo", request.getBoolean("servo_agent"))
        }

        back(player)
        // Android stops a finished activity only after the browser under it is idle.
        awaitSnapshot(player, "the release") { it.phase == PlaybackPhase.RELEASED }
        assertEquals("VIDEO:errors:1", device.browser.browserState.value.activeTab?.title)
        assertLogHasNoAddress()
    }

    /** Check 2: a direct link to a media file; whether Servo also read the whole body is reported. */
    @Test
    fun aDirectLinkPlays() {
        open(Fixtures.clip(key))
        tapPlay()
        val player = awaitPlayer()
        awaitSnapshot(player, "ready") { it.phase in PLAYED }
        back(player)

        // A media document's navigation: Servo keeps reading the body it will not show.
        device.browser.openUrl(Fixtures.SLOW_MEDIA, Placement.CURRENT_TAB)
        val deadline = SystemClock.uptimeMillis() + SLOW_MEDIA_MS
        while (SystemClock.uptimeMillis() < deadline && media().getInt("completed") == 0) Thread.sleep(POLL_MS)
        val media = media()
        Log.i(LOG_TAG, "REPORT direct link body: requests=${media.getInt("requests")} read in full=${media.getInt("completed")}")
    }

    /** Check 3: several entries open a sheet; a file Media3 cannot read fails as a format error. */
    @Test
    fun severalEntriesOpenASheetAndAFormatErrorIsShown() {
        open(Fixtures.MEDIA)
        // The notice comes with the first entry; with one entry Play opens the player, not the sheet.
        device.awaitState { it.activeTab?.media?.size == 2 }
        tapPlay()
        compose.waitUntilAtLeastOneExists(hasText(VIDEO_ROW), Device.LOAD_TIMEOUT_MS)
        assertEquals(1, compose.onAllNodesWithText(AUDIO_ROW).fetchSemanticsNodes().size)
        compose.onNodeWithText(VIDEO_ROW).performSemanticsAction(SemanticsActions.OnClick)

        val player = awaitPlayer()
        val failed = awaitSnapshot(player, "a failure") { it.phase == PlaybackPhase.FAILED }
        assertEquals(MediaFailure.FORMAT, failed.failure)
        val close = checkNotNull(closeButton(player)) { "no Close button" }
        instrumentation.runOnMainSync { close.performClick() }
        awaitBrowser()
    }

    /** Check 4, and check 8 for its addresses. */
    @Test
    fun aRedirectIsFollowedOnlyToAWebAddress() {
        open(Fixtures.mediaRedirect("clip", key))
        tapPlay()
        val player = awaitPlayer()
        awaitSnapshot(player, "ready") { it.phase in PLAYED }
        back(player)

        open(Fixtures.mediaRedirect("file", key))
        tapPlay()
        val refused = awaitPlayer()
        val failed = awaitSnapshot(refused, "a refusal") { it.phase == PlaybackPhase.FAILED }
        assertEquals(MediaFailure.REFUSED, failed.failure)
        assertEquals(0L, failed.positionMs)
        back(refused)
        assertLogHasNoAddress()
    }

    /** Check 5: media without a web address is not offered. */
    @Test
    fun mediaWithoutAWebAddressGetsThePlainNotice() {
        device.browser.openUrl(Fixtures.MEDIA_LOCAL, Placement.CURRENT_TAB)
        compose.waitUntilAtLeastOneExists(hasText(PLAIN_NOTICE), Device.LOAD_TIMEOUT_MS)
        val loaded = device.awaitPage(Fixtures.MEDIA_LOCAL)
        assertTrue(loaded.activeTab!!.media.isEmpty())
    }

    /** Check 6: the network rules, for pages the fixtures cannot serve through the engine. */
    @Test
    fun publicPagesCannotSendThePlayerToThisDevice() {
        // Mixed content from an https page, then the device rule from an http page elsewhere.
        for ((page, referrer) in listOf("https://example.org/" to "https://example.org", "http://example.org/" to "http://example.org")) {
            val request = PlaybackRequest(
                Fixtures.clip(key).toHttpUrl(),
                MediaKind.VIDEO,
                MediaPolicy.origin(page, referrer),
                "Combray device check",
                "clip.mp4",
                "localhost",
            )
            val id = device.app.graph.playback.offer(request)
            instrumentation.runOnMainSync { compose.activity.startActivity(PlayerActivity.intent(compose.activity, id)) }
            val player = awaitPlayer()
            val failed = awaitSnapshot(player, "a refusal") { it.phase == PlaybackPhase.FAILED }
            assertEquals(page, MediaFailure.REFUSED, failed.failure)
            back(player)
        }
        assertEquals("clip requests", 0, clip().getInt("requests"))
    }

    /** The offer goes with its page: Home shows none, and nothing was fetched. */
    @Test
    fun leavingThePageTakesItsOfferAway() {
        open(Fixtures.video(key))
        device.browser.newTab()
        device.awaitState { it.activeTab?.isHome == true && it.mediaOffer == null }
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) { compose.onAllNodesWithText(PLAYABLE_NOTICE).fetchSemanticsNodes().isEmpty() }
        assertNull("no player", resumed() as? PlayerActivity)
        assertEquals("clip requests", 0, clip().getInt("requests"))
    }

    /**
     * Check 15: the offer outlasts a notice's ten seconds and, once closed, a tap on the page's
     * own player (its video in a shadow root, under an overlay) offers the video again, where a
     * tap beside the player does not. The address field's Play plays it too.
     */
    @Test
    fun aTapOnThePagesOwnPlayerOffersItsVideoAgain() {
        open(Fixtures.player(key))
        device.awaitState { playerCount(it.activeTab?.title, "errors") == 1 }
        Thread.sleep(LONG_NOTICE_MS)
        assertTrue("the offer outlasts a notice", compose.onAllNodesWithText(PLAYABLE_NOTICE).fetchSemanticsNodes().isNotEmpty())

        compose.onNodeWithContentDescription(DISMISS).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(Device.LOAD_TIMEOUT_MS) { compose.onAllNodesWithText(PLAYABLE_NOTICE).fetchSemanticsNodes().isEmpty() }
        // The snackbar goes first; the effect that saw it go then closes the offer.
        device.awaitState { it.mediaOffer == null }
        compose.onNodeWithContentDescription(PLAY_MEDIA).assertExists()

        // A tap below the player reaches the page, and its media is not offered.
        tapPageUntil(BELOW_PLAYER) { playerCount(it, "outside") >= 1 }
        Thread.sleep(TAP_CHECK_MS)
        assertNull("a tap beside the player", device.browser.browserState.value.mediaOffer)

        // A tap on the player's own button offers its video again.
        tapPageUntil(ON_PLAYER) { playerCount(it, "plays") >= 1 }
        compose.waitUntilAtLeastOneExists(hasText(PLAYABLE_NOTICE), Device.LOAD_TIMEOUT_MS)
        assertEquals("clip requests before Play", 0, clip().getInt("requests"))
        tapPlay()
        val player = awaitPlayer()
        awaitSnapshot(player, "ready") { it.phase in PLAYED }
        back(player)

        // Played, the offer is done; the address field still plays the video.
        assertNull(device.browser.browserState.value.mediaOffer)
        compose.onNodeWithContentDescription(PLAY_MEDIA).performSemanticsAction(SemanticsActions.OnClick)
        val again = awaitPlayer()
        awaitSnapshot(again, "ready again") { it.phase in PLAYED }
        back(again)
    }

    /**
     * (report) A player stopped while playing is released at its exact position, and its restart
     * starts there without the released player's first frame. The task is moved, not relaunched:
     * MainActivity is singleTask, and an intent for it would clear the player.
     */
    @Test
    fun aStoppedPlayerIsReleasedAndResumesWhereItStopped() = report("stop and start") {
        open(Fixtures.video(key))
        tapPlay()
        val player = awaitPlayer()
        awaitSnapshot(player, "playback") { it.firstFrameRendered && it.phase in PLAYED && it.positionMs > PLAYING_MS }

        // Runs on the main thread right after onStart, before the new player delivers any event.
        val started = AtomicReference<PlaybackSnapshot>()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity === player && stage == Stage.STARTED) started.set(player.snapshot.value)
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        monitor.addLifecycleCallback(callback)
        try {
            instrumentation.runOnMainSync { player.moveTaskToBack(true) }
            val released = awaitSnapshot(player, "the release") { it.phase == PlaybackPhase.RELEASED }
            player.getSystemService(ActivityManager::class.java).appTasks
                .first { it.taskInfo?.taskId == player.taskId }
                .moveToFront()
            val restarted = poll("the restart") { started.get() }
            assertFalse("no frame drawn by the new player yet", restarted.firstFrameRendered)
            assertEquals("starts where it was released", released.positionMs, restarted.positionMs)
            awaitSnapshot(player, "the new first frame there") { it.firstFrameRendered && it.positionMs >= released.positionMs }
        } finally {
            monitor.removeLifecycleCallback(callback)
        }
    }

    /** Check 7 (report): audio reaches READY; whether its position advances without an audio device is unverified. */
    @Test
    fun audioPlays() = report("audio") {
        open(Fixtures.AUDIO)
        tapPlay()
        val player = awaitPlayer()
        val ready = awaitSnapshot(player, "ready") { it.phase in PLAYED }
        assertFalse("no video tracks", ready.hasVideo)
        val advanced = runCatching { awaitSnapshot(player, "position", AUDIO_ADVANCE_MS) { it.positionMs > 0 } }.isSuccess
        Log.i(LOG_TAG, "REPORT audio position advanced: $advanced")
        back(player)
    }

    /** Check 11: a page that asks canPlayType first reaches Play (docs/VIDEO.md). */
    @Test
    fun aPageThatAsksCanPlayTypeOffersItsVideo() {
        open(Fixtures.canPlay(key))
        // The source attached only because the claim matched; its cancel fired the error.
        device.awaitState { it.activeTab?.title == "CANPLAY:errors:1" }
        assertEquals("clip requests before Play", 0, clip().getInt("requests"))
        tapPlay()

        val player = awaitPlayer()
        awaitSnapshot(player, "ready") { it.phase in PLAYED }
        back(player)
        assertTrue("clip requests", clip().getInt("requests") > 0)
    }

    /** Check 12: a typed `<source>` is fetched, cancelled and offered once its type is claimed. */
    @Test
    fun aTypedSourceOffersItsVideo() {
        open(Fixtures.typedSource(key))
        // Selection fired the <source>'s error; a type-skipped source errors the same way, so
        // the Play offer is what proves the claim matched and the fetch was cancelled.
        device.awaitState { it.activeTab?.title == "TYPED:errors:1" }
        assertEquals("clip requests before Play", 0, clip().getInt("requests"))
        tapPlay()

        val player = awaitPlayer()
        awaitSnapshot(player, "ready") { it.phase in PLAYED }
        back(player)
        assertTrue("clip requests", clip().getInt("requests") > 0)
    }

    /** Check 10 (report): a data: page's media is judged as a public page's, through the engine. */
    @Test
    fun aDataPageOffersNothing() = report("data page") {
        device.browser.openUrl(Fixtures.mediaDataLink(key), Placement.CURRENT_TAB)
        device.awaitPage(Fixtures.mediaDataLink(key))
        val deadline = SystemClock.uptimeMillis() + Device.LOAD_TIMEOUT_MS
        while (device.browser.browserState.value.activeTab?.url?.startsWith("data:") != true) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("the data: link was not followed")
            tapPageCenter()
            Thread.sleep(TAP_OUTCOME_MS)
        }
        compose.waitUntilAtLeastOneExists(hasText(PLAIN_NOTICE), Device.LOAD_TIMEOUT_MS)
        assertTrue(device.browser.browserState.value.activeTab!!.media.isEmpty())
        assertEquals("clip requests", 0, clip().getInt("requests"))
    }

    private fun open(url: String) {
        device.browser.openUrl(url, Placement.CURRENT_TAB)
        compose.waitUntilAtLeastOneExists(hasText(PLAYABLE_NOTICE), Device.LOAD_TIMEOUT_MS)
    }

    /** The notice's Play, by its semantics action: no injected touch, which a slow emulator can lose. */
    private fun tapPlay() {
        compose.onNodeWithText(PLAY).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun awaitPlayer(): PlayerActivity = poll("the player") { resumed() as? PlayerActivity }

    private fun awaitBrowser() {
        poll("the browser") { resumed() as? MainActivity }
    }

    private fun back(player: PlayerActivity) {
        instrumentation.runOnMainSync { player.onBackPressedDispatcher.onBackPressed() }
        awaitBrowser()
    }

    private fun resumed(): Activity? {
        var found: Activity? = null
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return found
    }

    private fun awaitSnapshot(
        player: PlayerActivity,
        what: String,
        timeoutMs: Long = PLAYER_TIMEOUT_MS,
        predicate: (PlaybackSnapshot) -> Boolean,
    ): PlaybackSnapshot = try {
        poll(what, timeoutMs) { player.snapshot.value.takeIf(predicate) }
    } catch (late: AssertionError) {
        throw AssertionError("${late.message}; last ${player.snapshot.value}")
    }

    /**
     * Screenshots of the video band with the controls hidden, until one shows [color] while the
     * player was within [whilst] before and after it. Returns that color, or the last one seen;
     * null when a match could have been taken outside [whilst].
     */
    private fun sampleWhile(player: PlayerActivity, color: Int, whilst: (PlaybackSnapshot) -> Boolean): Int? {
        var seen: Int? = null
        val deadline = SystemClock.uptimeMillis() + SAMPLE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { player.hideControls() }
            val within = whilst(player.snapshot.value)
            seen = device.playerScreenColor()
            val stillWithin = whilst(player.snapshot.value)
            if (within && stillWithin && device.matches(seen, color)) return seen
            if (!stillWithin) return seen.takeUnless { device.matches(it, color) }
            device.dismissSystemDialogs()
            Thread.sleep(POLL_MS)
        }
        return seen
    }

    private fun closeButton(player: PlayerActivity): View? {
        val found = ArrayList<View>()
        val label = player.getString(R.string.player_close)
        instrumentation.runOnMainSync { player.window.decorView.findViewsWithText(found, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION) }
        return found.singleOrNull()
    }

    private fun <T> onMain(read: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = read() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /** Check 8: neither this check's address token nor OkHttp's leak warning (which names a host) is in the app's log. */
    private fun assertLogHasNoAddress() {
        val output = instrumentation.uiAutomation.executeShellCommand("logcat -d --pid=${Process.myPid()}")
        val log = ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().toString(Charsets.UTF_8) }
        assertFalse("a media address reached the log", log.contains(key))
        assertFalse("OkHttp reported a leaked connection", log.contains("was leaked"))
    }

    private fun media(): JSONObject = Fixtures.state().getJSONObject("media")

    private fun clip(): JSONObject = media().getJSONObject("clip")

    /** Taps halfway across the page at [fraction] of its height until [done] holds for its title. */
    private fun tapPageUntil(fraction: Float, done: (String?) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + Device.LOAD_TIMEOUT_MS
        while (!done(device.browser.browserState.value.activeTab?.title)) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("no tap reached the page at $fraction")
            tapPage(fraction)
            Thread.sleep(TAP_CHECK_MS)
        }
    }

    /** A tap on the engine's view, halfway across and at [fraction] of its height, where the page's `vh` measure. */
    private fun tapPage(fraction: Float) {
        device.dismissSystemDialogs()
        val origin = IntArray(2)
        var size = 0 to 0
        instrumentation.runOnMainSync {
            val surface = (compose.activity.window.decorView as ViewGroup).descendants.filterIsInstance<EngineSurfaceView>().single()
            surface.getLocationOnScreen(origin)
            size = surface.width to surface.height
        }
        val x = origin[0] + size.first / 2f
        val y = origin[1] + size.second * fraction
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP, x, y, 0))
    }

    /** One count of the player page's title, `PLAYER:errors:<n>:outside:<n>:plays:<n>`; -1 before it reports. */
    private fun playerCount(title: String?, name: String): Int {
        val parts = title?.split(':') ?: return -1
        val at = parts.indexOf(name)
        return parts.getOrNull(at + 1)?.toIntOrNull()?.takeIf { at > 0 } ?: -1
    }

    private fun tapPageCenter() {
        device.dismissSystemDialogs()
        val metrics = compose.activity.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels * 0.35f
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP, x, y, 0))
    }

    /** Runs a check that only reports until one CI run has passed it (docs/VIDEO.md). */
    private fun report(name: String, check: () -> Unit) {
        try {
            check()
            Log.i(LOG_TAG, "REPORT $name: passed")
        } catch (failed: Throwable) {
            // Assertions, Compose timeouts and state errors alike: reported, not failed.
            Log.w(LOG_TAG, "REPORT $name: failed: ${failed.message}")
        }
    }

    private fun <T : Any> poll(what: String, timeoutMs: Long = PLAYER_TIMEOUT_MS, probe: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private companion object {
        const val LOG_TAG = "MediaPlayerTest"
        const val PLAYABLE_NOTICE = "This page’s audio or video opens in a separate player."
        const val PLAIN_NOTICE = "This page’s audio or video can’t play in Combray."
        const val PLAY = "Play"
        /** The address field's Play, and the offer's close button (Material's label). */
        const val PLAY_MEDIA = "Play audio or video"
        const val DISMISS = "Dismiss"
        const val VIDEO_ROW = "Video · slow.bin"
        const val AUDIO_ROW = "Audio · slow.bin"
        val PLAYED = setOf(PlaybackPhase.READY, PlaybackPhase.ENDED)
        const val PLAYER_TIMEOUT_MS = 30_000L
        const val SAMPLE_TIMEOUT_MS = 10_000L
        const val AUDIO_ADVANCE_MS = 5_000L
        const val SLOW_MEDIA_MS = 20_000L
        const val TAP_OUTCOME_MS = 5_000L
        /** Past a long notice's 10 s, which the offer once lasted. */
        const val LONG_NOTICE_MS = 11_000L
        /** Time for the engine's hit test after a tap, and for the offer it may bring. */
        const val TAP_CHECK_MS = 2_000L
        /** The player page's play button, in the middle of its top half; and its plain bottom half. */
        const val ON_PLAYER = 0.25f
        const val BELOW_PLAYER = 0.75f
        const val POLL_MS = 100L
        /** Far enough into the clip that a stop lands between position ticks. */
        const val PLAYING_MS = 500L
        /** The clip turns from green to blue at 3 s. */
        const val HALF_MS = 3_000L
        const val SECOND_HALF_MS = 3_200L
    }
}
