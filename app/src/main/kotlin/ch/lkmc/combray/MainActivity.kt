package ch.lkmc.combray

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.combray.core.browser.SessionState
import ch.lkmc.combray.ui.BrowserScreen
import ch.lkmc.combray.ui.CombrayTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** The launcher icon's long-press shortcuts; each comes back through [MainActivity.handleIncoming]. */
private enum class LauncherShortcut(val id: String, @StringRes val label: Int, @DrawableRes val icon: Int, val action: String) {
    NEW_TAB("new_tab", R.string.action_new_tab, R.drawable.ic_shortcut_new_tab, IncomingIntents.ACTION_NEW_TAB),
    SEARCH_HISTORY("search_history", R.string.shortcut_search_history, R.drawable.ic_shortcut_search_history, IncomingIntents.ACTION_SEARCH_HISTORY),
}

class MainActivity : ComponentActivity() {
    private val graph get() = (application as CombrayApplication).graph

    // A sheet an intent asked for, until the screen has opened it.
    private val launchRequest = MutableStateFlow<LaunchRequest?>(null)

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
        if (savedInstanceState == null) publishShortcuts()
        // Only a fresh launch carries a new intent. Recreation (rotation, process restore) does not,
        // nor a relaunch from Recents, which replays the task's original intent.
        val fromRecents = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromRecents) handleIncoming(intent)
        setContent {
            // The owner's choice in Settings, or the system's.
            val themeMode by graph.settings.themeMode.collectAsStateWithLifecycle()
            CombrayTheme(themeMode) {
                BrowserScreen(
                    graph,
                    launchRequest = launchRequest,
                    onLaunchRequestHandled = { launchRequest.compareAndSet(it, null) },
                    // Back from a page another app opened returns to that app.
                    onLeave = { moveTaskToBack(true) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /**
     * Links, shared text, "Search with Kagi" and launcher shortcuts ([IncomingIntents]). A link
     * opens in a new tab at once; the rest are sheets for the screen to open.
     */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        // Launchers rank a shortcut by its use.
        LauncherShortcut.entries.firstOrNull { it.action == intent.action }?.let {
            ShortcutManagerCompat.reportShortcutUsed(this, it.id)
        }

        val incoming = IncomingIntents.interpret(intent.action, intent.dataString) { name ->
            IncomingIntents.textExtra(intent, name)
        } ?: return
        when (incoming) {
            is Incoming.Open -> graph.browser.openFromOtherApp(incoming.url)
            is LaunchRequest -> launchRequest.value = incoming
        }
    }

    /**
     * Dynamic rather than res/xml: a static shortcut names its target package literally, and
     * the debug build's package differs. Setting the same list again changes nothing; it is done
     * here, in the foreground, because background apps' shortcut updates are rate-limited.
     */
    private fun publishShortcuts() {
        val shortcuts = LauncherShortcut.entries.map { shortcut ->
            ShortcutInfoCompat.Builder(this, shortcut.id)
                .setShortLabel(getString(shortcut.label))
                .setIcon(IconCompat.createWithResource(this, shortcut.icon))
                .setIntent(Intent(this, MainActivity::class.java).setAction(shortcut.action))
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(this, shortcuts)
    }

    private companion object {
        /** The longest the launch window waits for the session; reading it normally takes far less. */
        const val MAX_SPLASH_MILLIS = 2_000L
    }
}
