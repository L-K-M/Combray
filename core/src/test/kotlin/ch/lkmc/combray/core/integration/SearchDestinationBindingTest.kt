package ch.lkmc.combray.core.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A page request bound to an older destination must not reach a newly configured service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchDestinationBindingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @get:Rule val reportFolder = TemporaryFolder()
    private lateinit var database: CombrayDatabase
    private lateinit var integrations: Integrations
    private val karakeep = FakeKarakeep()
    private val hister = FakeHister()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        integrations = Integrations(
            database = database,
            secrets = MemorySecrets(),
            transport = UrlConnectionTransport(timeoutMs = 500),
            clock = { 0L },
            newId = { "job" },
            scope = scope,
            scheduler = {},
            reportDirectory = reportFolder.root,
            reportEnvironment = FakeReports.ENVIRONMENT,
        )
        runBlocking {
            integrations.configureKarakeep(karakeep.root, FakeKarakeep.KEY)
            integrations.hister.configure(hister.root, FakeHister.TOKEN)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        karakeep.close()
        hister.close()
    }

    @Test
    fun aKarakeepPageRequestBoundToTheCurrentDestinationReads() {
        val current = runBlocking { integrations.karakeepService.first() }!!
        val result = runBlocking { integrations.karakeepBookmarks("", null, expected = current) }
        assertTrue(result is KarakeepRead.Ok)
    }

    @Test
    fun aKarakeepPageRequestBoundToAnOldDestinationIsRejected() {
        val first = runBlocking { integrations.karakeepService.first() }!!
        runBlocking { integrations.configureKarakeep("http://127.0.0.1:1/other", FakeKarakeep.KEY) }
        assertNull(runBlocking { integrations.karakeepBookmarks("", null, expected = first) })
    }

    @Test
    fun aHisterPageRequestBoundToAnOldDestinationIsRejected() {
        val first = runBlocking { integrations.hister.service.first() }!!
        runBlocking { integrations.hister.configure("http://127.0.0.1:1/other", FakeHister.TOKEN) }
        assertNull(runBlocking { integrations.hister.search("phrase", null, expected = first) })
    }

    private class MemorySecrets : SecretStore {
        private val values = HashMap<SecretName, String>()

        override fun read(name: SecretName) = values[name]

        override fun write(name: SecretName, value: String) {
            values[name] = value
        }

        override fun delete(name: SecretName) {
            values.remove(name)
        }
    }
}
