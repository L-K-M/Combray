package ch.lkmc.combray.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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

    /** Legacy rows with equal stamps keep a stable id order, not rowid order. */
    @Test
    fun openTabsBreaksStampTiesById() = runBlocking {
        val dao = database.browser()
        dao.insertTab(tab("tab-b", TabState.OPEN).copy(lastFocusedAt = 100))
        dao.insertTab(tab("tab-a", TabState.OPEN).copy(lastFocusedAt = 100))
        dao.insertTab(tab("tab-c", TabState.OPEN).copy(lastFocusedAt = 200))
        assertEquals(listOf("tab-c", "tab-a", "tab-b"), dao.openTabs().map { it.id })
    }

    /** The observed recency queries break ties the same way, for Recent and Continue. */
    @Test
    fun observedOpenTabsBreakStampTiesById() = runBlocking {
        val dao = database.browser()
        dao.insertTab(tab("tab-b", TabState.OPEN).copy(lastFocusedAt = 100))
        dao.insertTab(tab("tab-a", TabState.OPEN).copy(lastFocusedAt = 100))
        assertEquals(listOf("tab-a", "tab-b"), dao.observeOpenTabs().first().map { it.id })
        assertEquals(listOf("tab-a", "tab-b"), dao.observeOpenTabsForContinue().first().map { it.tab.id })
    }

    /** The seed covers closed tabs too, and is null when there are no tabs. */
    @Test
    fun maxTabStampIncludesClosedTabsAndIsNullWhenEmpty() = runBlocking {
        val dao = database.browser()
        assertNull(dao.maxTabStamp())
        dao.insertTab(tab("open", TabState.OPEN).copy(lastFocusedAt = 50))
        dao.insertTab(tab("closed", TabState.CLOSED).copy(lastFocusedAt = 500))
        assertEquals(500L, dao.maxTabStamp())
    }
}
