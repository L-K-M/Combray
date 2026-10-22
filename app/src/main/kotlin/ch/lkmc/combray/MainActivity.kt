package ch.lkmc.combray

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.combray.core.browser.SessionState
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.ui.BrowserScreen
import ch.lkmc.combray.ui.CombrayTheme

class MainActivity : ComponentActivity() {
    private val graph get() = (application as CombrayApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        // First, before the window's views exist (enableEdgeToEdge, super.onCreate): the launch window
        // (Theme.Combray.Starting) stays until the stored session is known, so "New tab" never
        // flashes in place of restored tabs. Bounded: a slow restore never holds the app behind it.
        val splash = installSplashScreen()
        val splashShownAt = SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition {
            graph.browser.browserState.value.session == SessionState.RESTORING &&
                SystemClock.uptimeMillis() - splashShownAt < MAX_SPLASH_MILLIS
        }
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
            // The owner's choice in Settings, or the system's.
            val themeMode by graph.settings.themeMode.collectAsStateWithLifecycle()
            CombrayTheme(themeMode) {
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

    private companion object {
        /** The longest the launch window waits for the session; reading it normally takes far less. */
        const val MAX_SPLASH_MILLIS = 2_000L
    }
}
