package ch.lkmc.combray.core.integration

import android.util.Log
import androidx.room.withTransaction
import ch.lkmc.combray.core.browser.Clock
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.JobKind
import ch.lkmc.combray.core.data.JobState
import ch.lkmc.combray.core.data.OutboxJobEntity
import ch.lkmc.combray.core.data.SavedItemEntity
import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import ch.lkmc.combray.core.policy.HostNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The Save button's state for one address. */
sealed interface SaveStatus {
    data object NotConfigured : SaveStatus

    data object Unsaved : SaveStatus

    /** Recorded durably; waiting for (or in) delivery. */
    data object Pending : SaveStatus

    /** The server acknowledged the link (not that crawling or tagging finished). */
    data object Saved : SaveStatus

    /** Delivery stopped: [reason] is an error category; Settings offers Retry or Discard. */
    data class NeedsAttention(val reason: String?) : SaveStatus
}

sealed interface SaveRequest {
    data object NotConfigured : SaveRequest

    /** Internal pages, blank tabs and non-web addresses cannot be saved. */
    data object NotSaveable : SaveRequest

    /** The address seems to carry a login or session credential; it is not sent. */
    data object Sensitive : SaveRequest

    data object Queued : SaveRequest

    data object AlreadyQueued : SaveRequest

    /** A save of this page is paused (credential rejected or changed); Settings can resume it. */
    data object NeedsAttention : SaveRequest
}

sealed interface Configured {
    data object Saved : Configured

    data class InvalidRoot(val problem: ServiceRootProblem) : Configured

    /** No credential given and none stored. */
    data object MissingCredential : Configured

    /** The credential has characters a key or token cannot have, such as a pasted line break. */
    data object InvalidCredential : Configured

    /** The device could not store the credential (Keystore or storage failure); nothing changed. */
    data object CredentialNotStored : Configured
}

/** API keys and tokens are printable ASCII; control characters cannot travel in an HTTP header. */
internal fun isHeaderSafe(secret: String) = secret.all { it in ' '..'~' }

@Serializable
internal data class SavePayload(val url: String, val title: String? = null)

/**
 * Integration settings, credentials and actions for the UI. Saves snapshot the page's address
 * and title when the user taps, and stay bound to the destination configured at that moment.
 */
