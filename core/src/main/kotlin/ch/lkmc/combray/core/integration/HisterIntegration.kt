package ch.lkmc.combray.core.integration

import androidx.room.withTransaction
import ch.lkmc.combray.core.browser.CaptureSink
import ch.lkmc.combray.core.browser.CapturedPage
import ch.lkmc.combray.core.browser.Clock
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.ExclusionEntity
import ch.lkmc.combray.core.data.JobKind
import ch.lkmc.combray.core.data.OutboxJobEntity
import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import ch.lkmc.combray.core.policy.HostNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicLong

@Serializable
internal data class CapturePayload(val url: String, val title: String? = null, val text: String)

/**
 * Hister: opt-in capture of rendered page text and explicit long-term search.
 *
 * Policy is checked when a capture is requested, when its text arrives, and again right before
 * sending. A policy generation changes with every relevant setting, so text captured under an
 * older policy is dropped. Forgetting a site also cancels its unsent captures and keeps
 * tombstones, so a capture already under way cannot bring the content back.
 */
class HisterIntegration(
    private val database: CombrayDatabase,
    private val secrets: SecretStore,
    transport: HttpTransport,
    private val clock: Clock,
    private val outbox: Outbox,
    private val kick: () -> Unit,
) : CaptureSink {
    private val dao = database.integrations()
    private val routes = ServiceRoutes(transport)
    private val policyGeneration = AtomicLong(1)
    private val lastQueuedHash = object : LinkedHashMap<String, String>(HASH_MEMORY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > HASH_MEMORY
    }
    private val forgotten = HashMap<String, Long>()
    private val budgetReached = MutableStateFlow(false)

    /** Configuration changes and dispatch read/write the service row and its token together. */
    private val config = Mutex()
    private val credentials = ServiceCredentials(secrets)

    val service: Flow<ServiceEntity?> = dao.observeService(ServiceKind.HISTER)
    val exclusions: Flow<List<ExclusionEntity>> = dao.observeExclusions()
    val queuedBytes: Flow<Long> = dao.observeQueuedCaptureBytes()

    /** Automatic capture is paused because unsent captures reached the content budget. */
    val captureBudgetReached: StateFlow<Boolean> = budgetReached.asStateFlow()

    /**
     * Saves the root, an optional alternate address and, if given, a new token. Capture stays as
     * it was (off at first).
     */
    suspend fun configure(rootInput: String, token: String?, alternateInput: String? = null): Configured {
        val addresses = when (val checked = ServiceUrls.normalizeAddresses(rootInput, alternateInput, HISTER_SUFFIXES)) {
            is ServiceAddresses.Invalid -> return Configured.InvalidRoot(checked.problem, checked.field)
            is ServiceAddresses.Valid -> checked
        }
        val secret = token?.trim()?.takeIf { it.isNotEmpty() }
        if (secret != null && !isHeaderSafe(secret)) return Configured.InvalidCredential
        val fingerprint = addresses.fingerprint
        // Not cancellable: leaving the screen must not stop between the token and the configuration.
        val result = withContext(NonCancellable) {
            config.withLock {
                val previous = dao.service(ServiceKind.HISTER)
                // A stored token belongs to the addresses it was entered for, never to a new one.
                val keepsServer = previous != null && previous.fingerprint == fingerprint
                val knownAddresses = keepsServer && (addresses.alternate == null || addresses.alternate == previous?.alternateUrl)
                val stored = credentials.read(SecretName.HISTER_TOKEN, fingerprint) != null
                if (secret == null && !(knownAddresses && stored)) return@withLock Configured.MissingCredential
                // Same server, stored token kept: queued captures keep going. At most the alternate was dropped.
                if (secret == null) {
                    if (previous != null && previous.alternateUrl != addresses.alternate) {
                        dao.setService(previous.copy(alternateUrl = addresses.alternate, updatedAt = clock.now()))
                    }
                    return@withLock Configured.Saved
                }
                if (!credentials.write(SecretName.HISTER_TOKEN, fingerprint, secret)) return@withLock Configured.CredentialNotStored
                database.withTransaction {
                    val current = ServiceEntity(
                        kind = ServiceKind.HISTER,
                        baseUrl = addresses.root,
                        fingerprint = fingerprint,
                        generation = outbox.nextGeneration(ServiceKind.HISTER, previous),
                        captureEnabled = previous?.captureEnabled == true && keepsServer,
                        updatedAt = clock.now(),
                        alternateUrl = addresses.alternate,
                    )
                    dao.setService(current)
                    outbox.onServiceChanged(previous, current)
                }
                Configured.Saved
            }
        }
        bumpPolicy()
        return result
    }

    suspend fun remove() {
        withContext(NonCancellable) {
            config.withLock {
                database.withTransaction {
                    val previous = dao.service(ServiceKind.HISTER)
                    dao.deleteService(ServiceKind.HISTER)
                    outbox.onServiceChanged(previous, null)
                }
                credentials.delete(SecretName.HISTER_TOKEN)
            }
        }
        bumpPolicy()
    }

    /** Switches automatic capture on or off. Off also holds unsent captures until it is on again. */
    suspend fun setCaptureEnabled(enabled: Boolean) {
        // Under the configuration lock: a concurrent configure must not restore the old switch.
        config.withLock {
            database.withTransaction {
                val service = dao.service(ServiceKind.HISTER) ?: return@withTransaction
                dao.setService(service.copy(captureEnabled = enabled, updatedAt = clock.now()))
            }
        }
        bumpPolicy()
        if (enabled) kick()
    }

    /**
     * Never capture a site; unsent captures for it are cancelled. Accepts what people paste or
     * type ("https://Bank.example:443/login", "*.bank.example"); returns false for anything that
     * is not a site name, rather than storing an exclusion that could never match.
     */
    suspend fun addExclusion(input: String, includeSubdomains: Boolean): Boolean {
        val site = siteOf(input) ?: return false
        val subdomains = includeSubdomains || site.wildcard
        dao.setExclusion(ExclusionEntity(site.host, subdomains, clock.now()))
        bumpPolicy()
        outbox.cancelOpen(JobKind.HISTER_ADD, "excluded") { job -> jobHost(job)?.let { HostNames.matches(it, site.host, subdomains) } == true }
        return true
    }

    suspend fun removeExclusion(host: String) {
        dao.deleteExclusion(host)
        bumpPolicy()
    }

    suspend fun check(): ConnectionCheck? {
        val (service, token) = snapshot()
        service ?: return null
        token ?: return ConnectionCheck.CredentialMissing
        return withContext(Dispatchers.IO) {
            routes.call(service) { transport, root -> HisterClient(transport).checkConnection(root, token) }
        }
    }

    /** Explicit long-term search. Null when Hister is not configured. */
    suspend fun search(query: String, pageKey: String? = null): HisterSearch? {
        val (service, token) = snapshot()
        service ?: return null
        token ?: return HisterSearch.Unauthorized
        return withContext(Dispatchers.IO) {
            routes.call(service) { transport, root -> HisterClient(transport).search(root, token, query, pageKey) }
        }
    }

    /** The service row and its token, read together. */
    private suspend fun snapshot(): Pair<ServiceEntity?, String?> = config.withLock {
        val service = dao.service(ServiceKind.HISTER)
        service to service?.let { credentials.read(SecretName.HISTER_TOKEN, it.fingerprint) }
    }

    /** Captures in flight under the old policy are dropped; content may be sent again. */
    private fun bumpPolicy() {
        policyGeneration.incrementAndGet()
        synchronized(lastQueuedHash) { lastQueuedHash.clear() }
    }

    // --- CaptureSink --------------------------------------------------------------------------

    override suspend fun wantsCapture(url: String): Long? {
        val service = dao.service(ServiceKind.HISTER)?.takeIf { it.captureEnabled } ?: return null
        if (!eligible(url, service)) return null
        return policyGeneration.get()
    }

    override suspend fun captured(page: CapturedPage) {
        if (page.policyGeneration != policyGeneration.get()) return
        val service = dao.service(ServiceKind.HISTER)?.takeIf { it.captureEnabled } ?: return
        if (!eligible(page.url, service) || page.text.isBlank()) return

        val key = documentKey(page.url)
        if (synchronized(lastQueuedHash) { lastQueuedHash[key] } == page.sha256) return
        val payload = json.encodeToString(CapturePayload.serializer(), CapturePayload(page.url, page.title, page.text))
        val bytes = payload.toByteArray(Charsets.UTF_8).size
        if (dao.queuedCaptureBytes() + bytes > MAX_QUEUED_CAPTURE_BYTES) {
            budgetReached.value = true
            return
        }
        budgetReached.value = false
        val enqueued = outbox.enqueue(JobKind.HISTER_ADD, service, Binding.SAME_DESTINATION, key, payload, supersede = true)
        if (enqueued !is Enqueued.Queued) return
        // Remembered only under the policy it was captured with: a policy change since then
        // cleared the memory, and this hash must not survive that.
        synchronized(lastQueuedHash) { if (policyGeneration.get() == page.policyGeneration) lastQueuedHash[key] = page.sha256 }
        kick()
    }

    override suspend fun forgetHost(host: String, includeSubdomains: Boolean) {
        val now = clock.now()
        synchronized(forgotten) { forgotten[if (includeSubdomains) ".$host" else host] = now }
        bumpPolicy()
        outbox.cancelOpen(JobKind.HISTER_ADD, "forgotten") { job ->
            jobHost(job)?.let { HostNames.matches(it, host, includeSubdomains) } == true
        }
    }

    override suspend fun forgetUrl(url: String) {
        val key = documentKey(url)
        synchronized(forgotten) { forgotten["url:$key"] = clock.now() }
        bumpPolicy()
        outbox.cancelOpen(JobKind.HISTER_ADD, "forgotten") { it.dedupeKey == key }
    }

    // --- Delivery -----------------------------------------------------------------------------

    internal suspend fun dispatch(job: OutboxJobEntity): DispatchResult {
        val (service, token) = snapshot()
        if (service == null || service.fingerprint != job.fingerprint) return DispatchResult.Cancelled("destination_changed")
        if (service.generation != job.generation) return DispatchResult.AuthRequired("credential_changed")
        val payload = json.decodeFromString(CapturePayload.serializer(), job.payload)
        // The policy may have changed since the capture: recheck right before sending.
        if (!eligible(payload.url, service) || wasForgotten(job, payload.url)) return DispatchResult.Cancelled("excluded")
        token ?: return DispatchResult.AuthRequired("credential_missing")
        return routes.call(service) { transport, root -> HisterClient(transport).add(root, token, payload.url, payload.title, payload.text) }
    }

    private suspend fun eligible(url: String, service: ServiceEntity): Boolean {
        if (!HostNames.isWeb(url) || SensitiveUrls.looksSensitive(url)) return false
        val host = HostNames.of(url) ?: return false
        // The services' own pages (accounts, settings, search results) are never captured. Kagi
        // result pages hold the user's queries: not captured by default (an open owner question).
        val serviceRoots = service.roots + dao.service(ServiceKind.KARAKEEP)?.roots.orEmpty()
        val serviceHosts = serviceRoots.mapNotNull(HostNames::of) + KAGI_HOST
        if (serviceHosts.any { HostNames.matches(host, it, includeSubdomains = true) }) return false
        return dao.exclusions().none { HostNames.matches(host, it.host, it.includeSubdomains) }
    }

    private fun wasForgotten(job: OutboxJobEntity, url: String): Boolean = synchronized(forgotten) {
        val host = HostNames.of(url) ?: return true
        forgotten.any { (site, at) ->
            at >= job.createdAt && when {
                site.startsWith("url:") -> site.removePrefix("url:") == job.dedupeKey
                site.startsWith(".") -> HostNames.matches(host, site.removePrefix("."), includeSubdomains = true)
                else -> host == site
            }
        }
    }

    private data class Site(val host: String, val wildcard: Boolean)

    private fun siteOf(input: String): Site? {
        var text = input.trim().lowercase()
        text = text.replace(Regex("^[a-z][a-z0-9+.-]*://"), "")
        text = text.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        // "*.site" and ".site" mean subdomains; "*site" is not a site name.
        if (text.startsWith("*") && !text.startsWith("*.")) return null
        val wildcard = text.startsWith("*.") || text.startsWith(".")
        text = text.removePrefix("*").removePrefix(".")
        // An IPv6 address needs brackets; otherwise its colons read as a port.
        if (!text.startsWith("[") && text.count { it == ':' } > 1) return null
        text = if (text.startsWith("[")) text.substringBefore(']') + "]" else text.substringBefore(':')
        if (text.isEmpty() || text.any { it == '*' || it.isWhitespace() }) return null
        val host = HostNames.normalize(text) ?: return null
        if (!text.startsWith("[") && host.split('.').any { it.isEmpty() }) return null
        return Site(host, wildcard)
    }

    private fun jobHost(job: OutboxJobEntity): String? =
        runCatching { json.decodeFromString(CapturePayload.serializer(), job.payload).url }.getOrNull()?.let(HostNames::of)

    companion object {
        val HISTER_SUFFIXES = listOf("/api")
        const val KAGI_HOST = "kagi.com"

        /** Proposed transient content budget for unsent captures. */
        const val MAX_QUEUED_CAPTURE_BYTES = 20L * 1024 * 1024

        private const val HASH_MEMORY = 512
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The document key Hister uses at the inspected revision: the URL without its fragment
         * and `utm_*` parameters. Captures with the same key replace each other.
         */
        fun documentKey(url: String): String {
            val uri = try {
                URI(url)
            } catch (malformed: URISyntaxException) {
                return url.substringBefore('#')
            }
            val query = uri.rawQuery?.split('&')?.filterNot { it.lowercase().startsWith("utm_") }?.joinToString("&")?.takeIf { it.isNotEmpty() }
            // Raw parts as received: re-encoding them would change the key.
            val base = "${uri.scheme?.lowercase()}://${uri.rawAuthority?.lowercase().orEmpty()}${uri.rawPath.orEmpty()}"
            return if (query != null) "$base?$query" else base
        }
    }
}
