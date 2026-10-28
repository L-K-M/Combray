package ch.lkmc.combray

import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.BrowserState
import ch.lkmc.combray.core.browser.EngineStatus
import ch.lkmc.combray.core.browser.HomeSectionVisibility
import ch.lkmc.combray.core.browser.RecentItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

enum class Orientation { PORTRAIT, LANDSCAPE }

/** Who follows a fixture's link: the user's tap or the page's script. */
enum class LinkTrigger { TAP, SCRIPT }

/** Where a fixture's link opens: in its own page or in a new window (`target=_blank`). */
enum class LinkTarget { SAME_WINDOW, NEW_WINDOW }

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

    /** Titled `XP:ok` once FontFace, adoptedStyleSheets and IndexedDB work, `XP:fail:<why>` otherwise. */
    const val APIS = "$BASE/apis.html"

    /** Answered once per [key] (title [ONCE_TITLE]); later requests fail like a lost connection. */
    fun once(key: String) = "$BASE/once.html?k=$key"

    const val ONCE_TITLE = "ONCE"

    /** Titled `SCREEN:<screen.width>:<screen.availWidth>:<outerWidth>:<innerWidth>` as the page reads them. */
    const val SCREEN = "$BASE/screen.html"

    /**
     * A page-filling [Device.OCHRE] link to another app's link or a file ([to]: market, intent,
     * badfallback or file), followed on a tap or, with [LinkTrigger.SCRIPT], by the page's own
     * script, into [target]. Titled [EXTERNAL_STILL_HERE] 3 s later if the page is still shown.
     */
    fun external(to: String, trigger: LinkTrigger = LinkTrigger.TAP, target: LinkTarget = LinkTarget.SAME_WINDOW) =
        "$BASE/external.html?to=$to" +
            (if (trigger == LinkTrigger.SCRIPT) "&auto=1" else "") +
            (if (target == LinkTarget.NEW_WINDOW) "&blank=1" else "")

    const val EXTERNAL_STILL_HERE = "EXTERNAL:still-here"

    /** [Device.GREEN] in a light appearance, [Device.BLUE] when the page is told the theme is dark. */
    const val SCHEME = "$BASE/scheme.html"

    /** Kimi's :has() DOM queries; stylesheet support stays disabled. */
    const val HAS_SELECTOR = "$BASE/has-selector.html"

    /** A page without any text. */
    const val EMPTY = "$BASE/empty.html"

    /**
     * A one-page PDF (`application/pdf`) reading PDF-FIXTURE-TEXT. Servo cannot show it: it
     * commits a stand-in page, "Unknown content type (application/pdf).", with no title.
     */
    const val PDF = "$BASE/document.pdf"

    /** A page the server answers only after several seconds. */
    const val SLOW = "$BASE/slow.html"

    /** A `<video>` and an `<audio>` element; titled `MEDIA:errors:2` once both failed. */
    const val MEDIA = "$BASE/media.html"

    /** 512 KiB served over about eight seconds as `video/mp4`; "media" in [state] counts its requests. */
    const val SLOW_MEDIA = "$BASE/media/slow.bin"

    /** Sets the fixture's cookie in Servo's profile. */
    fun cookieSet(value: String) = "$BASE/cookie-set?v=$value"

    /** A `<video>` playing [clip] with [key]; titled `VIDEO:errors:<n>`. */
    fun video(key: String) = "$BASE/video.html?k=$key"

    /** The 6 s fixture clip, [Device.GREEN] then [Device.BLUE]; "media" › "clip" in [state] records its requests. */
    fun clip(key: String) = "$BASE/media/clip.mp4?k=$key"

    /** An `<audio>` playing a 3 s tone; titled `AUDIO:errors:<n>`. */
    const val AUDIO = "$BASE/audio.html"

    /** A `<video>` whose address redirects to the clip ([to] `clip`) or to a `file:` address ([to] `file`). */
    fun mediaRedirect(to: String, key: String) = "$BASE/media-redirect.html?to=$to&k=$key"

    /** A `data:` and a `file:` `<video>`: media without a web address. */
    const val MEDIA_LOCAL = "$BASE/media-local.html"

    /** A page-filling link to a `data:` page whose `<video>` is the clip with [key]. */
    fun mediaDataLink(key: String) = "$BASE/media-data-link.html?k=$key"

    /** Titled `ZOOM:<innerWidth>:<devicePixelRatio>` on load and on every resize. */
    const val ZOOM = "$BASE/zoom.html"

    /** A transparent fixed drawer over the page, optionally held loading by an image. */
    const val FIXED_SCROLL = "$BASE/fixed-scroll.html"

    fun finishScrollLoad() = request("POST", "/_admin/finish-scroll-load")

    /** "combraneedle" three times far apart; titled `FIND:<scrollY>:<selected text>`. */
    const val FIND = "$BASE/find.html"

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
    val settings get() = app.graph.settings

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

    /** Fixture isolation: a failed editor check must not hide another check's automatic links. */
    internal fun resetHome() = runBlocking {
        settings.setHomeContinue(HomeSectionVisibility.SHOWN)
        settings.setHomeFrequentSites(HomeSectionVisibility.SHOWN)
        val home = app.graph.home
        val state = home.state(flowOf(emptyList())).first()
        state.pins.forEach { home.unpin(it.id) }
        state.hiddenSites.forEach { home.restoreSite(it) }
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
        return sample(xFraction, yFraction)
    }

    /**
     * RGB at a point of the screen (fractions of it) while the media player shows: a View
     * activity, so there is no Compose frame to advance first. The middle is in the video band.
     */
    fun playerScreenColor(xFraction: Float = 0.5f, yFraction: Float = 0.5f): Int? = sample(xFraction, yFraction)

    private fun sample(xFraction: Float, yFraction: Float): Int? {
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