class Integrations(
    private val database: CombrayDatabase,
    private val secrets: SecretStore,
    transport: HttpTransport,
    private val clock: Clock,
    private val newId: () -> String,
    /** Hosts allowed over plain HTTP: the fixture loopback in debug builds, none in release. */
    private val cleartextHosts: Set<String>,
    private val scope: CoroutineScope,
    scheduler: WakeupScheduler,
) {
    private val dao = database.integrations()
    private val karakeep = KarakeepClient(transport)

    /** Configuration changes and dispatch read/write the service row and its secret together. */
    private val karakeepConfig = Mutex()
    val outbox = Outbox(database, clock, newId)

    /** Hister settings, capture (the browser's [ch.lkmc.combray.core.browser.CaptureSink]) and search. */
    val hister = HisterIntegration(database, secrets, transport, clock, outbox, cleartextHosts, ::kick)
    private val credentials = ServiceCredentials(secrets)

    val dispatcher = OutboxDispatcher(
        outbox = outbox,
        handlers = mapOf(
            JobKind.KARAKEEP_SAVE to JobHandler { dispatchSave(it) },
            JobKind.HISTER_ADD to JobHandler { hister.dispatch(it) },
        ),
        onDone = ::recordDone,
        scheduler = scheduler,
    )

    val karakeepService: Flow<ServiceEntity?> = dao.observeService(ServiceKind.KARAKEEP)

    fun openJobs(kind: ServiceKind): Flow<List<OutboxJobEntity>> = dao.observeOpenJobs(kind)

    /** Keystore work stays off the main thread. */
    suspend fun hasCredential(name: SecretName, service: ServiceEntity): Boolean = credentials.read(name, service.fingerprint) != null

    /** The address a queued save is for, for status lists. */
    fun jobUrl(job: OutboxJobEntity): String? = when (job.kind) {
        JobKind.KARAKEEP_SAVE -> runCatching { json.decodeFromString(SavePayload.serializer(), job.payload).url }.getOrNull()
        JobKind.HISTER_ADD -> runCatching { json.decodeFromString(CapturePayload.serializer(), job.payload).url }.getOrNull()
    }

    /**
     * Saves the Karakeep root and, if given, a new API key. Any change starts a new generation:
     * unfinished saves for another server are cancelled; saves for this server pause until resumed.
     */
    suspend fun configureKarakeep(rootInput: String, apiKey: String?): Configured {
        val root = when (val checked = ServiceUrls.normalizeRoot(rootInput, KARAKEEP_SUFFIXES, cleartextHosts)) {
            is ServiceRoot.Invalid -> return Configured.InvalidRoot(checked.reason)
            is ServiceRoot.Valid -> checked.root
        }
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (key != null && !isHeaderSafe(key)) return Configured.InvalidCredential
        val fingerprint = ServiceUrls.fingerprint(root)
        // Not cancellable: leaving the screen must not stop between the key and the configuration.
        return withContext(NonCancellable) {
            karakeepConfig.withLock {
                val previous = dao.service(ServiceKind.KARAKEEP)
                // A stored key belongs to the server it was entered for, never to a new one.
                val keepsServer = previous != null && previous.fingerprint == fingerprint
                if (key == null && !(keepsServer && credentials.read(SecretName.KARAKEEP_API_KEY, fingerprint) != null)) {
                    return@withLock Configured.MissingCredential
                }
                // Same server, stored key kept: nothing changed, and queued saves keep going.
                if (key == null) return@withLock Configured.Saved
                if (!credentials.write(SecretName.KARAKEEP_API_KEY, fingerprint, key)) return@withLock Configured.CredentialNotStored
                database.withTransaction {
                    val current = ServiceEntity(
                        kind = ServiceKind.KARAKEEP,
                        baseUrl = root,
                        fingerprint = fingerprint,
                        generation = outbox.nextGeneration(ServiceKind.KARAKEEP, previous),
                        captureEnabled = false,
                        updatedAt = clock.now(),
                    )
                    dao.setService(current)
                    outbox.onServiceChanged(previous, current)
                }
                Configured.Saved
            }
        }
    }

    suspend fun removeKarakeep() = withContext(NonCancellable) {
        karakeepConfig.withLock {
            database.withTransaction {
                val previous = dao.service(ServiceKind.KARAKEEP)
                dao.deleteService(ServiceKind.KARAKEEP)
                outbox.onServiceChanged(previous, null)
            }
            credentials.delete(SecretName.KARAKEEP_API_KEY)
        }
    }

    /** Read-only request with the stored root and key. Never creates anything on the server. */
    suspend fun checkKarakeep(): ConnectionCheck? {
        val (service, key) = karakeepConfig.withLock {
            val service = dao.service(ServiceKind.KARAKEEP) ?: return null
            service to credentials.read(SecretName.KARAKEEP_API_KEY, service.fingerprint)
        }
        if (key == null) return ConnectionCheck.CredentialMissing
        return withContext(Dispatchers.IO) { karakeep.checkConnection(service.baseUrl, key) }
    }

    /** One tap on Save: durable before this returns, then sent in the background. */
    suspend fun save(url: String?, title: String?): SaveRequest {
        if (url == null || !HostNames.isWeb(url)) return SaveRequest.NotSaveable
        if (SensitiveUrls.looksSensitive(url)) return SaveRequest.Sensitive
        val service = dao.service(ServiceKind.KARAKEEP) ?: return SaveRequest.NotConfigured
        val payload = json.encodeToString(SavePayload.serializer(), SavePayload(url, title))
        val enqueued = outbox.enqueue(JobKind.KARAKEEP_SAVE, service, Binding.CURRENT, dedupeKey = url, payload = payload, supersede = false)
        kick()
        return when (enqueued) {
            is Enqueued.Queued -> SaveRequest.Queued
            is Enqueued.AlreadyQueued -> if (enqueued.job.state == JobState.AUTH_REQUIRED) SaveRequest.NeedsAttention else SaveRequest.AlreadyQueued
            // Removed in Settings between the tap and queuing.
            Enqueued.DestinationChanged -> SaveRequest.NotConfigured
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun saveStatus(url: String?): Flow<SaveStatus> = karakeepService.flatMapLatest { service ->
        when {
            service == null -> flowOf(SaveStatus.NotConfigured)
            url == null -> flowOf(SaveStatus.Unsaved)
            else -> combine(dao.observeSaved(service.fingerprint, url), dao.observeLatestSave(service.fingerprint, url)) { saved, job ->
                when {
                    job != null && job.state == JobState.FAILED -> SaveStatus.NeedsAttention(job.error)
                    job != null && job.state == JobState.AUTH_REQUIRED -> SaveStatus.NeedsAttention(job.error)
                    job != null -> SaveStatus.Pending
                    saved != null -> SaveStatus.Saved
                    else -> SaveStatus.Unsaved
                }
            }
        }
    }

    fun retry(jobId: String) = launchGuarded("retrying a job") {
        outbox.retryNow(jobId)
        dispatcher.drain()
    }

    fun discard(jobId: String) = launchGuarded("discarding a job") { outbox.discard(jobId) }

    /** After the user fixed a credential: paused jobs for the configured destination go out. */
    fun resume(kind: ServiceKind) = launchGuarded("resuming jobs") {
        outbox.resume(kind)
        dispatcher.drain()
    }

    /** User actions run on the app scope: a failure is logged (class only), never a crash. */
    private fun launchGuarded(action: String, block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e(LOG_TAG, "$action failed: ${failure.javaClass.name}")
        }
    }

    /** Sends due work now (foreground); the background worker covers the rest. */
    fun kick() {
        launchGuarded("sending queued work") { dispatcher.drain() }
    }

    private suspend fun dispatchSave(job: OutboxJobEntity): DispatchResult {
        // One snapshot of row and key: a concurrent change cannot pair a new key with an old server.
        val (service, key) = karakeepConfig.withLock {
            val service = dao.service(ServiceKind.KARAKEEP)
            service to service?.let { credentials.read(SecretName.KARAKEEP_API_KEY, it.fingerprint) }
        }
        // Settings may have changed after the claim: never send to another destination.
        if (service == null || service.fingerprint != job.fingerprint) return DispatchResult.Cancelled("destination_changed")
        if (service.generation != job.generation) return DispatchResult.AuthRequired("credential_changed")
        key ?: return DispatchResult.AuthRequired("credential_missing")
        val payload = json.decodeFromString(SavePayload.serializer(), job.payload)
        return karakeep.createLink(service.baseUrl, key, payload.url, payload.title)
    }

    private suspend fun recordDone(job: OutboxJobEntity, done: DispatchResult.Done) {
        if (job.kind != JobKind.KARAKEEP_SAVE) return
        val payload = json.decodeFromString(SavePayload.serializer(), job.payload)
        dao.setSaved(SavedItemEntity(job.fingerprint, payload.url, payload.title, done.remoteId, clock.now()))
    }

    companion object {
        private const val LOG_TAG = "CombrayIntegrations"

        /** API suffixes users paste with the root; removed so endpoints are not doubled. */
        val KARAKEEP_SUFFIXES = listOf("/api/v1", "/api")

        private val json = Json { ignoreUnknownKeys = true }
    }
}
