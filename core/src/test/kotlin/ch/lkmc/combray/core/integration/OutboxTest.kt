package ch.lkmc.combray.core.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.JobKind
import ch.lkmc.combray.core.data.JobState
import ch.lkmc.combray.core.data.ServiceEntity
import ch.lkmc.combray.core.data.ServiceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun enqueue(payload: String = """{"url":"https://a.test/","title":"One"}""", dedupeKey: String = "https://a.test/") =
        runBlocking {
            outbox.enqueue(JobKind.KARAKEEP_SAVE, service, Binding.CURRENT, dedupeKey, payload, supersede = false)
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

    private companion object {
        const val LEASE_MS = 60_000L
        const val RETRY_BACKOFF = 120_000L
    }
}
