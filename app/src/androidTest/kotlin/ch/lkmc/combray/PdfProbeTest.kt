package ch.lkmc.combray

import android.os.Debug
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PDF probe (docs/PDF.md, phase 0b; a draft-PR experiment, never merged): the probe page renders
 * a PDF corpus with pdf.js (modern and legacy builds) and with PDFium compiled to WebAssembly in
 * the real engine, and reports its timings, text and snapshots to the fixture server. At each of
 * the page's checkpoints this samples the app's memory (Servo runs in the app's process).
 *
 * A document that fails is a result, not a failed check: this fails only when the probe page
 * itself breaks or never finishes.
 */
@RunWith(AndroidJUnit4::class)
class PdfProbeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val device = Device { compose.mainClock.advanceTimeByFrame() }

    @Before
    fun setUp() {
        device.awaitEngine()
        device.closeAllTabs()
    }

    @Test
    fun pdfjsModern() = probe("pdfjs", STRICT)

    @Test
    fun pdfjsLegacy() = probe("pdfjs-legacy", STRICT)

    @Test
    fun pdfiumWasm() = probe("pdfium", STRICT)

    /** The legacy build without the viewer's CSP, to tell CSP refusals from engine gaps. */
    @Test
    fun pdfjsLegacyWithoutCsp() = probe("pdfjs-legacy", OPEN)

    // The corpus twice, to tell a one-off cost from memory that keeps growing; and pdf.js without
    // FontFace (glyphs drawn as paths), to tell whether loaded web fonts are what stays.
    @Test
    fun pdfjsLegacyTwice() = probe("pdfjs-legacy", STRICT, "&rounds=2", "pdfjs-legacy-x2")

    @Test
    fun pdfjsLegacyWithoutFontFaceTwice() = probe("pdfjs-legacy", STRICT, "&rounds=2&fonts=off", "pdfjs-legacy-nofontface-x2")

    @Test
    fun pdfiumWasmTwice() = probe("pdfium", STRICT, "&rounds=2", "pdfium-x2")

    /** [label] names the run in the reports and checkpoints, as the page names it. */
    private fun probe(engine: String, csp: String, options: String = "", label: String = engine) {
        device.browser.openUrl("${Fixtures.BASE}/pdfprobe/$csp/index.html?engine=$engine$options", Placement.CURRENT_TAB)
        val handled = mutableSetOf<String>()
        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) gaveUp(label, csp)
            val title = try {
                device.awaitState(remaining) { state ->
                    val title = state.activeTab?.title.orEmpty()
                    title == DONE || title.startsWith(FAIL) || checkpointOf(title)?.let { it !in handled } == true
                }.activeTab?.title.orEmpty()
            } catch (timeout: TimeoutCancellationException) {
                gaveUp(label, csp)
            }
            if (title == DONE) {
                afterLeaving(label, csp)
                return
            }
            check(!title.startsWith(FAIL)) { "probe $label/$csp broke: $title" }

            val name = checkpointOf(title)!!
            handled += name
            // Let the page's last frames and the engine's frees land before measuring.
            Thread.sleep(SETTLE_MS)
            post("/_admin/probe-memory", memory(label, csp, name).toString())
            post("/_admin/probe-release?engine=$label&csp=$csp&name=$name", "")
        }
    }

    /**
     * What a person closing the PDF would see: the tab goes to another page, Servo discards the
     * probe's document and workers, and memory is sampled once that has settled.
     */
    private fun afterLeaving(engine: String, csp: String) {
        val elsewhere = Fixtures.solid(Device.GREEN)
        device.browser.openUrl(elsewhere, Placement.CURRENT_TAB)
        device.awaitState { it.activeTab?.url == elsewhere && it.activeTab?.title == Fixtures.title(Device.GREEN) }
        Thread.sleep(LEAVE_SETTLE_MS)
        post("/_admin/probe-memory", memory(engine, csp, "after-leave").toString())
    }

    /** Records where the page was when the check gave up (the summary shows it), then fails. */
    private fun gaveUp(engine: String, csp: String): Nothing {
        val title = device.browser.browserState.value.activeTab?.title
        val record = JSONObject().put("engine", engine).put("csp", csp).put("kind", "harness-error")
            .put("error", "device check gave up after ${PROBE_TIMEOUT_MS / 60_000} minutes at title $title")
        runCatching { post("/_admin/probe-report", record.toString()) }
        error("probe $engine/$csp did not finish: $title")
    }

    private fun memory(engine: String, csp: String, name: String): JSONObject {
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return JSONObject()
            .put("engine", engine)
            .put("csp", csp)
            .put("kind", "memory")
            .put("name", name)
            .put("totalPssKb", info.totalPss)
            .put("nativeHeapKb", info.getMemoryStat("summary.native-heap"))
            .put("graphicsKb", info.getMemoryStat("summary.graphics"))
            .put("javaHeapKb", info.getMemoryStat("summary.java-heap"))
            .put("codeKb", info.getMemoryStat("summary.code"))
            // Servo's jemalloc heap, SpiderMonkey's GC heap and wasm memory are unnamed mappings:
            // they count here, not in the native heap.
            .put("privateOtherKb", info.getMemoryStat("summary.private-other"))
            .put("systemKb", info.getMemoryStat("summary.system"))
            .put("nativeAllocatedKb", Debug.getNativeHeapAllocatedSize() / 1024)
    }

    private fun post(path: String, body: String) {
        val connection = URL(Fixtures.BASE + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        connection.inputStream.use { it.readBytes() }
        connection.disconnect()
    }

    private companion object {
        const val STRICT = "strict"
        const val OPEN = "open"
        const val DONE = "PROBE:done"
        const val FAIL = "PROBE:fail:"
        const val CHECKPOINT = "PROBE:checkpoint:"
        const val PROBE_TIMEOUT_MS = 25 * 60 * 1000L
        const val SETTLE_MS = 2_000L
        const val LEAVE_SETTLE_MS = 10_000L

        fun checkpointOf(title: String): String? = title.takeIf { it.startsWith(CHECKPOINT) }?.removePrefix(CHECKPOINT)
    }
}
