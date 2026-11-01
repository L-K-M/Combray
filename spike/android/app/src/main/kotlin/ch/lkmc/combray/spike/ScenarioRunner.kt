package ch.lkmc.combray.spike

import android.graphics.Bitmap
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import ch.lkmc.combray.engine.NativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val DEFAULT_CAPTURE_BYTES = 256 * 1024
const val CAPTURE_TIMEOUT_MS = 30_000

/**
 * M0 checks against the real engine. `core` runs inside the app; lifecycle phases are
 * triggered by scripts/m0/run-checks.sh between host actions (Home, rotation, process kill).
 *
 * Fixture origins (served by spike/fixtures/server.py through `adb reverse`):
 * [SITE] and [OTHER_SITE] are different hosts, so Servo treats them as different sites.
 */
class ScenarioRunner(private val app: HarnessApplication) {
    private val engine get() = app.engine
    private val events get() = app.engine.events
    private val main = Handler(Looper.getMainLooper())
    private val expectationsFile get() = File(app.filesDir, "m0-expectations.json")
    private var nextId = 10_000L
    /** Navigations of the selected tab whose document never reached the screen. */
    private val screenMismatches = JSONArray()
    private var screenChecks = 0
    /** Navigations of the selected tab that could not be traced (no swatch, page broke the probe, same color). */
    private var screenSkips = 0
    /** Event count when the previous scenario ended: host actions (Home, rotation) happen after it. */
    @Volatile private var lastScenarioEnd = 0

    @Volatile var active = false
        private set

    class CheckTimeout(what: String) : Exception("timed out waiting for $what")

    data class Loaded(val tab: Long, val generation: Long, val document: Long, val url: String)

    fun request(name: String, argument: String?) {
        if (active) {
            Log.w(LOG_TAG, "scenario already running; ignoring $name")
            return
        }
        active = true
        Thread({ run(name, argument) }, "m0-scenario").start()
    }

    private fun run(name: String, argument: String?) {
        val report = Report(app, name)
        screenChecks = 0
        screenSkips = 0
        while (screenMismatches.length() > 0) screenMismatches.remove(0)
        try {
            awaitEngineReady()
            when (name) {
                "core" -> core(report)
                "page-probe" -> probePage(report, requireNotNull(argument))
                "diag-paint" -> diagnosePaint(report)
                "diag-tail" -> diagnoseCoreTail(report)
                "diag-tail2" -> diagnoseLargePageDwell(report, argument)
                "screen-check" -> screenCheck(report, argument ?: "now")
                "resume-check" -> resumeCheck(report)
                "rotation-check" -> rotationCheck(report)
                "persist-set" -> persistSet(report, argument ?: "unset")
                "persist-check" -> persistCheck(report, argument ?: "unspecified")
                "shutdown" -> shutdown(report)
                else -> report.record("unknown-request", CheckStatus.FAILED, JSONObject().put("name", name))
            }
        } catch (failure: Throwable) {
            Log.e(LOG_TAG, "scenario $name aborted", failure)
            report.record("$name-aborted", CheckStatus.FAILED, JSONObject().put("error", failure.toString()))
        } finally {
            report.finish()
            lastScenarioEnd = events.size()
            active = false
        }
    }

    private fun probePage(report: Report, address: String) {
        val tab = (engine.tabs().maxOfOrNull { it.tab } ?: 0L) + 1
        engine.createTab(tab, address)
        select(tab)
        SystemClock.sleep(60_000)
        val probe = evaluate(tab, """
            (() => ({
                origin: location.origin,
                ready: document.readyState,
                title: document.title,
                viewport: [innerWidth, innerHeight],
                scroll: [scrollX, scrollY, document.documentElement.scrollHeight],
                elements: Array.from(document.querySelectorAll(
                    'body,main,header,section,#main-content,#status-card,#turnstile-container,iframe'
                )).slice(0, 30).map(el => {
                    const box = el.getBoundingClientRect();
                    const style = getComputedStyle(el);
                    return {
                        tag: el.tagName, id: el.id.slice(0, 64),
                        box: [box.x, box.y, box.width, box.height],
                        display: style.display, visibility: style.visibility,
                        opacity: style.opacity, color: style.color,
                        background: style.backgroundColor, position: style.position,
                        transform: style.transform, overflow: style.overflow
                    };
                })
            }))()
        """.trimIndent())
        File(app.filesDir, "page-probe.json").writeText(probe.toString())
        report.record("page-probe-captured", CheckStatus.PASSED,
            JSONObject().put("observations_only", true))
        report.screenshot("page-probe")
    }

    // --- Core scenario ----------------------------------------------------------------------------

