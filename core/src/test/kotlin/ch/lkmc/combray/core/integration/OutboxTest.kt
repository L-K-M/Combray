package ch.lkmc.combray.core.integration

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.JobKind
import ch.lkmc.combray.core.data.JobState
import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The durable queue's own state machine (docs/IMPLEMENTATION_PLAN.md section 6): one broken
 * job cycle must not strand the queue, abandoned claims stop at the attempt limit, a retried
 * save sends its fresh payload, and resuming starts attempts over. No HTTP is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutboxTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: CombrayDatabase
    private lateinit var outbox: Outbox
    private var now = 1_760_000_000_000L
    private var ids = 0
    private val scheduled = mutableListOf<Long?>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        outbox = Outbox(database, { now }, { "job${ids++}" })
        runBlocking {
            database.integrations().setService(ServiceEntity(ServiceKind.KARAKEEP, "https://keep.test", "fp-keep", 1, false, now))
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val service get() = runBlocking { database.integrations().service(ServiceKind.KARAKEEP) }!!

    private fun enqueue(
        payload: String = """{"url":"https://a.test/","title":"One"}""",
        dedupeKey: String = "https://a.test/",
        url: String? = null,
    ) = runBlocking {
        outbox.enqueue(JobKind.KARAKEEP_SAVE, service, Binding.CURRENT, dedupeKey, payload, supersede = false, url = url)
    }

    private fun job(id: String) = runBlocking { database.integrations().job(id) }!!

    /** What a crashed worker leaves behind: a claim that is never finished. */
    private fun claimAndAbandon(owner: String) {
        val claim = runBlocking { outbox.claim(owner, leaseMs = LEASE_MS) }
        assertNotNull("the job must stay claimable while attempts remain", claim)
        now += LEASE_MS + 1
    }

    private fun claimAndFinish(result: DispatchResult) = runBlocking {
        val claim = outbox.claim("owner", LEASE_MS)!!
        outbox.finish(claim, result)
    }

    @Test
    fun oneBrokenJobCycleDoesNotStrandTheRestOrTheWakeup() {
        val first = enqueue(dedupeKey = "https://a.test/") as Enqueued.Queued
        val second = enqueue(dedupeKey = "https://b.test/") as Enqueued.Queued
        val handler = JobHandler { DispatchResult.Done(remoteId = "kept") }
        var recordingBroken = true
        val dispatcher = OutboxDispatcher(
            outbox = outbox,
            handlers = mapOf(JobKind.KARAKEEP_SAVE to handler),
            // Recording the first finished save fails; later records work.
            onDone = { _, _ -> if (recordingBroken) { recordingBroken = false; error("recording failed") } },
            scheduler = { scheduled += it },
        )

        val sent = runBlocking { dispatcher.drain() }

        // The second job went out and is recorded; the count says what was finished.
        assertEquals(1, sent)
        assertEquals(JobState.SUCCEEDED, job(second.job.id).state)
        // The broken cycle leaves the job waiting for its backoff, not RUNNING under a lease.
        assertEquals(JobState.RETRY_WAIT, job(first.job.id).state)
        assertTrue(scheduled.isNotEmpty())

        // The recording works again: the retried job completes at the next wakeup.
        now += RETRY_BACKOFF
        assertEquals(1, runBlocking { dispatcher.drain() })
        assertEquals(JobState.SUCCEEDED, job(first.job.id).state)
    }

    @Test
    fun aBrokenHandlerStopsItsJobButNotTheDrain() {
        val first = enqueue(dedupeKey = "https://a.test/") as Enqueued.Queued
        val second = enqueue(dedupeKey = "https://b.test/") as Enqueued.Queued
        val handler = JobHandler { job ->
            if (job.id == first.job.id) error("handler bug") else DispatchResult.Done(remoteId = "kept")
        }
        val dispatcher = OutboxDispatcher(
            outbox = outbox,
            handlers = mapOf(JobKind.KARAKEEP_SAVE to handler),
            onDone = { _, _ -> },
            scheduler = { scheduled += it },
        )

        val sent = runBlocking { dispatcher.drain() }

        // A handler bug fails its own job visibly and the queue keeps moving.
        assertEquals(2, sent)
        assertEquals(JobState.FAILED, job(first.job.id).state)
        assertEquals(JobState.SUCCEEDED, job(second.job.id).state)
    }

    @Test
    fun maxJobsBoundsEvenARoundOfBrokenCycles() {
        val first = enqueue(dedupeKey = "https://a.test/") as Enqueued.Queued
        val second = enqueue(dedupeKey = "https://b.test/") as Enqueued.Queued
        val dispatcher = OutboxDispatcher(
            outbox = outbox,
            handlers = mapOf(JobKind.KARAKEEP_SAVE to JobHandler { DispatchResult.Done(remoteId = "kept") }),
            // Every recording fails: no cycle completes.
            onDone = { _, _ -> error("recording failed") },
            scheduler = { scheduled += it },
        )

        runBlocking { dispatcher.drain(maxJobs = 1) }

        // One job per round at most: broken cycles must not let a drain claim the whole queue.
        assertEquals(JobState.RETRY_WAIT, job(first.job.id).state)
        assertEquals(JobState.PENDING, job(second.job.id).state)
    }

    @Test
    fun anEmptiedQueueCancelsItsWakeup() {
        enqueue()
        val dispatcher = OutboxDispatcher(
            outbox = outbox,
            handlers = mapOf(JobKind.KARAKEEP_SAVE to JobHandler { DispatchResult.Done(remoteId = "kept") }),
            onDone = { _, _ -> },
            scheduler = { scheduled += it },
        )

        val sent = runBlocking { dispatcher.drain() }

        // Nothing pending: the wakeup is cancelled, not re-booked at the fallback time.
        assertEquals(1, sent)
        assertEquals(listOf<Long?>(null), scheduled)
    }

    @Test
    fun aBrokenQueueStillBooksItsNextWakeup() {
        enqueue()
        database.close()
        val dispatcher = OutboxDispatcher(
            outbox = outbox,
            handlers = mapOf(JobKind.KARAKEEP_SAVE to JobHandler { DispatchResult.Done(remoteId = "kept") }),
            onDone = { _, _ -> },
            scheduler = { scheduled += it },
            clock = { now },
        )

        val sent = runBlocking { dispatcher.drain() }

        // Even with the queue unreadable, a wakeup is booked so the work resumes later.
        assertEquals(0, sent)
        assertEquals(listOf<Long?>(now + OutboxDispatcher.WAKEUP_FALLBACK_MS), scheduled)
    }

    @Test
    fun aFailedClaimWaitsBeforeTheNextWakeup() {
        val past = now - 1_000
        // A failed claim leaves the due job's past time as the next wakeup: wait instead.
        assertEquals(now + OutboxDispatcher.WAKEUP_FALLBACK_MS, OutboxDispatcher.nextWakeup(past, claimFailed = true, now = now))
        // A later due time is kept, and nothing pending still cancels the wakeup.
        val later = now + 10 * OutboxDispatcher.WAKEUP_FALLBACK_MS
        assertEquals(later, OutboxDispatcher.nextWakeup(later, claimFailed = true, now = now))
        assertEquals(null, OutboxDispatcher.nextWakeup(null, claimFailed = true, now = now))
        // Without a failed claim the queue's own time stands, even when it is due now.
        assertEquals(past, OutboxDispatcher.nextWakeup(past, claimFailed = false, now = now))
    }

    @Test
    fun expiredCapturesAreNeverClaimed() {
        runBlocking {
            database.integrations().setService(ServiceEntity(ServiceKind.HISTER, "https://hister.test", "fp-hister", 1, true, now))
        }
        val hister = runBlocking { database.integrations().service(ServiceKind.HISTER) }!!
        val queued = runBlocking {
            outbox.enqueue(JobKind.HISTER_ADD, hister, Binding.SAME_DESTINATION, "doc", """{"url":"https://a.test/"}""", supersede = true)
        } as Enqueued.Queued

        // A week later the content must not go out, even at the send point itself.
        now += Outbox.CAPTURE_MAX_AGE_MS + 1

        assertNull(runBlocking { outbox.claim("owner", LEASE_MS) })
        assertEquals(JobState.EXPIRED, job(queued.job.id).state)
    }

    @Test
    fun aWorkerThatDiesRepeatedlyStopsAtTheAttemptLimit() {
        val queued = enqueue() as Enqueued.Queued

        repeat(RetryPolicy().maxAttempts) { claimAndAbandon("owner$it") }

        assertNull(runBlocking { outbox.claim("last", LEASE_MS) })
        assertEquals(JobState.FAILED, job(queued.job.id).state)
    }

    /** The row carries the address its caller recorded, so status lists never decode the payload for it. */
    @Test
    fun enqueueStoresTheUrlTheCallerRecords() {
        val queued = enqueue(url = "https://a.test/") as Enqueued.Queued
        assertEquals("https://a.test/", queued.job.url)
        assertEquals("https://a.test/", job(queued.job.id).url)

        // No caller-recorded address: none is invented from the payload.
        val undecoded = enqueue(payload = """{"url":"https://b.test/"}""", dedupeKey = "https://b.test/", url = null) as Enqueued.Queued
        assertNull(undecoded.job.url)
    }

    @Test
    fun retryingAFailedJobKeepsTheRecordedUrlUnlessTheCallerRecordsAnother() {
        val first = enqueue(url = "https://a.test/") as Enqueued.Queued
        claimAndFinish(DispatchResult.Rejected("bad_request"))

        val again = enqueue(url = "https://a.test/") as Enqueued.Queued
        assertEquals(first.job.id, again.job.id)
        assertEquals("https://a.test/", job(again.job.id).url)

        // A caller that records no address leaves the original in place.
        claimAndFinish(DispatchResult.Rejected("bad_request"))
        val silent = enqueue(url = null) as Enqueued.Queued
        assertEquals("https://a.test/", job(silent.job.id).url)
    }

    @Test
    fun reSavingAFailedJobSendsTheFreshPayload() {
        val first = enqueue(payload = """{"url":"https://a.test/","title":"One"}""") as Enqueued.Queued
        claimAndFinish(DispatchResult.Rejected("bad_request"))

        val again = enqueue(payload = """{"url":"https://a.test/","title":"Two"}""") as Enqueued.Queued

        // The same job is retried (no duplicate row), with the payload the user just saved.
        assertEquals(first.job.id, again.job.id)
        assertTrue(job(again.job.id).payload.contains("Two"))
    }

    @Test
    fun resumingPausedJobsStartsTheirAttemptsFresh() {
        val queued = enqueue() as Enqueued.Queued
        claimAndFinish(DispatchResult.AuthRequired("http_401"))
        assertEquals(1, job(queued.job.id).attempts)

        runBlocking { outbox.resume(ServiceKind.KARAKEEP) }

        val resumed = job(queued.job.id)
        assertEquals(JobState.PENDING, resumed.state)
        assertEquals(0, resumed.attempts)
    }

    private fun reportsService(fingerprint: String = "fp-reports") = runBlocking {
        val dao = database.integrations()
        dao.setService(ServiceEntity(ServiceKind.SITE_REPORTS, "https://reports.test", fingerprint, 1, false, now))
        dao.service(ServiceKind.SITE_REPORTS)!!
    }

    private fun histerService() = runBlocking {
        val dao = database.integrations()
        dao.setService(ServiceEntity(ServiceKind.HISTER, "https://hister.test", "fp-hister", 1, true, now))
        dao.service(ServiceKind.HISTER)!!
    }

    private fun enqueueReport(id: String, url: String? = null, service: ServiceEntity = reportsService()) = runBlocking {
        outbox.enqueue(JobKind.SITE_REPORT, service, Binding.CURRENT, id, """{"reportId":"$id"}""", supersede = false, url = url)
    } as Enqueued.Queued

    private fun enqueueCapture(key: String = "https://c.test/", url: String? = key) = runBlocking {
        outbox.enqueue(JobKind.HISTER_ADD, histerService(), Binding.SAME_DESTINATION, key, """{"url":"$key","text":"page text"}""", supersede = true, url = url)
    } as Enqueued.Queued

    @Test
    fun aReportIsParkedOnADestinationChangeAndCancelledOnRemoval() {
        val previous = reportsService()
        val parked = enqueueReport("report-a")
        val elsewhere = previous.copy(baseUrl = "https://other.test", fingerprint = "fp-other", generation = 2)

        runBlocking { database.withTransaction { outbox.onServiceChanged(previous, elsewhere) } }

        // A deliberate report waits for the user, with its old destination, like a save.
        assertEquals(JobState.DESTINATION_CHANGED, job(parked.job.id).state)
        assertEquals("fp-reports", job(parked.job.id).fingerprint)

        runBlocking { database.withTransaction { outbox.onServiceChanged(elsewhere, null) } }
        assertEquals(JobState.CANCELLED, job(parked.job.id).state)
    }

    @Test
    fun retryingAReportStartsItsWeekOverButACaptureKeepsItsAge() {
        val report = enqueueReport("report-a")
        claimAndFinish(DispatchResult.Rejected("http_400"))
        val capture = enqueueCapture()
        claimAndFinish(DispatchResult.Rejected("http_400"))
        val created = now
        now += 8 * DAY_MS

        runBlocking {
            outbox.retryNow(report.job.id)
            outbox.retryNow(capture.job.id)
        }

        assertEquals(JobState.PENDING, job(report.job.id).state)
        assertEquals(now, job(report.job.id).createdAt)
        // A capture's age bounds how long its text is kept: a retry does not extend it.
        assertEquals(created, job(capture.job.id).createdAt)
    }

    @Test
    fun finishedReportsAndCapturesDropTheirContentAndUrlButSavesKeepTheirs() {
        val save = enqueue(url = "https://a.test/") as Enqueued.Queued
        val capture = enqueueCapture(url = "https://c.test/")
        val report = enqueueReport("report-a", url = "https://r.test/")

        repeat(3) { claimAndFinish(DispatchResult.Done(remoteId = null)) }

        assertEquals(listOf(JobState.SUCCEEDED), listOf(save, capture, report).map { job(it.job.id).state }.distinct())
        assertEquals("", job(report.job.id).payload)
        assertEquals(0, job(report.job.id).payloadBytes)
        assertNull(job(report.job.id).url)
        assertEquals("", job(capture.job.id).payload)
        assertNull(job(capture.job.id).url)
        assertNotEquals("", job(save.job.id).payload)
        assertEquals("https://a.test/", job(save.job.id).url)
    }

    @Test
    fun failedAndWaitingJobsKeepTheirUrlForTheirRetry() {
        val save = enqueue(url = "https://a.test/") as Enqueued.Queued
        claimAndFinish(DispatchResult.Rejected("http_400"))
        assertEquals(JobState.FAILED, job(save.job.id).state)
        assertEquals("https://a.test/", job(save.job.id).url)

        val waiting = enqueue(dedupeKey = "https://w.test/", url = "https://w.test/") as Enqueued.Queued
        claimAndFinish(DispatchResult.Retry("network", delivery = Delivery.NOT_SENT))
        assertEquals(JobState.RETRY_WAIT, job(waiting.job.id).state)
        assertEquals("https://w.test/", job(waiting.job.id).url)

        val paused = enqueue(dedupeKey = "https://p.test/", url = "https://p.test/") as Enqueued.Queued
        claimAndFinish(DispatchResult.AuthRequired("http_401"))
        assertEquals(JobState.AUTH_REQUIRED, job(paused.job.id).state)
        assertEquals("https://p.test/", job(paused.job.id).url)
    }

    @Test
    fun expiredCapturesDropTheirContentAndUrl() {
        val capture = enqueueCapture(url = "https://c.test/")

        now += Outbox.CAPTURE_MAX_AGE_MS + 1
        assertNull(runBlocking { outbox.claim("owner", LEASE_MS) })

        assertEquals(JobState.EXPIRED, job(capture.job.id).state)
        assertEquals("", job(capture.job.id).payload)
        assertNull(job(capture.job.id).url)
    }

    @Test
    fun supersededCapturesDropTheirContentAndUrl() {
        val first = enqueueCapture(key = "https://c.test/", url = "https://c.test/")
        val second = enqueueCapture(key = "https://c.test/", url = "https://c.test/")

        assertEquals(JobState.SUPERSEDED, job(first.job.id).state)
        assertEquals("", job(first.job.id).payload)
        assertNull(job(first.job.id).url)
        assertEquals(JobState.PENDING, job(second.job.id).state)
        assertEquals("https://c.test/", job(second.job.id).url)
    }

    @Test
    fun discardedOrReplacedParkedReportsDropTheirContentAtOnce() {
        val previous = reportsService()
        val replaced = enqueueReport("report-replaced", url = "https://r.test/", service = previous)
        val current = reportsService("fp-new")
        runBlocking {
            database.withTransaction { outbox.onServiceChanged(previous, current) }
            // The same report was queued for the new address meanwhile: the parked copy is replaced.
            database.integrations().insertJob(job(replaced.job.id).copy(id = "again", fingerprint = "fp-new", state = JobState.PENDING))
            outbox.sendParkedToCurrent(ServiceKind.SITE_REPORTS)
        }
        assertEquals(JobState.SUPERSEDED, job(replaced.job.id).state)
        assertEquals("", job(replaced.job.id).payload)
        assertNull(job(replaced.job.id).url)

        val discarded = enqueueReport("report-discarded", url = "https://d.test/", service = current)
        val next = reportsService("fp-next")
        runBlocking {
            database.withTransaction { outbox.onServiceChanged(current, next) }
            outbox.discardParked(ServiceKind.SITE_REPORTS)
        }
        assertEquals(JobState.CANCELLED, job(discarded.job.id).state)
        assertEquals("", job(discarded.job.id).payload)
        assertNull(job(discarded.job.id).url)
    }

    @Test
    fun unfinishedReportsHoldTheirFilesAndCountTowardTheCap() {
        val dao = database.integrations()
        val service = reportsService()
        // One report in every state, plus a save and a capture that are not reports.
        for (state in JobState.entries) {
            val queued = enqueueReport("report-${state.name}", service = service)
            runBlocking { dao.updateJob(queued.job.copy(state = state)) }
        }
        enqueue()
        enqueueCapture()

        val unfinished = setOf(
            JobState.PENDING, JobState.RUNNING, JobState.RETRY_WAIT, JobState.AUTH_REQUIRED,
            JobState.FAILED, JobState.DESTINATION_CHANGED,
        )
        assertEquals(unfinished.map { "report-${it.name}" }.toSet(), runBlocking { dao.reportIdsHoldingFiles() }.toSet())
        assertEquals(unfinished.size, runBlocking { dao.unsentReports() })
    }

    @Test
    fun housekeepingRunsAroundEveryDrainAndItsFailureStopsNothing() {
        val queued = enqueue() as Enqueued.Queued
        var runs = 0
        val dispatcher = OutboxDispatcher(
            outbox = outbox,
            handlers = mapOf(JobKind.KARAKEEP_SAVE to JobHandler { DispatchResult.Done(remoteId = "kept") }),
            onDone = { _, _ -> },
            scheduler = { scheduled += it },
            housekeeping = { if (runs++ == 0) error("sweep failed") },
        )

        val sent = runBlocking { dispatcher.drain() }

        // Before the first claim and after the last result; the first failure is only logged.
        assertEquals(2, runs)
        assertEquals(1, sent)
        assertEquals(JobState.SUCCEEDED, job(queued.job.id).state)
    }

    private companion object {
        const val LEASE_MS = 60_000L
        const val RETRY_BACKOFF = 120_000L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
