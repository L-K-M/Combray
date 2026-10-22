package ch.lkmc.combray

import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.BrowserState
import ch.lkmc.combray.core.browser.EngineStatus
import ch.lkmc.combray.core.browser.RecentItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

enum class Orientation { PORTRAIT, LANDSCAPE }

/** Synthetic pages and fake services from testing/fixtures/server.py, through `adb reverse`. */
object Fixtures {
    /** Pages (through `adb reverse`). */
    const val BASE = "http://localhost:8766"

    /**
     * The same server as the services' host: the emulator reaches the host's loopback at 10.0.2.2.
     * A host of its own, because a service's own pages are never captured for Hister.
     */
    const val SERVICES = "http://10.0.2.2:8766"
    const val KARAKEEP_ROOT = "$SERVICES/keep"
    const val KARAKEEP_KEY = "fixture-key"
    const val HISTER_ROOT = "$SERVICES/hister"
    const val HISTER_TOKEN = "fixture-token"

    fun solid(color: Int) = "$BASE/solid/%06x.html".format(color and 0xFFFFFF)

    /** A news-style page with navigation, an aside and a footer around its article. */
    const val ARTICLE = "$BASE/article.html"

    /** Titled `IO:visible` once IntersectionObserver reports its text on screen, `IO:missing` without it. */
    const val INTERSECTION = "$BASE/intersection.html"

    /** Titled `WA:ok` once Element.animate() behaves, `WA:missing` without it, `WA:fail:<why>` otherwise. */
    const val ANIMATE = "$BASE/animate.html"

    /** Answered once per [key] (title [ONCE_TITLE]); later requests fail like a lost connection. */
    fun once(key: String) = "$BASE/once.html?k=$key"

    const val ONCE_TITLE = "ONCE"

    /** Titled `SCREEN:<screen.width>:<screen.availWidth>:<outerWidth>:<innerWidth>` as the page reads them. */
    const val SCREEN = "$BASE/screen.html"

    /** A page without any text. */
    const val EMPTY = "$BASE/empty.html"

    /** A page the server answers only after several seconds. */
    const val SLOW = "$BASE/slow.html"

    /** A `<video>` and an `<audio>` element; titled `MEDIA:errors:2` once both failed. */
    const val MEDIA = "$BASE/media.html"

    /** The title the fixture gives a solid page; only the engine reports it, once loaded. */
    fun title(color: Int) = "SOLID-%06x".format(color and 0xFFFFFF)

    fun state(): JSONObject = JSONObject(request("GET", "/_admin/state"))

    fun reset() = request("POST", "/_admin/reset")

    fun fault(service: String, kind: String, count: Int) = request("POST", "/_admin/fault?service=$service&kind=$kind&count=$count")

    private fun request(method: String, path: String): String {
        val connection = URL(BASE + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (method == "POST") {
            connection.doOutput = true
            connection.outputStream.close()
        }
        return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.also { connection.disconnect() }
    }
}

/**
 * Waits and screen checks against the real engine. Timeouts allow for a software-emulated device.
 * [frame] advances the Compose test clock by one frame: under a Compose test rule the UI only
 * recomposes when the test drives it, so a screenshot would otherwise show a stale UI.
 */
class Device(private val frame: () -> Unit = {}) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app: CombrayApplication = ApplicationProvider.getApplicationContext()
    val browser get() = app.graph.browser
    val integrations get() = app.graph.integrations

    fun awaitState(timeoutMs: Long = LOAD_TIMEOUT_MS, predicate: (BrowserState) -> Boolean): BrowserState =
        runBlocking { withTimeout(timeoutMs) { browser.browserState.first(predicate) } }

    fun awaitEngine() = awaitState { it.engine == EngineStatus.READY }

    /** Waits until the focused tab committed [url] and finished loading. */
    fun awaitPage(url: String, timeoutMs: Long = LOAD_TIMEOUT_MS): BrowserState =
        awaitState(timeoutMs) { it.activeTab?.url == url && it.activeTab?.loading == false }

    fun recent(): List<RecentItem> = runBlocking { browser.recent().first() }

    fun idle() = runBlocking { browser.awaitIdle() }

    /** Closes every tab so each check starts from the internal home. */
    fun closeAllTabs() {
        idle()
        recent().filterIsInstance<RecentItem.OpenTab>().forEach { browser.closeTab(it.tabId) }
        idle()
        awaitState { it.activeTab == null }
    }

    /** Waits until screenshots have the [orientation]'s shape, so a pre-rotation frame never counts. */
    fun awaitOrientation(orientation: Orientation, timeoutMs: Long = SCREEN_TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val landscape = screenshot != null && screenshot.width > screenshot.height
            screenshot?.recycle()
            if (landscape == (orientation == Orientation.LANDSCAPE)) return
            Thread.sleep(1_000)
        }
        throw AssertionError("the screen did not turn to $orientation")
    }

    /** RGB at a point of the page area (fractions of the screen), from a real screenshot. */
    fun screenColor(xFraction: Float = 0.5f, yFraction: Float = 0.35f): Int? {
        frame()
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return null
        val pixel = screenshot.getPixel((screenshot.width * xFraction).toInt(), (screenshot.height * yFraction).toInt())
        screenshot.recycle()
        return pixel and RGB_MASK
    }

    /** Waits until the page area shows [color]; returns the last color seen. */
    fun awaitScreen(color: Int, timeoutMs: Long = SCREEN_TIMEOUT_MS): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen = screenColor()
        while (!matches(seen, color) && System.currentTimeMillis() < deadline) {
            dismissSystemDialogs()
            Thread.sleep(1_000)
            seen = screenColor()
        }
        return seen
    }

    /**
     * Under software emulation System UI misses its ANR deadline, and its "isn't responding"
     * dialog covers the app, dims the screen and takes input focus. It is not part of Combray:
     * close it (error dialogs close on this broadcast) before looking at or touching the app.
     */
    fun dismissSystemDialogs() {
        val output = instrumentation.uiAutomation.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    fun matches(seen: Int?, color: Int): Boolean {
        if (seen == null) return false
        return abs(Color.red(seen) - Color.red(color)) <= TOLERANCE &&
            abs(Color.green(seen) - Color.green(color)) <= TOLERANCE &&
            abs(Color.blue(seen) - Color.blue(color)) <= TOLERANCE
    }

    companion object {
        const val LOAD_TIMEOUT_MS = 240_000L
        // The first frame of a new process waits for engine start-up and WebRender's first paint,
        // which take over 30 s on a software-emulated device. Only failing checks wait this long.
        const val SCREEN_TIMEOUT_MS = 120_000L
        const val TOLERANCE = 16
        const val RGB_MASK = 0xFFFFFF
        const val RED = 0xb03a2e
        const val GREEN = 0x2e6b4f
        const val BLUE = 0x3b4f8a
        const val OCHRE = 0x8a5a2b
        /** opener.html's background: no other screen of the app or the fixtures uses it. */
        const val VIOLET = 0x5b2a86
    }
}
