package ch.lkmc.combray

import android.os.Debug
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.combray.core.browser.Placement
import java.net.HttpURLConnection
import java.net.URL
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

    private fun probe(engine: String, csp: String) {
        device.browser.openUrl("${Fixtures.BASE}/pdfprobe/$csp/index.html?engine=$engine", Placement.CURRENT_TAB)
        val handled = mutableSetOf<String>()
        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            check(remaining > 0) { "probe $engine/$csp did not finish: ${device.browser.browserState.value.activeTab?.title}" }
            val title = device.awaitState(remaining) { state ->
                val title = state.activeTab?.title.orEmpty()
                title == DONE || title.startsWith(FAIL) || checkpointOf(title)?.let { it !in handled } == true
            }.activeTab?.title.orEmpty()
            if (title == DONE) return
            check(!title.startsWith(FAIL)) { "probe $engine/$csp broke: $title" }

            val name = checkpointOf(title)!!
            handled += name
            // Let the page's last frames and the engine's frees land before measuring.
            Thread.sleep(SETTLE_MS)
            post("/_admin/probe-memory", memory(engine, csp, name).toString())
            post("/_admin/probe-release?engine=$engine&csp=$csp&name=$name", "")
        }
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

        fun checkpointOf(title: String): String? = title.takeIf { it.startsWith(CHECKPOINT) }?.removePrefix(CHECKPOINT)
    }
}
