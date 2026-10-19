package ch.lkmc.combray.spike

import android.app.Application
import android.util.Log
import ch.lkmc.combray.engine.NativeEngine

class HarnessApplication : Application() {
    val engine by lazy { EngineController(this) }
    val runner by lazy { ScenarioRunner(this) }

    /** The visible harness Activity, if any. Set and cleared on the main thread. */
    @Volatile var activity: HarnessActivity? = null

    /**
     * Recreate tabs from the saved session (last committed URL per tab and the selection).
     * Returns the number of tabs requested. Back/forward history and page state are not restored.
     */
    fun restoreSession(): Int {
        val session = engine.savedSession() ?: return 0
        val tabs = session.getJSONArray("tabs")
        for (index in 0 until tabs.length()) {
            val saved = tabs.getJSONObject(index)
            engine.createTab(saved.getLong("tab"), saved.getString("url"))
        }
        val selected = session.optLong("selected", -1)
        if (selected >= 0) NativeEngine.selectTab(selected)
        Log.i(LOG_TAG, "restored ${tabs.length()} tab(s) from session")
        return tabs.length()
    }
}
