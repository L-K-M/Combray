package ch.lkmc.combray

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import ch.lkmc.combray.core.browser.AppVisibility
import ch.lkmc.combray.core.browser.BrowserCoordinator
import ch.lkmc.combray.core.browser.BrowserSettings
import ch.lkmc.combray.core.browser.EngineStatus
import ch.lkmc.combray.core.browser.HomeLinks
import ch.lkmc.combray.core.browser.Retention
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.integration.Integrations
import ch.lkmc.combray.core.integration.KeystoreSecretStore
import ch.lkmc.combray.core.integration.OutboxDispatcher
import ch.lkmc.combray.core.integration.OutboxHost
import ch.lkmc.combray.core.integration.UrlConnectionTransport
import ch.lkmc.combray.core.integration.WorkManagerScheduler
import ch.lkmc.combray.core.media.MediaNetwork
import ch.lkmc.combray.engine.NativeEngineHost
import ch.lkmc.combray.player.PlaybackRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import androidx.media3.common.util.Log as MediaLog

class CombrayApplication : Application(), OutboxHost {
    lateinit var graph: AppGraph
        private set

    override val outboxDispatcher: OutboxDispatcher get() = graph.integrations.dispatcher

    override fun onCreate() {
        super.onCreate()
        quietMediaLogs()
        graph = AppGraph(this)
        // Uploads picked in an earlier process are no longer referenced by any page.
        graph.scope.launch(Dispatchers.IO) { graph.uploads.clear() }
        // Before any Activity: the engine must run before the first surface is created.
        graph.browser.start()
        persistProfileWhileRunning()
        expireHistoryOnReturn()
        // Saves queued before the process died go out as soon as possible.
        graph.integrations.kick()
    }

    /**
     * Media3 logs warnings and errors only, and an error's own message without its causes: the
     * causes of a failed load can name the media's address, which never reaches the log.
     */
    @OptIn(UnstableApi::class)
    private fun quietMediaLogs() {
        MediaLog.setLogStackTraces(false)
        MediaLog.setLogLevel(MediaLog.LOG_LEVEL_WARNING)
    }

    /** In the background Android may kill the app for memory: keep only the focused renderer. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) graph.browser.releaseHiddenViews()
    }

    /**
     * Servo writes cookies only when asked (patch 0004) or at a clean exit, which Android rarely
     * allows. Write them when the app leaves the foreground, and periodically while it is used,
     * so a killed process loses at most five minutes of cookie changes. The same observer feeds
     * the browser process visibility, so a backgrounded renderer is throttled.
     */
    private fun persistProfileWhileRunning() {
        val process = ProcessLifecycleOwner.get()
        process.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = graph.browser.setAppVisibility(AppVisibility.FOREGROUND)
            override fun onStop(owner: LifecycleOwner) {
                graph.browser.setAppVisibility(AppVisibility.BACKGROUND)
                graph.browser.persistProfile()
            }
        })
        process.lifecycleScope.launch {
            process.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(PROFILE_PERSIST_INTERVAL_MS)
                    graph.browser.persistProfile()
                }
            }
        }
    }

    /** A process can live for days; closed history past retention goes each time the app returns. */
    private fun expireHistoryOnReturn() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = graph.browser.runMaintenance()
        })
    }

    private companion object {
        // Each write briefly holds the engine thread (cookie, auth and HSTS files are fsynced).
        const val PROFILE_PERSIST_INTERVAL_MS = 5 * 60_000L
    }
}

/**
 * Process-scoped objects. The engine, the database and the coordinator outlive Activities:
 * rotation and surface loss never close tabs.
 */
class AppGraph(application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = CombrayDatabase.open(application)
    private val engine = NativeEngineHost(
        profileDir = File(application.filesDir, "servo-profile"),
        density = application.resources.displayMetrics.density,
    )
    val integrations = Integrations(
        database = database,
        secrets = KeystoreSecretStore(application),
        transport = UrlConnectionTransport(),
        clock = { System.currentTimeMillis() },
        newId = { UUID.randomUUID().toString() },
        scope = scope,
        scheduler = WorkManagerScheduler(application),
    )
    val settings = BrowserSettings(application.getSharedPreferences(BrowserSettings.FILE_NAME, Context.MODE_PRIVATE))
    val home = HomeLinks(database)
    /** One retention policy for maintenance and the Settings label, so the two cannot diverge. */
    val retention = Retention()
    val browser = BrowserCoordinator(
        database,
        engine,
        scope,
        retention = retention,
        capture = integrations.hister,
        unusedTabs = { settings.unusedTabs.value },
        textSize = { settings.textSize.value },
    )
    val uploads = UploadStaging(File(application.noBackupFilesDir, "uploads"), application.contentResolver)
    val notices = ThirdPartyNotices(application.assets)

    /** Requests on their way to the media player: an Intent carries only an id. */
    val playback = PlaybackRequests()

    /** The media player's HTTP client, built at the first playback. */
    val media by lazy { MediaNetwork() }

    fun engineRunning(): Boolean = browser.browserState.value.engine.let {
        it == EngineStatus.STARTING || it == EngineStatus.READY
    }
}