    private fun core(report: Report) {
        val tab1 = 1L
        val tab2 = 2L

        // Views: two independent Servo WebViews in one surface.
        val createdFrom = mark()
        val a1 = createTab(tab1, url("a.html"))
        select(tab1)
        val b2 = createTab(tab2, url("b.html"))
        val created = events.since(createdFrom).filter { it.type == "tab_created" }
        val webviews = created.map { it.getString("webview") }.toSet()
        val painted1 = awaitPainted(tab1, createdFrom)
        val screen1 = awaitDisplayed(tab1, COLOR_A)
        report.passIf(
            "views-two-independent-webviews",
            webviews.size == 2 && a1.document != b2.document && a1.generation != b2.generation,
            JSONObject()
                .put("webviews", JSONArray(webviews.toList()))
                .put("tab1", loadedJson(a1)).put("tab2", loadedJson(b2)),
        )
        report.passIf(
            "engine-renders-on-android",
            painted1 != null && matches(screen1, COLOR_A),
            JSONObject().put("painted_event", painted1 ?: JSONObject.NULL).put("screen", hex(screen1)),
        )
        report.screenshot("tab1-a")

        check(report, "views-switching") {
            // Paint waits start at the selection event, so earlier paints cannot satisfy them.
            val selected2 = select(tab2)
            val shown2 = awaitPainted(tab2, selected2)
            val screen2 = awaitDisplayed(tab2, COLOR_B)
            report.screenshot("tab2-b")
            val switchedBack = select(tab1)
            val shown1 = awaitPainted(tab1, switchedBack)
            val screen1 = awaitDisplayed(tab1, COLOR_A)
            val hiddenPaints = events.since(switchedBack).count { it.type == "painted" && it.isFor(tab2) }
            report.passIf(
                "views-switching",
                shown2 != null && shown1 != null && hiddenPaints == 0 &&
                    matches(screen2, COLOR_B) && matches(screen1, COLOR_A),
                JSONObject().put("tab2_painted_when_selected", shown2 != null)
                    .put("tab1_painted_when_reselected", shown1 != null)
                    .put("tab2_paint_events_while_hidden", hiddenPaints)
                    .put("screen_when_tab2_selected", hex(screen2)).put("screen_when_tab1_selected", hex(screen1)),
            )
        }

        var c1: Loaded? = null
        check(report, "views-independent-navigation") {
            val tab2Before = engine.tab(tab2)
            c1 = navigate(tab1, url("c.html"))
            val screen = awaitDisplayed(tab1, COLOR_C)
            val tab2After = engine.tab(tab2)
            val tab2Url = evaluate(tab2, "document.URL")
            report.passIf(
                "views-independent-navigation",
                tab2Before?.document == tab2After?.document && tab2Url == url("b.html") &&
                    c1!!.document != a1.document && matches(screen, COLOR_C),
                JSONObject().put("tab1", loadedJson(c1!!)).put("tab2_document_unchanged", tab2Before?.document == tab2After?.document)
                    .put("tab2_url", tab2Url).put("screen", hex(screen)),
            )
        }

        check(report, "views-back-forward") {
            val backFrom = mark()
            NativeEngine.goBack(tab1)
            val back = awaitCommit(backFrom, tab1, url("a.html"))
            val forwardFrom = mark()
            NativeEngine.goForward(tab1)
            val forward = awaitCommit(forwardFrom, tab1, url("c.html"))
            val tab2Events = events.since(backFrom).count { it.isFor(tab2) && it.type.endsWith("committed") }
            // Back and forward must return the cached documents, not reload them.
            val backReused = back.getLong("document") == a1.document
            val forwardReused = forward.getLong("document") == c1?.document
            report.passIf(
                "views-back-forward",
                tab2Events == 0 && backReused && forwardReused,
                JSONObject().put("back", back).put("forward", forward)
                    .put("back_document_reused", backReused).put("forward_document_reused", forwardReused)
                    .put("tab2_commits_during_traversal", tab2Events),
            )
        }

        check(report, "views-same-url-tabs-independent") {
            val sameFrom = mark()
            NativeEngine.goBack(tab1)
            val t1 = awaitCommit(sameFrom, tab1, url("a.html"))
            val t2 = navigate(tab2, url("a.html"))
            evaluate(tab1, "window.m0Mark = 'set-in-tab-1'; window.m0Mark")
            val markInTab2 = evaluate(tab2, "typeof window.m0Mark")
            val nonce1 = evaluate(tab1, "window.m0Nonce")
            val nonce2 = evaluate(tab2, "window.m0Nonce")
            // Simultaneous captures: each result must carry its own tab, document and nonce.
            val captureFrom = mark()
            val id1 = startCapture(tab1, t1.getLong("document"))
            val id2 = startCapture(tab2, t2.document)
            val cap1 = awaitCapture(captureFrom, id1)
            val cap2 = awaitCapture(captureFrom, id2)
            val text1 = cap1.optJSONObject("content")?.optString("text").orEmpty()
            val text2 = cap2.optJSONObject("content")?.optString("text").orEmpty()
            report.passIf(
                "views-same-url-tabs-independent",
                t1.getLong("document") != t2.document && markInTab2 == "undefined" && nonce1 != nonce2 &&
                    cap1.status == "captured" && cap2.status == "captured" &&
                    text1.contains("NONCE-$nonce1") && text2.contains("NONCE-$nonce2") &&
                    !text1.contains("NONCE-$nonce2") && cap1.getLong("document") == t1.getLong("document") &&
                    cap2.getLong("document") == t2.document,
                JSONObject().put("tab1_document", t1.getLong("document")).put("tab2_document", t2.document)
                    .put("global_in_tab2", markInTab2).put("nonce1", nonce1).put("nonce2", nonce2)
                    .put("capture1", summary(cap1)).put("capture2", summary(cap2)),
            )
        }

        // Measured early, with two small pages, as a baseline rather than after a long session.
        memoryChecks(report, tab1)
        check(report, "navigation-taxonomy") { navigationTaxonomy(report, tab1) }
        inputChecks(report, tab1)
        captureChecks(report, tab1, tab2)
        hiddenViewChecks(report, tab1, tab2)

        check(report, "views-close-one-keeps-other") {
            val closeFrom = mark()
            NativeEngine.closeTab(tab2)
            await(closeFrom, "tab_closed 2") { it.type == "tab_closed" && it.isFor(tab2) }
            val after = navigate(tab1, url("b.html"))
            val screen = awaitDisplayed(tab1, COLOR_B)
            val capture = awaitCapture(mark(), startCapture(tab1, after.document))
            SystemClock.sleep(3_000)
            // Nothing about the closed tab may arrive after its close, captures included.
            val lateTab2 = events.since(closeFrom).filter { it.isFor(tab2) && it.type != "tab_closed" }
            report.passIf(
                "views-close-one-keeps-other",
                capture.status == "captured" && lateTab2.isEmpty() && engine.tab(tab2) == null &&
                    matches(screen, COLOR_B),
                JSONObject().put("tab1_after_close", loadedJson(after)).put("capture", summary(capture))
                    .put("tab2_events_after_close", JSONArray(lateTab2)).put("screen", hex(screen)),
            )
        }

        report.passIf("display-follows-navigation", screenChecks > 0 && screenMismatches.length() == 0,
            JSONObject().put("navigations_checked", screenChecks).put("navigations_not_traceable", screenSkips)
                .put("mismatches", screenMismatches))

        // Remember state for the lifecycle phases that follow.
        val state = JSONObject()
        engine.tab(tab1)?.let { state.put("tab1_document", it.document).put("tab1_url", it.url) }
        File(app.filesDir, "m0-core-state.json").writeText(state.toString())
        report.screenshot("core-end")
    }

    // --- Navigation taxonomy -------------------------------------------------------------------

    /** Which history event and document ID each kind of navigation produces. */
    private fun navigationTaxonomy(report: Report, tab: Long) {
        val base = navigate(tab, url("a.html"))
        fun next(from: Int, what: String) = await(from, what) {
            it.isFor(tab) && it.type in HISTORY_EVENTS
        }
        fun observed(event: JSONObject) = JSONObject().put("event", event.type)
            .put("document", event.getLong("document")).put("url", event.getString("url"))

        // Page-initiated fragment change: the same document.
        val fragmentFrom = mark()
        evaluate(tab, "location.hash = 'section'; location.href")
        val fragment = next(fragmentFrom, "fragment navigation")

        val pushFrom = mark()
        evaluate(tab, "history.pushState({}, '', '/a.html?pushed=1'); location.href")
        val pushed = next(pushFrom, "pushState")

        val replaceFrom = mark()
        evaluate(tab, "history.replaceState({}, '', location.href); location.href")
        val replaced = events.await(replaceFrom, INPUT_TIMEOUT_MS) { it.isFor(tab) && it.type in HISTORY_EVENTS }

        // Embedder-initiated load of a URL that differs only in its fragment (recorded, not judged).
        val embedderFragmentFrom = mark()
        NativeEngine.navigate(tab, nextId++, url("a.html?pushed=1#other"))
        val embedderFragment = events.await(embedderFragmentFrom, LOAD_TIMEOUT_MS) {
            it.isFor(tab) && it.type in HISTORY_EVENTS
        }
        // Let that load finish, so its events cannot be taken for the failed load's.
        if (embedderFragment?.type == "document_committed") {
            events.await(embedderFragmentFrom, LOAD_TIMEOUT_MS) {
                it.type == "load_status" && it.isFor(tab) && it.optString("phase") == "complete" &&
                    it.optLong("document") == embedderFragment.getLong("document")
            }
        }

        val failedFrom = mark()
        NativeEngine.navigate(tab, nextId++, UNREACHABLE_URL)
        val failed = events.await(failedFrom, LOAD_TIMEOUT_MS) {
            it.isFor(tab) && it.type in HISTORY_EVENTS && it.getString("url") == UNREACHABLE_URL
        }
        val failedComplete = failed?.let { commit ->
            events.await(failedFrom, LOAD_TIMEOUT_MS) {
                it.isFor(tab) && it.type == "load_status" && it.optString("phase") == "complete" &&
                    it.optLong("document") == commit.getLong("document") && it.optString("attribution") != "stale"
            }
        }
        val failedTitle = if (failed != null) evaluate(tab, "document.title") else null

        report.passIf("navigation-taxonomy",
            fragment.type == "same_document_navigation" && fragment.getLong("document") == base.document &&
                pushed.type == "same_document_navigation" && pushed.getLong("document") == base.document &&
                replaced != null && replaced.type == "history_updated" && replaced.getLong("document") == base.document &&
                failed != null && failed.type == "document_committed" && failed.getLong("document") != base.document &&
                failedComplete != null && failedTitle == SERVO_ERROR_PAGE_TITLE,
            JSONObject().put("base", loadedJson(base))
                .put("fragment", observed(fragment)).put("push_state", observed(pushed))
                .put("replace_state_same_url", replaced?.let(::observed) ?: JSONObject.NULL)
                .put("embedder_fragment_load", embedderFragment?.let {
                    observed(it).put("same_document", it.getLong("document") == pushed.getLong("document"))
                } ?: JSONObject.NULL)
                .put("failed_load", failed?.let {
                    observed(it).put("title", failedTitle).put("load_complete", failedComplete != null)
                } ?: JSONObject.NULL))
    }

