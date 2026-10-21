package ch.lkmc.combray

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.ui.BrowserScreen
import ch.lkmc.combray.ui.CombrayTheme

class MainActivity : ComponentActivity() {
    private val graph get() = (application as CombrayApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // The bottom bar's own color runs under the navigation bar and gives its buttons their
        // contrast, so three-button navigation needs no system scrim over it.
        window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        // Only a fresh launch carries a new link. Recreation (rotation, process restore) does not,
        // nor a relaunch from Recents, which replays the task's original intent.
        val fromRecents = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromRecents) openLink(intent)
        setContent {
            CombrayTheme {
                // Back from a page another app opened returns to that app.
                BrowserScreen(graph, onLeave = { moveTaskToBack(true) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    /** External http(s) links open in a new tab through the same address policy as typing. */
    private fun openLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val url = intent.dataString ?: return
        val decision = AddressPolicy.interpret(url).primary
        if (decision is AddressDecision.Navigate) graph.browser.openFromOtherApp(decision.url)
    }
}
