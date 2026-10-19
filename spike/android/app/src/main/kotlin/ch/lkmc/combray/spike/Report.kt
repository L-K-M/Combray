package ch.lkmc.combray.spike

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class CheckStatus { PASSED, FAILED, NOT_RUN }

/**
 * Results of one scenario or phase. Each result is logged as `M0CHECK {json}` when recorded,
 * and the whole report is written to `files/m0-report-<name>.json` when finished, so the host
 * script can collect it even if the process dies afterwards.
 */
class Report(private val context: Context, private val name: String) {
    private val results = JSONArray()
    private val startedAt = System.currentTimeMillis()

    fun record(id: String, status: CheckStatus, detail: JSONObject = JSONObject()) {
        val result = JSONObject()
            .put("id", id)
            .put("status", status.name.lowercase())
            .put("detail", detail)
        synchronized(results) { results.put(result) }
        Log.i(LOG_TAG, "M0CHECK $result")
        write()
    }

    fun passIf(id: String, condition: Boolean, detail: JSONObject) =
        record(id, if (condition) CheckStatus.PASSED else CheckStatus.FAILED, detail)

    /** Ask the host script to take a screenshot now; it watches logcat for this marker. */
    fun screenshot(label: String) {
        Log.i(LOG_TAG, "M0SCREENSHOT $name-$label")
        Thread.sleep(SCREENSHOT_PAUSE_MS)
    }

    fun finish() {
        write()
        Log.i(LOG_TAG, "M0DONE $name")
    }

    private fun write() {
        val report = JSONObject()
            .put("scenario", name)
            .put("started_at_ms", startedAt)
            .put("device", JSONObject()
                .put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())))
            .put("results", synchronized(results) { JSONArray(results.toString()) })
        val file = File(context.filesDir, "m0-report-$name.json")
        val temporary = File(file.path + ".tmp")
        temporary.writeText(report.toString(2))
        temporary.renameTo(file)
    }

    companion object {
        private const val SCREENSHOT_PAUSE_MS = 8_000L
    }
}