    // --- Input -----------------------------------------------------------------------------------

    private fun inputChecks(report: Report, tab: Long) {
        val page = try {
            navigate(tab, url("input.html"))
        } catch (failure: Exception) {
            report.record("input-page", CheckStatus.FAILED, error(failure))
            for (id in INPUT_CHECKS) {
                report.record(id, CheckStatus.NOT_RUN, JSONObject().put("reason", "input fixture did not load"))
            }
            return
        }
        select(tab)
        SystemClock.sleep(2_000)

        check(report, "input-touch-tap") {
            val (x, y) = elementCenter(tab, "tap")
            tap(x, y)
            val taps = waitForValue(tab, "window.tapCount") { (it as? Number)?.toInt() == 1 }
            report.passIf("input-touch-tap", (taps as? Number)?.toInt() == 1,
                JSONObject().put("tap_px", JSONArray(listOf(x, y))).put("tapCount", taps))
        }

        check(report, "input-keys-and-ime-visibility") {
            val from = mark()
            val (x, y) = elementCenter(tab, "field")
            tap(x, y)
            val imeShown = events.await(from, LOAD_TIMEOUT_MS) { it.type == "ime_shown" && it.isFor(tab) }
            val focused = evaluate(tab, "document.activeElement && document.activeElement.id")
            typeKeys("abc")
            val value = waitForValue(tab, "document.getElementById('field').value") { it == "abc" }
            report.passIf("input-keys-and-ime-visibility", imeShown != null && value == "abc",
                JSONObject().put("ime_shown_event", imeShown ?: JSONObject.NULL).put("focused", focused)
                    .put("value", value))
        }

        check(report, "input-ime-commit-unicode") {
            val text = "é😀中"
            // Independent of the key check: compare with whatever the field held before.
            val before = evaluate(tab, "document.getElementById('field').value") as? String ?: ""
            onMain { surfaceView().onCreateInputConnection(EditorInfo()).commitText(text, 1) }
            val value = waitForValue(tab, "document.getElementById('field').value") { it == before + text }
            report.passIf("input-ime-commit-unicode", value == before + text,
                JSONObject().put("committed", text).put("before", before).put("value", value))
        }

        // Scroll last: a tap right after a scroll can hit-test against a stale offset.
        check(report, "input-touch-scroll") {
            // Start from an unfocused page with the soft keyboard gone, so the swipe is a scroll.
            evaluate(tab, "document.activeElement && document.activeElement.blur(); true")
            onMain { surfaceView().hideKeyboard() }
            SystemClock.sleep(3_000)
            val view = surfaceView()
            val x = view.width / 2f
            swipe(x, view.height * 0.8f, x, view.height * 0.2f)
            val scrolled = waitForValue(tab, "window.scrollY") { ((it as? Number)?.toDouble() ?: 0.0) > 0 }
            report.passIf("input-touch-scroll", ((scrolled as? Number)?.toDouble() ?: 0.0) > 0,
                JSONObject().put("scrollY", scrolled))
            evaluate(tab, "window.scrollTo(0, 0); window.scrollY")
            SystemClock.sleep(1_500)
        }

        report.record("input-selection", CheckStatus.NOT_RUN,
            JSONObject().put("reason", "M0 forwards no selection gestures or selection sync; not exercised"))

        check(report, "surface-resize") {
            val before = evaluate(tab, "window.innerHeight") as? Number
            val view = surfaceView()
            val target = (view.height * 0.6f).toInt()
            onMain { view.layoutParams = LinearLayout.LayoutParams(view.width, target) }
            val after = waitForValue(tab, "window.innerHeight") { it is Number && it.toDouble() != before?.toDouble() }
            onMain { view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
            val restored = waitForValue(tab, "window.innerHeight") { it is Number && it.toDouble() == before?.toDouble() }
            report.passIf("surface-resize",
                after is Number && before != null && after.toDouble() < before.toDouble() &&
                    (restored as? Number)?.toDouble() == before.toDouble(),
                JSONObject().put("innerHeight_before", before).put("innerHeight_resized", after)
                    .put("innerHeight_restored", restored).put("surface_target_px", target)
                    .put("page_document", page.document))
        }
    }

    // --- Capture ---------------------------------------------------------------------------------

    private fun captureChecks(report: Report, tab1: Long, tab2: Long) {
        check(report, "capture-js-rendered-text") {
            val page = navigate(tab1, url("js-rendered.html"))
            SystemClock.sleep(SETTLE_MS)
            val nonce = evaluate(tab1, "window.m0Nonce")
            val capture = awaitCapture(mark(), startCapture(tab1, page.document))
            val text = capture.text()
            report.passIf("capture-js-rendered-text",
                capture.status == "captured" && text.contains("JS-RENDERED-PHRASE $nonce") &&
                    !text.contains("static placeholder") && capture.getLong("document") == page.document,
                JSONObject().put("page", loadedJson(page)).put("capture", summary(capture, text.take(300))))
        }

        check(report, "capture-redirect-attribution") {
            val from = mark()
            NativeEngine.navigate(tab1, nextId++, url("redirect?to=/c.html"))
            val page = awaitLoaded(from, tab1, url("c.html"))
            val committedUrls = events.since(from)
                .filter { it.isFor(tab1) && it.type == "document_committed" }.map { it.getString("url") }
            val capture = awaitCapture(mark(), startCapture(tab1, page.document))
            report.passIf("capture-redirect-attribution",
                capture.status == "captured" && capture.content().getString("url") == url("c.html") &&
                    capture.text().contains("MARKER-PAGE-C") && committedUrls == listOf(url("c.html")),
                JSONObject().put("requested", url("redirect?to=/c.html")).put("committed_urls", JSONArray(committedUrls))
                    .put("capture", summary(capture)))
        }

        check(report, "capture-same-url-reload") {
            val first = navigate(tab1, url("b.html"))
            val firstNonce = evaluate(tab1, "window.m0Nonce")
            val firstCapture = awaitCapture(mark(), startCapture(tab1, first.document))
            val reloadFrom = mark()
            NativeEngine.reload(tab1)
            val second = awaitLoaded(reloadFrom, tab1, url("b.html"))
            // Unguarded evaluation (no document named) runs in whatever is current now.
            val unguardedNonce = evaluate(tab1, "window.m0Nonce")
            val guardedOld = awaitCapture(mark(), startCapture(tab1, first.document, skipLocalCheck = true))
            val localOld = awaitCapture(mark(), startCapture(tab1, first.document))
            val secondCapture = awaitCapture(mark(), startCapture(tab1, second.document))
            report.passIf("capture-same-url-reload",
                second.document != first.document && second.url == first.url &&
                    guardedOld.status == "document_not_found" && localOld.status == "document_not_found" &&
                    secondCapture.status == "captured" && firstCapture.text().contains("NONCE-$firstNonce") &&
                    secondCapture.text().contains("NONCE-$unguardedNonce") && unguardedNonce != firstNonce,
                JSONObject().put("first", loadedJson(first)).put("second", loadedJson(second))
                    .put("first_nonce", firstNonce).put("unguarded_nonce_after_reload", unguardedNonce)
                    .put("servo_guard_for_old_document", summary(guardedOld))
                    .put("local_guard_for_old_document", summary(localOld))
                    .put("capture_new_document", summary(secondCapture)))
        }

        check(report, "capture-navigation-during-capture") {
            val slow = navigate(tab1, url("slow-capture.html"))
            val from = mark()
            val id = startCapture(tab1, slow.document)
            NativeEngine.navigate(tab1, nextId++, otherSite("b.html"))
            val capture = awaitCapture(from, id)
            val commit = awaitCommit(from, tab1, otherSite("b.html"))
            val captureIndex = indexOf(from) { it.type == "capture" && it.optLong("capture") == id }
            val commitIndex = indexOf(from) { it === commit }
            val leaked = capture.text().contains("MARKER-PAGE-B") ||
                (capture.status == "captured" && captureIndex > commitIndex)
            // The race happened when the script ran in the old document and its result arrived
            // after the new document committed; the engine must then discard it.
            val raced = capture.status == "stale_document" && captureIndex > commitIndex && capture.isNull("content")
            val status = when {
                leaked -> CheckStatus.FAILED
                raced -> CheckStatus.PASSED
                // Finished before the commit, or refused before running: the race did not happen.
                capture.status == "captured" || capture.status == "document_not_found" -> CheckStatus.NOT_RUN
                else -> CheckStatus.FAILED
            }
            report.record("capture-navigation-during-capture", status,
                JSONObject().put("old_document", slow.document).put("new_document", commit.getLong("document"))
                    .put("capture", summary(capture)).put("capture_event_before_commit", captureIndex < commitIndex))
        }

        check(report, "capture-close-during-capture") {
            val page = navigate(tab2, url("slow-capture.html"))
            val from = mark()
            val id = startCapture(tab2, page.document)
            NativeEngine.closeTab(tab2)
            val capture = awaitCapture(from, id)
            // Recreate tab 2 (a new native generation) for the checks that follow.
            val again = createTab(tab2, url("b.html"))
            // close_tab settles pending captures itself, without a page-reported URL.
            report.passIf("capture-close-during-capture",
                capture.status == "tab_closed" && capture.isNull("content") && capture.isNull("observed_url") &&
                    capture.getLong("generation") == page.generation && capture.getLong("document") == page.document,
                JSONObject().put("capture", summary(capture)).put("closed_generation", page.generation)
                    .put("recreated_generation", again.generation))
        }

        check(report, "capture-stale-title-ignored") {
            navigate(tab1, url("stale-title-a.html"))
            val from = mark()
            val next = navigate(tab1, url("c.html"))
            SystemClock.sleep(SETTLE_MS)
            val slice = events.since(from)
            val commitAt = slice.indexOfFirst {
                it.type == "document_committed" && it.isFor(tab1) && it.getLong("document") == next.document
            }
            // Before C commits, A is current and its title reports are rightly not stale.
            val staleText = slice.withIndex()
                .filter { (_, event) -> event.type == "title" && event.isFor(tab1) && event.optString("title") == "M0-STALE-FROM-A" }
            val beforeCommit = staleText.filter { it.index < commitAt }.map { it.value }
            val staleReports = staleText.filter { it.index > commitAt }.map { it.value }
            val misattributed = staleReports.filter { it.getString("attribution") != "stale" }
            val unattributed = events.since(from)
                .filter { it.type == "unattributed_title" && it.isFor(tab1) }.map { it.optString("title") }
            val recorded = engine.tab(tab1)?.title
            // Without a stale report the attribution path was not exercised: not a pass.
            val status = when {
                misattributed.isNotEmpty() || recorded != "M0-C" -> CheckStatus.FAILED
                staleReports.isEmpty() -> CheckStatus.NOT_RUN
                else -> CheckStatus.PASSED
            }
            report.record("capture-stale-title-ignored", status,
                JSONObject().put("new_document", next.document).put("tab_title", recorded)
                    .put("stale_reports", JSONArray(staleReports)).put("misattributed", JSONArray(misattributed))
                    .put("reports_before_commit", JSONArray(beforeCommit))
                    .put("stale_report_observed", staleReports.isNotEmpty())
                    .put("unattributed_titles_seen", JSONArray(unattributed)))
        }

        check(report, "capture-shadow-dom") {
            val page = navigate(tab1, url("shadow.html"))
            val supported = evaluate(tab1, "!!document.querySelector('m0-card').shadowRoot")
            val capture = awaitCapture(mark(), startCapture(tab1, page.document))
            val text = capture.text()
            val status = when {
                supported != true -> CheckStatus.NOT_RUN
                text.contains("SHADOW-TEXT") && text.contains("SLOTTED-LIGHT-TEXT") &&
                    !text.contains("HIDDEN-SHADOW-TEXT") -> CheckStatus.PASSED
                else -> CheckStatus.FAILED
            }
            report.record("capture-shadow-dom", status,
                JSONObject().put("open_shadow_root_supported", supported).put("capture", summary(capture)))
        }

        check(report, "capture-bounds-multibyte") {
            val page = navigate(tab1, url("multibyte.html"))
            val small = awaitCapture(mark(), startCapture(tab1, page.document, maxBytes = SMALL_CAPTURE_BYTES))
            val full = awaitCapture(mark(), startCapture(tab1, page.document))
            val smallText = small.text()
            val smallBytes = smallText.toByteArray(Charsets.UTF_8)
            report.passIf("capture-bounds-multibyte",
                small.status == "captured" && smallBytes.size <= SMALL_CAPTURE_BYTES &&
                    small.content().getBoolean("truncated_in_page") &&
                    small.content().getString("sha256") == sha256(smallBytes) && !smallText.contains('�') &&
                    full.text().contains("Grüße") && full.text().contains("日本語") && full.text().contains("😀"),
                JSONObject().put("small", summary(small)).put("small_utf8_bytes", smallBytes.size)
                    .put("full", summary(full)))
        }

        check(report, "capture-bounds-large") {
            val page = navigate(tab1, url("large.html"))
            val capture = awaitCapture(mark(), startCapture(tab1, page.document))
            report.passIf("capture-bounds-large",
                capture.status == "captured" && capture.content().getInt("text_bytes") <= DEFAULT_CAPTURE_BYTES &&
                    capture.content().getBoolean("truncated_in_page"),
                JSONObject().put("capture", summary(capture)))
        }

        check(report, "capture-empty-document") {
            val page = navigate(tab1, url("empty.html"))
            val capture = awaitCapture(mark(), startCapture(tab1, page.document))
            report.passIf("capture-empty-document", capture.status == "empty",
                JSONObject().put("capture", summary(capture)))
        }

        check(report, "capture-hostile-page") {
            val throwing = navigate(tab1, url("hostile-throw.html"))
            val thrown = awaitCapture(mark(), startCapture(tab1, throwing.document))
            val huge = navigate(tab1, url("hostile-huge.html"))
            val bounded = awaitCapture(mark(), startCapture(tab1, huge.document))
            // Claims a billion children per node: the script must stay within its node budget.
            val wide = navigate(tab1, url("hostile-wide.html"))
            val widened = awaitCapture(mark(), startCapture(tab1, wide.document))
            report.passIf("capture-hostile-page",
                thrown.status == "script_error" && thrown.isNull("content") &&
                    (bounded.status != "captured" || bounded.content().getInt("text_bytes") <= DEFAULT_CAPTURE_BYTES) &&
                    bounded.getLong("document") == huge.document &&
                    widened.status != "timed_out" && widened.getLong("document") == wide.document &&
                    (widened.status != "captured" || widened.content().getInt("text_bytes") <= DEFAULT_CAPTURE_BYTES),
                JSONObject().put("throwing_builtins", summary(thrown)).put("huge_getter", summary(bounded))
                    .put("huge_child_list", summary(widened)))
        }

        check(report, "capture-timeout-abandons-result") {
            val page = navigate(tab1, url("large.html"))
            val from = mark()
            val id = startCapture(tab1, page.document, timeoutMs = 1)
            val capture = awaitCapture(from, id)
            val late = events.await(from, LOAD_TIMEOUT_MS) { it.type == "late_capture_result" && it.optLong("capture") == id }
            val delivered = events.since(from).count { it.type == "capture" && it.optLong("capture") == id }
            report.passIf("capture-timeout-abandons-result",
                capture.status == "timed_out" && delivered == 1 && late != null,
                JSONObject().put("capture", summary(capture)).put("late_result_ignored", late != null))
        }
    }

    // --- Hidden views and memory -------------------------------------------------------------------

    private fun hiddenViewChecks(report: Report, tab1: Long, tab2: Long) {
        check(report, "hidden-view-behaviour") {
            navigate(tab2, url("timer.html"))
            select(tab1)
            val start = evaluate(tab2, "window.ticks") as Number
            SystemClock.sleep(MEASURE_MS)
            val hidden = evaluate(tab2, "window.ticks") as Number
            NativeEngine.setThrottled(tab2, true)
            SystemClock.sleep(1_000)
            val throttledStart = evaluate(tab2, "window.ticks") as Number
            SystemClock.sleep(MEASURE_MS)
            val throttled = evaluate(tab2, "window.ticks") as Number
            NativeEngine.setThrottled(tab2, false)
            val hiddenRate = (hidden.toDouble() - start.toDouble()) * 1000 / MEASURE_MS
            val throttledRate = (throttled.toDouble() - throttledStart.toDouble()) * 1000 / MEASURE_MS
            // Measurement, not a policy decision: record what hidden views do.
            // A measurement: it passes when it measured something, whatever the rates are.
            report.passIf("hidden-view-behaviour", hiddenRate > 0 || throttledRate > 0,
                JSONObject().put("timer_ticks_per_second_hidden", hiddenRate)
                    .put("timer_ticks_per_second_hidden_throttled", throttledRate)
                    .put("page_requests_ticks_per_second", 10))
        }
    }

    private fun memoryChecks(report: Report, tab1: Long) {
        check(report, "memory-baseline") {
            val twoTabs = pssKb()
            val extra = (3L..10L)
            for (tab in extra) createTab(tab, url("a.html"))
            SystemClock.sleep(SETTLE_MS)
            val tenTabs = pssKb()
            for (tab in extra) NativeEngine.closeTab(tab)
            select(tab1)
            // A measurement: it passes when both values were read.
            report.passIf("memory-baseline", twoTabs > 0 && tenTabs > 0,
                JSONObject().put("pss_kb_two_tabs", twoTabs).put("pss_kb_ten_tabs", tenTabs)
                    .put("note", "Debug.MemoryInfo total PSS of the harness process (emulator, x86_64), " +
                        "small fixture pages, measured early in the scenario"))
        }
    }

    // --- Paint diagnosis ----------------------------------------------------------------------------

    /** Bisects which engine operations stop navigations from reaching the screen. */
    private fun diagnosePaint(report: Report) {
        val tab = 1L
        fun step(id: String, color: Int, action: () -> Unit) = check(report, id) {
            action()
            val screen = awaitDisplayed(tab, color)
            report.passIf(id, matches(screen, color),
                JSONObject().put("expected", hex(color)).put("screen", hex(screen)).put("tab", engine.tab(tab)?.url))
        }
        step("diag-first-page", COLOR_A) { createTab(tab, url("a.html")); select(tab) }
        step("diag-navigate", COLOR_B) { navigate(tab, url("b.html")) }
        step("diag-navigate-again", COLOR_C) { navigate(tab, url("c.html")) }
        step("diag-after-timed-out-capture", COLOR_A) {
            val large = navigate(tab, url("large.html"))
            awaitCapture(mark(), startCapture(tab, large.document, timeoutMs = 1))
            navigate(tab, url("a.html"))
        }
        step("diag-after-hidden-tab-closed", COLOR_B) {
            createTab(2, url("c.html"))
            NativeEngine.closeTab(2)
            navigate(tab, url("b.html"))
        }
        step("diag-after-eight-hidden-tabs", COLOR_C) {
            for (other in 3L..10L) createTab(other, url("a.html"))
            for (other in 3L..10L) NativeEngine.closeTab(other)
            navigate(tab, url("c.html"))
        }
        step("diag-after-reselect", COLOR_A) {
            select(tab)
            navigate(tab, url("a.html"))
        }
        step("diag-after-throttle-toggle", COLOR_B) {
            createTab(2, url("timer.html"))
            NativeEngine.setThrottled(2, true)
            NativeEngine.setThrottled(2, false)
            navigate(tab, url("b.html"))
        }
        report.screenshot("diag-end")
    }

    /** Replays the end of `core` (where a stale frame was seen) with a screen check per stage. */
    private fun diagnoseCoreTail(report: Report) {
        val tab = 1L
        fun stage(id: String, path: String, color: Int, action: () -> Unit) = check(report, id) {
            action()
            navigate(tab, url(path))
            val screen = awaitDisplayed(tab, color)
            report.passIf(id, matches(screen, color),
                JSONObject().put("expected", hex(color)).put("screen", hex(screen)))
        }
        stage("tail-start", "a.html", COLOR_A) {
            createTab(tab, url("b.html"))
            select(tab)
            createTab(2, url("b.html"))
        }
        stage("tail-timed-out-capture-on-large-page", "c.html", COLOR_C) {
            val large = navigate(tab, url("large.html"))
            val from = mark()
            val id = startCapture(tab, large.document, timeoutMs = 1)
            awaitCapture(from, id)
            events.await(from, LOAD_TIMEOUT_MS) { it.type == "late_capture_result" && it.optLong("capture") == id }
        }
        stage("tail-hidden-timer-tab-throttled", "a.html", COLOR_A) {
            navigate(2, url("timer.html"))
            select(tab)
            SystemClock.sleep(MEASURE_MS)
            NativeEngine.setThrottled(2, true)
            SystemClock.sleep(MEASURE_MS)
            NativeEngine.setThrottled(2, false)
        }
        stage("tail-eight-tabs-created-and-closed", "b.html", COLOR_B) {
            for (other in 3L..10L) createTab(other, url("a.html"))
            SystemClock.sleep(SETTLE_MS)
            for (other in 3L..10L) NativeEngine.closeTab(other)
            select(tab)
        }
        stage("tail-timer-tab-closed", "c.html", COLOR_C) {
            val closeFrom = mark()
            NativeEngine.closeTab(2)
            await(closeFrom, "tab_closed 2") { it.type == "tab_closed" && it.isFor(2) }
        }
        report.screenshot("tail-end")
    }

    /**
     * In `core` the tab stays on large.html while other tabs are created, throttled and closed.
     * Each variant uses a fresh tab, keeps it on large.html during one operation, then checks
     * that a navigation away reaches the screen, and whether a further navigation recovers.
     */
    private fun diagnoseLargePageDwell(report: Report, only: String?) {
        var tab = 100L
        var matched = false
        fun variant(id: String, during: (Long) -> Unit) = if (only != null && only != id) Unit else check(report, id) {
            matched = true
            val previous = tab
            tab += 1
            createTab(tab, url("a.html"))
            select(tab)
            if (engine.tab(previous) != null) NativeEngine.closeTab(previous)
            navigate(tab, url("large.html"))
            during(tab)
            navigate(tab, url("b.html"))
            val away = awaitDisplayed(tab, COLOR_B)
            navigate(tab, url("c.html"))
            val again = awaitDisplayed(tab, COLOR_C)
            report.passIf(id, matches(away, COLOR_B),
                JSONObject().put("screen_after_leaving_large_page", hex(away))
                    .put("screen_after_next_navigation", hex(again)).put("recovered", matches(again, COLOR_C)))
        }
        variant("dwell-only") { SystemClock.sleep(30_000) }
        variant("dwell-timed-out-capture") { current ->
            val document = engine.tab(current)!!.document!!
            val from = mark()
            val id = startCapture(current, document, timeoutMs = 1)
            awaitCapture(from, id)
            events.await(from, LOAD_TIMEOUT_MS) { it.type == "late_capture_result" && it.optLong("capture") == id }
        }
        variant("dwell-reselect") { current -> select(current) }
        variant("dwell-throttled-timer-tab-closed") { current ->
            createTab(2, url("timer.html"))
            select(current)
            NativeEngine.setThrottled(2, true)
            SystemClock.sleep(MEASURE_MS)
            NativeEngine.setThrottled(2, false)
            NativeEngine.closeTab(2)
        }
        // Last: once the screen is stuck, later variants would only measure the stuck state.
        variant("dwell-eight-hidden-tabs") { current ->
            for (other in 3L..10L) createTab(other, url("a.html"))
            for (other in 3L..10L) NativeEngine.closeTab(other)
            select(current)
        }
        if (!matched) report.record("diag-tail2-unknown-variant", CheckStatus.FAILED, JSONObject().put("only", only))
        report.screenshot("tail2-end")
    }

    /** Does the surface show the selected tab's current document? (Used after host actions.) */
    private fun screenCheck(report: Report, label: String) {
        val tab = engine.selectedTab ?: run {
            report.record("screen-matches-selected-tab-$label", CheckStatus.NOT_RUN,
                JSONObject().put("reason", "no selected tab"))
            return
        }
        val (unbound, rebound) = awaitRebind(lastScenarioEnd)
        if (unbound < 0 || rebound < 0) {
            report.record("screen-matches-selected-tab-$label", CheckStatus.NOT_RUN,
                JSONObject().put("reason", "the host action produced no new surface"))
            return
        }
        val expected = declaredSwatch(tab)
        val screen = expected?.let { awaitDisplayed(tab, it) }
        report.record("screen-matches-selected-tab-$label",
            if (expected == null) CheckStatus.NOT_RUN else if (matches(screen, expected)) CheckStatus.PASSED else CheckStatus.FAILED,
            JSONObject().put("tab", tab).put("url", engine.tab(tab)?.url).put("expected", hex(expected))
                .put("screen", hex(screen)).put("surface_rebound_event_index", rebound))
        report.screenshot(label)
    }

    // --- Lifecycle phases (host-driven) ------------------------------------------------------------

    private fun coreState(): JSONObject {
        val file = File(app.filesDir, "m0-core-state.json")
        return if (file.exists()) JSONObject(file.readText()) else JSONObject()
    }

    /** Index of the first `surface_bound` after the most recent `surface_unbound` at or after [from]. */
    private fun awaitRebind(from: Int): Pair<Int, Int> {
        val unbound = events.since(from).indexOfLast { it.type == "surface_unbound" }.let { if (it < 0) -1 else from + it }
        if (unbound < 0) return Pair(-1, -1)
        val bound = events.await(unbound + 1, LOAD_TIMEOUT_MS) { it.type == "surface_bound" }
        return Pair(unbound, if (bound == null) -1 else events.since(0).indexOf(bound))
    }

    private fun resumeCheck(report: Report) {
        val state = coreState()
        val (unbound, rebound) = awaitRebind(lastScenarioEnd)
        val tab1 = engine.tab(1)
        val from = maxOf(rebound, 0)
        val painted = if (tab1 != null) awaitPainted(1, from) ?: run {
            // A paint needs a new frame; nudge the page to produce one.
            evaluate(1, "document.body.style.background = '#fafafa'")
            awaitPainted(1, from)
        } else null
        val url = tab1?.let { evaluate(1, "document.URL") }
        // A paint event only proves a present; the pixels must show the tab's page.
        val swatch = if (tab1 != null) declaredSwatch(1) else null
        val screen = if (swatch != null && engine.selectedTab == 1L) awaitDisplayed(1, swatch) else null
        report.passIf("surface-background-foreground",
            unbound >= 0 && rebound > unbound && tab1 != null &&
                tab1.document == state.optLong("tab1_document", -1) && painted != null && url == state.optString("tab1_url") &&
                swatch != null && matches(screen, swatch),
            JSONObject().put("unbound_event_index", unbound).put("rebound_event_index", rebound)
                .put("tab1", tab1?.let { JSONObject().put("document", it.document).put("url", it.url) } ?: JSONObject.NULL)
                .put("expected", state).put("painted_after_resume", painted != null)
                .put("expected_swatch", hex(swatch)).put("screen", hex(screen)))
        report.screenshot("after-resume")
    }

    private fun rotationCheck(report: Report) {
        val state = coreState()
        val (unbound, rebound) = awaitRebind(lastScenarioEnd)
        val bound = if (rebound >= 0) events.since(rebound).first() else null
        val tab1 = engine.tab(1)
        val size = evaluate(1, "[window.innerWidth, window.innerHeight]") as? JSONArray
        report.passIf("surface-rotation-recreation",
            bound != null && bound.getInt("width") > bound.getInt("height") && tab1 != null &&
                tab1.document == state.optLong("tab1_document", -1) && size != null && size.getDouble(0) > size.getDouble(1),
            JSONObject().put("unbound_event_index", unbound).put("last_surface", bound ?: JSONObject.NULL)
                .put("viewport", size ?: JSONObject.NULL)
                .put("tab1_document", tab1?.document).put("expected_document", state.opt("tab1_document")))
        report.screenshot("landscape")
    }

    private fun persistSet(report: Report, value: String) {
        // Goes into URLs unencoded and is compared with what the server echoes back.
        require(value.matches(PERSIST_VALUE)) { "persist-set value must be 1-32 ASCII letters or digits" }
        val tab = engine.selectedTab ?: 1L
        if (engine.tab(tab) == null) createTab(tab, url("a.html")).also { select(tab) }
        val cookie = navigate(tab, url("cookie-set?v=$value"))
        val storage = navigate(tab, url("storage.html?set=$value"))
        val stored = evaluate(tab, "localStorage.getItem('m0')")
        navigate(tab, url("cookie-echo"))
        val header = evaluate(tab, "document.getElementById('cookie').textContent") as? String
        // Leave the session on a neutral page: restoring it must not set the values again.
        val last = navigate(tab, url("a.html"))
        val temporary = File(expectationsFile.path + ".tmp")
        temporary.writeText(JSONObject().put("value", value).put("tab", tab).put("last_url", last.url).toString())
        temporary.renameTo(expectationsFile)
        report.passIf("persist-set", stored == value && header?.contains("m0cookie=$value") == true,
            JSONObject().put("value", value).put("cookie_page", loadedJson(cookie)).put("storage_page", loadedJson(storage))
                .put("cookie_header_seen_by_server", header))
    }

    private fun persistCheck(report: Report, label: String) {
        val expectations = JSONObject(expectationsFile.readText())
        val expected = expectations.getString("value")
        val saved = engine.savedSession()
        val from = mark()
        val restored = app.restoreSession()
        val savedTabs = saved?.getJSONArray("tabs") ?: JSONArray()
        // Each saved tab must come back as a new view whose first committed document is the saved URL.
        val restoredUrls = (0 until savedTabs.length()).map { index ->
            val entry = savedTabs.getJSONObject(index)
            val tab = entry.getLong("tab")
            await(from, "restored tab $tab") { it.type == "document_committed" && it.isFor(tab) }.getString("url")
        }
        val savedUrls = (0 until savedTabs.length()).map { savedTabs.getJSONObject(it).getString("url") }
        // Restore reproduces the file; the file must also hold the URL persist-set last committed.
        val expectedTab = expectations.getLong("tab")
        val savedForTab = (0 until savedTabs.length()).map { savedTabs.getJSONObject(it) }
            .firstOrNull { it.getLong("tab") == expectedTab }?.getString("url")
        report.passIf("session-last-url-recovery-$label",
            restored > 0 && restoredUrls == savedUrls && savedForTab == expectations.getString("last_url"),
            JSONObject().put("saved", saved ?: JSONObject.NULL).put("restored_committed_urls", JSONArray(restoredUrls))
                .put("expected_tab", expectedTab).put("expected_url", expectations.getString("last_url")))

        val tab = savedTabs.optJSONObject(0)?.getLong("tab") ?: 1L
        navigate(tab, url("cookie-echo"))
        val header = evaluate(tab, "document.getElementById('cookie').textContent") as? String
        navigate(tab, url("storage.html"))
        val stored = evaluate(tab, "localStorage.getItem('m0')")
        report.passIf("profile-cookie-persists-$label", header?.contains("m0cookie=$expected") == true,
            JSONObject().put("expected", expected).put("cookie_header_seen_by_server", header))
        report.passIf("profile-localstorage-persists-$label", stored == expected,
            JSONObject().put("expected", expected).put("stored", stored))
    }

    private fun shutdown(report: Report) {
        val started = SystemClock.uptimeMillis()
        val acknowledged = NativeEngine.shutdown()
        val complete = events.await(0, SHUTDOWN_TIMEOUT_MS) { it.type == "shutdown_complete" }
        report.passIf("engine-clean-shutdown", complete != null,
            JSONObject().put("acknowledged_by_jni_wait", acknowledged)
                .put("duration_ms", SystemClock.uptimeMillis() - started))
        report.finish()
        // Servo cannot be restarted in this process; end it like a user closing the app.
        main.post {
            app.activity?.finishAndRemoveTask()
            Process.killProcess(Process.myPid())
        }
    }

    // --- Engine helpers ----------------------------------------------------------------------------

    private fun awaitEngineReady() {
        engine.start()
        await(0, "engine_ready") { it.type == "engine_ready" }
        await(0, "surface_bound") { it.type == "surface_bound" }
    }

    private fun mark() = events.size()

    private fun await(from: Int, what: String, timeoutMs: Long = LOAD_TIMEOUT_MS, predicate: (JSONObject) -> Boolean) =
        events.await(from, timeoutMs, predicate) ?: throw CheckTimeout(what)

    private fun indexOf(from: Int, predicate: (JSONObject) -> Boolean): Int {
        val slice = events.since(from)
        val index = slice.indexOfFirst(predicate)
        return if (index < 0) Int.MAX_VALUE else from + index
    }

    private fun url(path: String) = "$SITE/$path"

    private fun otherSite(path: String) = "$OTHER_SITE/$path"

    private fun createTab(tab: Long, url: String): Loaded {
        val from = mark()
        engine.createTab(tab, url)
        return awaitLoaded(from, tab, url)
    }

    /** Selects [tab]; returns the event index of its `tab_selected` event. */
    private fun select(tab: Long): Int {
        val from = mark()
        NativeEngine.selectTab(tab)
        val selected = await(from, "tab_selected $tab") { it.type == "tab_selected" && it.isFor(tab) }
        return events.since(0).indexOf(selected)
    }

    private fun navigate(tab: Long, url: String): Loaded {
        val shownBefore = if (engine.selectedTab == tab && app.activity != null) swatchOnScreen(tab) else null
        val from = mark()
        NativeEngine.navigate(tab, nextId++, url)
        return awaitLoaded(from, tab, url).also { traceScreen(it, shownBefore) }
    }

    /** The color under the current page's swatch, or null when the page has none. */
    private fun swatchOnScreen(tab: Long): Int? = try {
        if (declaredSwatch(tab) == null) null else displayedColor(tab)
    } catch (failure: IllegalStateException) {
        null
    }

    /**
     * After a navigation of the selected tab, compare the pixels under the page's `#swatch`
     * with the color the new document declares. Records mismatches for
     * `display-follows-navigation` instead of failing the step. A navigation whose swatch
     * matches what was already on screen cannot reveal a stale frame, so it is skipped.
     */
    private fun traceScreen(loaded: Loaded, shownBefore: Int?) {
        if (engine.selectedTab != loaded.tab || app.activity == null) return
        val rgb = declaredSwatch(loaded.tab)
        if (rgb == null || matches(shownBefore, rgb)) {
            screenSkips++
            return
        }
        screenChecks++
        val deadline = SystemClock.uptimeMillis() + SCREEN_TRACE_TIMEOUT_MS
        var screen = displayedColor(loaded.tab)
        while (!matches(screen, rgb) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(500)
            screen = displayedColor(loaded.tab)
        }
        if (!matches(screen, rgb)) {
            screenMismatches.put(JSONObject().put("url", loaded.url).put("document", loaded.document)
                .put("expected", hex(rgb)).put("screen", hex(screen)).put("event_index", mark()))
        }
    }

    /** The swatch color the tab's current document declares, or null (no swatch, broken page). */
    private fun declaredSwatch(tab: Long): Int? {
        // Inline style, not getComputedStyle: hostile fixtures replace page built-ins.
        val declared = try {
            evaluate(tab,
                "(function(){var s=document.getElementById('swatch');" +
                    "return s ? s.style.backgroundColor : null;})()") as? String
        } catch (failure: IllegalStateException) {
            null
        } ?: return null
        return Regex("""rgba?\((\d+), (\d+), (\d+)""").find(declared)?.destructured
            ?.let { (r, g, b) -> (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt() }
    }

    private fun awaitCommit(from: Int, tab: Long, url: String): JSONObject =
        await(from, "commit $url in tab $tab") {
            it.type == "document_committed" && it.isFor(tab) && it.getString("url") == url
        }

    private fun awaitLoaded(from: Int, tab: Long, url: String): Loaded {
        val commit = awaitCommit(from, tab, url)
        val document = commit.getLong("document")
        await(from, "load complete of $url in tab $tab") {
            it.type == "load_status" && it.isFor(tab) && it.optLong("document") == document &&
                it.getString("phase") == "complete" && it.getString("attribution") != "stale"
        }
        return Loaded(tab, commit.getLong("generation"), document, url)
    }

    private fun awaitPainted(tab: Long, from: Int): JSONObject? =
        events.await(from, LOAD_TIMEOUT_MS) { it.type == "painted" && it.isFor(tab) }

    private fun evaluate(tab: Long, script: String): Any? {
        val request = nextId++
        val from = mark()
        NativeEngine.evaluate(tab, request, script)
        val result = await(from, "evaluation $request") { it.type == "evaluation" && it.optLong("request") == request }
        if (!result.getBoolean("ok")) throw IllegalStateException("evaluation failed: ${result.opt("error")}")
        return result.opt("value").takeUnless { it == JSONObject.NULL }
    }

    private fun waitForValue(tab: Long, script: String, predicate: (Any?) -> Boolean): Any? {
        val deadline = SystemClock.uptimeMillis() + INPUT_TIMEOUT_MS
        var value = evaluate(tab, script)
        while (!predicate(value) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(500)
            value = evaluate(tab, script)
        }
        return value
    }

    private fun startCapture(
        tab: Long,
        document: Long,
        maxBytes: Int = DEFAULT_CAPTURE_BYTES,
        timeoutMs: Int = CAPTURE_TIMEOUT_MS,
        skipLocalCheck: Boolean = false,
    ): Long {
        val id = nextId++
        NativeEngine.capture(tab, document, id, maxBytes, timeoutMs, skipLocalCheck)
        return id
    }

    private fun awaitCapture(from: Int, id: Long): JSONObject =
        await(from, "capture $id", CAPTURE_TIMEOUT_MS + LOAD_TIMEOUT_MS) {
            it.type == "capture" && it.optLong("capture") == id
        }

    // --- Input helpers -----------------------------------------------------------------------------

    private fun surfaceView(): EngineSurfaceView =
        app.activity?.surface ?: throw IllegalStateException("no visible harness activity")

    private fun onMain(block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        main.post {
            try {
                block()
            } catch (thrown: Throwable) {
                failure = thrown
            } finally {
                done.countDown()
            }
        }
        if (!done.await(INPUT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw CheckTimeout("main thread")
        failure?.let { throw it }
    }

    /** Center of element [id] in surface pixels. */
    private fun elementCenter(tab: Long, id: String): Pair<Float, Float> {
        val box = evaluate(tab,
            "(function(){var r=document.getElementById('$id').getBoundingClientRect();" +
                "return [r.left+r.width/2, r.top+r.height/2, window.devicePixelRatio];})()") as JSONArray
        val ratio = box.getDouble(2)
        return Pair((box.getDouble(0) * ratio).toFloat(), (box.getDouble(1) * ratio).toFloat())
    }

    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        dispatch(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        SystemClock.sleep(80)
        dispatch(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val down = SystemClock.uptimeMillis()
        dispatch(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x1, y1, 0))
        val steps = 12
        for (step in 1..steps) {
            SystemClock.sleep(30)
            val fraction = step.toFloat() / steps
            dispatch(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction, 0))
        }
        dispatch(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2, 0))
    }

    private fun dispatch(event: MotionEvent) {
        try {
            onMain { surfaceView().dispatchTouchEvent(event) }
        } finally {
            event.recycle()
        }
    }

    private fun typeKeys(text: String) {
        val keys = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        for (key in keys) {
            onMain { surfaceView().dispatchKeyEvent(key) }
            SystemClock.sleep(40)
        }
    }

    // --- Reporting helpers -------------------------------------------------------------------------

    private fun check(report: Report, id: String, body: () -> Unit) {
        try {
            body()
        } catch (failure: Exception) {
            Log.e(LOG_TAG, "check $id failed", failure)
            report.record(id, CheckStatus.FAILED, error(failure))
        }
    }

    private fun error(failure: Exception) = JSONObject().put("error", failure.toString())

    private fun loadedJson(loaded: Loaded) = JSONObject().put("tab", loaded.tab)
        .put("generation", loaded.generation).put("document", loaded.document).put("url", loaded.url)

    private fun summary(capture: JSONObject, text: String? = null): JSONObject {
        val summary = JSONObject(capture.toString())
        summary.optJSONObject("content")?.let { content ->
            content.put("text", text ?: preview(content.optString("text")))
        }
        return summary
    }

    /** First [PREVIEW_CODE_POINTS] code points, never splitting a surrogate pair. */
    private fun preview(text: String): String {
        val codePoints = text.codePointCount(0, text.length)
        if (codePoints <= PREVIEW_CODE_POINTS) return text
        return text.substring(0, text.offsetByCodePoints(0, PREVIEW_CODE_POINTS)) + "…"
    }

    /** RGB on screen at the center of the page's `#swatch`, read back from the surface. */
    private fun displayedColor(tab: Long): Int? {
        val (x, y) = elementCenter(tab, "swatch")
        val copied = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        var bitmap: Bitmap? = null
        main.post {
            // The surface can disappear at any time (Home, rotation): never let that crash.
            try {
                val view = surfaceView()
                if (view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) {
                    copied.countDown()
                    return@post
                }
                val target = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                bitmap = target
                PixelCopy.request(view, target, { code -> result = code; copied.countDown() }, main)
            } catch (failure: RuntimeException) {
                Log.w(LOG_TAG, "PixelCopy failed", failure)
                copied.countDown()
            }
        }
        if (!copied.await(INPUT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || result != PixelCopy.SUCCESS) return null
        val copy = bitmap ?: return null
        val px = x.toInt().coerceIn(0, copy.width - 1)
        val py = y.toInt().coerceIn(0, copy.height - 1)
        return copy.getPixel(px, py) and RGB_MASK
    }

    /** Waits for frames to reach the screen; returns the last color seen. */
    private fun awaitDisplayed(tab: Long, rgb: Int): Int? {
        val deadline = SystemClock.uptimeMillis() + INPUT_TIMEOUT_MS
        var color = displayedColor(tab)
        while (!matches(color, rgb) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(1_000)
            color = displayedColor(tab)
        }
        return color
    }

    private fun matches(color: Int?, rgb: Int): Boolean {
        if (color == null) return false
        return (0..2).all { channel ->
            val shift = channel * 8
            kotlin.math.abs(((color shr shift) and 0xFF) - ((rgb shr shift) and 0xFF)) <= COLOR_TOLERANCE
        }
    }

    private fun hex(color: Int?): Any = color?.let { "#%06x".format(it) } ?: JSONObject.NULL

    private fun pssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val JSONObject.type get() = getString("type")
    private val JSONObject.status get() = optString("status")
    private fun JSONObject.isFor(tab: Long) = optLong("tab", -1) == tab
    private fun JSONObject.content(): JSONObject = getJSONObject("content")
    private fun JSONObject.text(): String = optJSONObject("content")?.optString("text").orEmpty()

    companion object {
        const val SITE = "http://localhost:8765"
        const val OTHER_SITE = "http://127.0.0.1:8765"
        // Generous: the reference run is a software-emulated x86_64 emulator without KVM, where a
        // loaded host stretched slow-capture.html to 2.6 min (run7).
        const val LOAD_TIMEOUT_MS = 240_000L
        const val INPUT_TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 2_000L
        const val MEASURE_MS = 5_000L
        const val SMALL_CAPTURE_BYTES = 1_000
        const val SHUTDOWN_TIMEOUT_MS = 600_000L
        const val SCREEN_TRACE_TIMEOUT_MS = 15_000L
        // Nothing listens on the discard port on the device, so the connection is refused.
        const val UNREACHABLE_URL = "http://localhost:9/unreachable"
        // Title of the page Servo commits for a failed load.
        const val SERVO_ERROR_PAGE_TITLE = "Error loading page"
        val PERSIST_VALUE = Regex("[A-Za-z0-9]{1,32}")
        val HISTORY_EVENTS = setOf("document_committed", "same_document_navigation", "history_updated")
        const val PREVIEW_CODE_POINTS = 160
        // Swatch colors of the fixture pages a.html, b.html and c.html.
        const val COLOR_A = 0x2e6b4f
        const val COLOR_B = 0x8a5a2b
        const val COLOR_C = 0x3b4f8a
        const val COLOR_TOLERANCE = 16
        const val RGB_MASK = 0xFFFFFF
        val INPUT_CHECKS = listOf(
            "input-touch-tap", "input-touch-scroll", "input-keys-and-ime-visibility",
            "input-ime-commit-unicode", "input-selection", "surface-resize",
        )
    }
}
