package ch.lkmc.combray.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The joined active-tab query answers as the session-then-tab reads did in every state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserDaoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: CombrayDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun tab(id: String, state: TabState) = TabEntity(
        id = id,
        state = state,
        currentVisitId = null,
        url = "https://a.test/",
        title = null,
        createdAt = 1,
        lastFocusedAt = 1,
        closedAt = null,
    )

    @Test
    fun activeTabIsNullWithoutASession() = runBlocking {
        database.browser().insertTab(tab("open", TabState.OPEN))
        assertNull(database.browser().activeTab())
    }

    @Test
    fun activeTabIsNullWithoutAnActiveTab() = runBlocking {
        database.browser().insertTab(tab("open", TabState.OPEN))
        database.browser().setSession(SessionEntity(activeTabId = null))
        assertNull(database.browser().activeTab())
    }

    @Test
    fun activeTabIsNullWhenTheTabIsMissing() = runBlocking {
        database.browser().setSession(SessionEntity(activeTabId = "missing"))
        assertNull(database.browser().activeTab())
    }

    @Test
    fun activeTabIsNullWhenTheTabIsClosed() = runBlocking {
        database.browser().insertTab(tab("closed", TabState.CLOSED))
        database.browser().setSession(SessionEntity(activeTabId = "closed"))
        assertNull(database.browser().activeTab())
    }

    @Test
    fun activeTabIsTheOpenTab() = runBlocking {
        val open = tab("open", TabState.OPEN)
        database.browser().insertTab(open)
        database.browser().setSession(SessionEntity(activeTabId = "open"))
        assertEquals(open, database.browser().activeTab())
    }
}
