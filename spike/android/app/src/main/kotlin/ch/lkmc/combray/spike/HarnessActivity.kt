package ch.lkmc.combray.spike

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import ch.lkmc.combray.engine.NativeEngine
import org.json.JSONObject

/**
 * Minimal manual UI around the engine surface: tab switching, navigation and capture.
 * It is a test harness, not Combray's product chrome. Started with an `m0` extra
 * (`scenario` or `phase`), it hands control to [ScenarioRunner].
 */
class HarnessActivity : Activity() {
    lateinit var surface: EngineSurfaceView
        private set
    private lateinit var status: TextView
    private lateinit var address: EditText
    private val app get() = application as HarnessApplication
    private var nextTab = 1L
    private var nextRequest = 1L

    private val listener: (JSONObject) -> Unit = { event -> onEngineEvent(event) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.engine.start()

        surface = EngineSurfaceView(this)
        status = TextView(this).apply { textSize = 12f; setPadding(12, 6, 12, 6) }
        address = EditText(this).apply {
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            hint = "http://localhost:8765/a.html"
            setOnEditorActionListener { _, _, _ -> go(); true }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("New") { newTab() })
            addView(button("Next tab") { cycleTab() })
            addView(button("Back") { selected()?.let(NativeEngine::goBack) })
            addView(button("Fwd") { selected()?.let(NativeEngine::goForward) })
            addView(button("Reload") { selected()?.let(NativeEngine::reload) })
            addView(button("Close") { closeSelected() })
            addView(button("Capture") { captureSelected() })
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(address, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(HorizontalScrollView(this@HarnessActivity).apply { addView(buttons) })
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(surface, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            // Routine controls at the bottom, as in the product brief.
            addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        app.engine.addUiListener(listener)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        app.activity = this
    }

    override fun onDestroy() {
        app.engine.removeUiListener(listener)
        if (app.activity === this) app.activity = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val request = intent.getStringExtra("m0") ?: return
        // Consume the request so a recreated Activity does not run it twice.
        intent.removeExtra("m0")
        app.runner.request(request, intent.getStringExtra("arg"))
    }

    private fun onEngineEvent(event: JSONObject) {
        when (event.getString("type")) {
            "engine_ready" -> if (!app.runner.active && app.engine.tabs().isEmpty()) {
                if (app.restoreSession() == 0) newTab()
            }
            "ime_shown" -> surface.showKeyboard()
            "ime_hidden" -> surface.hideKeyboard()
        }
        val selected = app.engine.selectedTab?.let(app.engine::tab)
        status.text = buildString {
            append("tab ${selected?.tab ?: "-"} · ")
            append(selected?.title ?: "")
            append("\n")
            append(selected?.url ?: "no page")
            if (event.getString("type") == "capture") append("\ncapture: ${event.getString("status")}")
        }
        if (event.getString("type") == "tab_created") nextTab = maxOf(nextTab, event.getLong("tab") + 1)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.CENTER
        setOnClickListener { action() }
    }

    private fun selected(): Long? = app.engine.selectedTab

    private fun newTab() {
        val tab = nextTab++
        app.engine.createTab(tab, address.text.toString().ifBlank { "about:blank" })
        NativeEngine.selectTab(tab)
    }

    private fun cycleTab() {
        val tabs = app.engine.tabs().map { it.tab }.sorted()
        if (tabs.isEmpty()) return
        val current = tabs.indexOf(selected() ?: tabs.first())
        NativeEngine.selectTab(tabs[(current + 1) % tabs.size])
    }

    private fun closeSelected() {
        val tab = selected() ?: return
        NativeEngine.closeTab(tab)
        // Select the most recently created remaining tab, so no closed page stays visible.
        app.engine.tabs().map { it.tab }.filter { it != tab }.maxOrNull()?.let(NativeEngine::selectTab)
    }

    private fun go() {
        val tab = selected() ?: return newTab()
        NativeEngine.navigate(tab, nextRequest++, address.text.toString())
    }

    private fun captureSelected() {
        val record = selected()?.let(app.engine::tab) ?: return
        val document = record.document ?: return
        NativeEngine.capture(record.tab, document, nextRequest++, DEFAULT_CAPTURE_BYTES, CAPTURE_TIMEOUT_MS, false)
    }
}
