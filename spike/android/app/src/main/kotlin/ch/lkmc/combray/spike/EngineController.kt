package ch.lkmc.combray.spike

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ch.lkmc.combray.engine.NativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

const val LOG_TAG = "CombrayM0"
private const val LOGGED_CODE_POINTS = 120

/** A logical tab as the harness sees it, rebuilt from engine events. */
data class TabRecord(
    val tab: Long,
    val generation: Long,
    val document: Long?,
    val url: String?,
    val title: String?,
)

/**
 * Process-scoped owner of the engine: survives Activity recreation (rotation, surface loss).
 *
 * Engine events arrive on the engine thread. They are recorded in [events] (for the scenario
 * runner), folded into [tabs] and forwarded to UI listeners on the main thread.
 */
class EngineController(private val app: Application) {
    val events = EventLog()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiListeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    private val tabState = LinkedHashMap<Long, TabRecord>()
    private val sessionFile = File(app.filesDir, "m0-session.json")
    /**
     * URL requested for each tab that has not committed yet. Saved in its place, so a restore
     * cannot overwrite the session with tabs that have no URL (and lose them on a kill).
     */
    private val pendingUrls = ConcurrentHashMap<Long, String>()
    /** Session writes leave the engine thread, so file I/O never delays event delivery. */
    private val sessionWriter = Executors.newSingleThreadExecutor()

    @Volatile var selectedTab: Long? = null
        private set

    @Volatile var started = false
        private set

    fun start(): Boolean = synchronized(this) {
        if (started) return true
        val profile = File(app.filesDir, "servo-profile")
        started = NativeEngine.start(profile.path, app.resources.displayMetrics.density) { json ->
            onEvent(json)
        }
        Log.i(LOG_TAG, "engine start: $started (profile ${profile.path})")
        started
    }

    /** Creates a tab; use this rather than [NativeEngine.createTab] so the session keeps it. */
    fun createTab(tab: Long, url: String) {
        pendingUrls[tab] = url
        NativeEngine.createTab(tab, url)
    }

    fun addUiListener(listener: (JSONObject) -> Unit) = uiListeners.add(listener)

    fun removeUiListener(listener: (JSONObject) -> Unit) = uiListeners.remove(listener)

    fun tabs(): List<TabRecord> = synchronized(tabState) { tabState.values.toList() }

    fun tab(tab: Long): TabRecord? = synchronized(tabState) { tabState[tab] }

    private fun onEvent(json: String) {
        val event = JSONObject(json)
        updateState(event)
        // Log before sharing the event: redaction briefly edits it in place.
        Log.i(LOG_TAG, "EVENT ${redacted(event)}")
        events.append(event)
        mainHandler.post { uiListeners.forEach { it(event) } }
    }

    private fun updateState(event: JSONObject) {
        val tab = event.optLong("tab", -1)
        val changed = synchronized(tabState) {
            when (event.getString("type")) {
                "tab_created" -> {
                    tabState[tab] = TabRecord(tab, event.getLong("generation"), null, null, null)
                    false
                }
                "document_committed", "same_document_navigation", "history_updated" -> {
                    val record = tabState[tab] ?: return
                    if (record.generation != event.getLong("generation")) return
                    pendingUrls.remove(tab)
                    val newDocument = event.getString("type") == "document_committed"
                    tabState[tab] = record.copy(
                        document = event.getLong("document"),
                        url = event.getString("url"),
                        title = if (newDocument) null else record.title,
                    )
                    true
                }
                "title" -> {
                    val record = tabState[tab] ?: return
                    if (event.getString("attribution") == "stale") return
                    if (record.document != event.getLong("document")) return
                    tabState[tab] = record.copy(title = event.optString("title", ""))
                    false
                }
                "tab_closed" -> {
                    tabState.remove(tab)
                    pendingUrls.remove(tab)
                    if (selectedTab == tab) selectedTab = null
                    true
                }
                "tab_selected" -> {
                    selectedTab = tab
                    true
                }
                else -> false
            }
        }
        if (changed) persistSession()
    }

    /**
     * Last committed URL per open tab, written on every commit. This is the M0 recovery
     * guarantee: URLs and selection, not Servo's back/forward stacks or page state.
     */
    private fun persistSession() {
        val tabs = JSONArray()
        tabs().forEach { record ->
            val url = record.url ?: pendingUrls[record.tab] ?: return@forEach
            tabs.put(JSONObject().put("tab", record.tab).put("url", url))
        }
        val session = JSONObject().put("selected", selectedTab ?: JSONObject.NULL).put("tabs", tabs).toString()
        sessionWriter.execute {
            val temporary = File(sessionFile.path + ".tmp")
            temporary.writeText(session)
            temporary.renameTo(sessionFile)
        }
    }

    fun savedSession(): JSONObject? =
        if (sessionFile.exists()) JSONObject(sessionFile.readText()) else null

    /** Log form of an event: captured text is shortened so logs stay small. */
    private fun redacted(event: JSONObject): String {
        val content = event.optJSONObject("content") ?: return event.toString()
        val text = content.optString("text")
        if (text.codePointCount(0, text.length) <= LOGGED_CODE_POINTS) return event.toString()
        // Swap the text out for serialization only (no deep copy of up to 256 KiB).
        content.put("text", text.substring(0, text.offsetByCodePoints(0, LOGGED_CODE_POINTS)) + "…")
        val line = event.toString()
        content.put("text", text)
        return line
    }
}

/** Append-only record of engine events with blocking waits for the scenario runner. */
class EventLog {
    private val lock = Object()
    private val events = ArrayList<JSONObject>()

    fun append(event: JSONObject) = synchronized(lock) {
        events.add(event)
        lock.notifyAll()
    }

    fun size(): Int = synchronized(lock) { events.size }

    fun since(from: Int): List<JSONObject> = synchronized(lock) {
        events.subList(from.coerceAtMost(events.size), events.size).toList()
    }

    /** First event at index >= [from] matching [predicate], waiting up to [timeoutMs]. */
    fun await(from: Int, timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var index = from
        synchronized(lock) {
            while (true) {
                while (index < events.size) {
                    val event = events[index++]
                    if (predicate(event)) return event
                }
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0) return null
                lock.wait(remaining)
            }
        }
    }
}
