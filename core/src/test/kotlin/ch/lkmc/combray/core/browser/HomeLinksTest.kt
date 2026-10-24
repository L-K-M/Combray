package ch.lkmc.combray.core.browser

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.combray.core.data.CombrayDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The new-tab page's durable pins and hidden sites against a real Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeLinksTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: CombrayDatabase
    private lateinit var links: HomeLinks
    private var ids = 0

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CombrayDatabase::class.java).build()
        links = HomeLinks(database, newId = { "pin${ids++}" })
    }

    @After
    fun tearDown() = database.close()

    /** The frequent-site ranking the app feeds in, unbounded as agreed. */
    private fun frequent(vararg sites: String) = flowOf(
        sites.map { TopSite(it, "https://$it/newest", visits = 1) },
    )

    private fun state(vararg sites: String) = runBlocking { links.state(frequent(*sites)).first() }

    private fun save(id: String? = null, url: String, title: String = "T", replaceId: String? = null) =
        runBlocking { links.savePin(id, url, title, replaceId) }

    private fun pin(url: String, title: String = "T"): HomePin {
        assertEquals(HomePinResult.SAVED, save(url = url, title = title))
        return runBlocking { database.homeLinks().observePins().first() }.last()
            .let { HomePin(it.id, it.url, it.title, it.position) }
    }

    @Test
    fun pinsComeFirstInTheirOrderThenFrequentSitesFillToTheLimit() {
        pin("https://b.test/2", "B")
        pin("https://a.test/1", "A")

        val state = state("s1.test", "s2.test", "s3.test", "s4.test", "s5.test", "s6.test")

        assertEquals(
            listOf("https://b.test/2", "https://a.test/1", "https://s1.test/newest", "https://s2.test/newest",
                "https://s3.test/newest", "https://s4.test/newest"),
            state.links.map { it.url },
        )
        // The tiles name pins by their label and automatic links by their site.
        assertEquals(listOf("B", "A", null, null, null, null), state.links.map { it.title })
        assertEquals(listOf("pin0", "pin1"), state.links.take(2).map { it.pinId })
        assertEquals(HOME_LINK_LIMIT, state.links.size)
    }

    @Test
    fun aPinSuppressesTheAutomaticLinkOfItsSiteInEitherSpelling() {
        pin("https://www.example.com/pinned", "Pinned")

        val state = state("example.com", "other.test")

        // example.com merges with www.example.com: one site, one tile, and the pin wins.
        assertEquals(listOf("https://www.example.com/pinned", "https://other.test/newest"), state.links.map { it.url })
        assertEquals(listOf("example.com", "other.test"), state.links.map { it.site })
    }

    @Test
    fun severalPagesOfOneSiteMayBePinned() {
        pin("https://example.com/one", "One")
        pin("https://example.com/two", "Two")

        assertEquals(2, state().pins.size)
        assertEquals(listOf("https://example.com/one", "https://example.com/two"), state().links.map { it.url })
    }

    @Test
    fun hidingASiteSuppressesOnlyItsAutomaticLinkUntilRestored() {
        pin("https://example.com/pinned", "Pinned")
        runBlocking { links.hideSite("example.com") }

        var state = state("example.com", "other.test", "gone.test")
        assertEquals(listOf("example.com"), state.hiddenSites)
        // The pin is deliberate and stays; only automatic tiles hide.
        assertEquals(
            listOf("https://example.com/pinned", "https://other.test/newest", "https://gone.test/newest"),
            state.links.map { it.url },
        )

        runBlocking {
            // Either spelling names the merged site.
            links.hideSite("www.gone.test")
            links.restoreSite("example.com")
        }
        state = state("example.com", "other.test", "gone.test")
        assertEquals(listOf("gone.test"), state.hiddenSites)
        assertEquals(
            listOf("https://example.com/pinned", "https://other.test/newest"),
            state.links.map { it.url },
        )

        // Restoring an unpinned site brings its automatic tile back.
        runBlocking { links.restoreSite("gone.test") }
        state = state("example.com", "other.test", "gone.test")
        assertTrue(state.hiddenSites.isEmpty())
        assertEquals(
            listOf("https://example.com/pinned", "https://other.test/newest", "https://gone.test/newest"),
            state.links.map { it.url },
        )
    }

    @Test
    fun hidingAnAutomaticLinkDoesNotSpendAPinSlot() {
        repeat(HOME_LINK_LIMIT) { pin("https://p$it.test/", "P$it") }
        runBlocking { links.hideSite("busy.test") }

        val state = state("busy.test", "extra.test")

        // Six pins fill the page; nothing automatic shows, hidden or not.
        assertEquals(HOME_LINK_LIMIT, state.links.size)
        assertTrue(state.links.all { it.pinId != null })
    }

    @Test
    fun anAddressIsPinnedAsThePolicyWouldNavigateToIt() {
        assertEquals(HomePinResult.SAVED, save(url = "example.com/page?q=1", title = "  "))

        val saved = state().pins.single()
        // "example.com/page?q=1" navigates as HTTPS; a blank label falls back to the site.
        assertEquals("https://example.com/page?q=1", saved.url)
        assertEquals("example.com", saved.title)
    }

    @Test
    fun textThatWouldSearchIsNotAnAddress() {
        // One word reads as a search, not a site; a pin never becomes a Kagi query.
        assertEquals(HomePinResult.INVALID_ADDRESS, save(url = "router"))
        assertEquals(HomePinResult.INVALID_ADDRESS, save(url = "how to cook rice"))
        assertEquals(HomePinResult.INVALID_ADDRESS, save(url = "ftp://files.test/"))
        assertEquals(HomePinResult.INVALID_ADDRESS, save(url = "javascript:alert(1)"))
        assertEquals(0, state().pins.size)
    }

    @Test
    fun aCredentialBearingAddressIsNotPinned() {
        assertEquals(
            HomePinResult.SENSITIVE_ADDRESS,
            save(url = "https://login.test/callback?access_token=abc"),
        )
        assertEquals(0, state().pins.size)
    }

    @Test
    fun pinningTheSameAddressAgainRelabelsTheSamePin() {
        val first = pin("https://example.com/", "Old")

        assertEquals(HomePinResult.SAVED, save(url = "https://example.com/", title = "New"))

        val pins = state().pins
        assertEquals(1, pins.size)
        assertEquals(first.id, pins.single().id)
        assertEquals("New", pins.single().title)
        assertEquals(first.position, pins.single().position)
    }

    @Test
    fun theSeventhPinNeedsAnExistingSlotToTake() {
        val six = (0 until HOME_LINK_LIMIT).map { pin("https://p$it.test/", "P$it") }

        assertEquals(HomePinResult.FULL, save(url = "https://extra.test/"))
        // A replacement that does not exist cannot be taken.
        assertEquals(HomePinResult.NOT_FOUND, save(url = "https://extra.test/", replaceId = "pin-missing"))
        // A real slot: the replacement keeps its place in the order.
        assertEquals(HomePinResult.SAVED, save(url = "https://extra.test/", title = "New", replaceId = six[2].id))

        val pins = state().pins
        assertEquals(HOME_LINK_LIMIT, pins.size)
        assertEquals("https://extra.test/", pins[2].url)
        assertEquals(2, pins[2].position)
        assertTrue(pins.none { it.url == "https://p2.test/" })
    }

    @Test
    fun editingAPinChangesItInPlaceButCannotTakeAnothersAddress() {
        pin("https://a.test/", "A")
        val b = pin("https://b.test/", "B")

        assertEquals(HomePinResult.NOT_FOUND, save(id = "pin-missing", url = "https://c.test/"))
        // Editing b.test's pin onto a.test's address would duplicate it: refused, both stay.
        assertEquals(HomePinResult.INVALID_ADDRESS, save(id = b.id, url = "https://a.test/"))
        assertEquals(HomePinResult.SAVED, save(id = b.id, url = "https://b.test/moved", title = "Moved"))

        val pins = state().pins
        assertEquals(listOf("https://a.test/", "https://b.test/moved"), pins.map { it.url })
        assertEquals(listOf("A", "Moved"), pins.map { it.title })
        assertEquals(listOf(0, 1), pins.map { it.position })
    }

    @Test
    fun unpinAndReorderKeepPositionsGapFree() {
        pin("https://a.test/", "A")
        val b = pin("https://b.test/", "B")
        val c = pin("https://c.test/", "C")

        runBlocking {
            links.movePin(c.id, PinDirection.EARLIER)
            links.movePin(c.id, PinDirection.EARLIER)
        }
        assertEquals(listOf("https://c.test/", "https://a.test/", "https://b.test/"), state().pins.map { it.url })

        runBlocking {
            links.movePin(c.id, PinDirection.EARLIER) // already first: no change
            links.unpin(b.id)
        }
        val pins = state().pins
        assertEquals(listOf("https://c.test/", "https://a.test/"), pins.map { it.url })
        assertEquals(listOf(0, 1), pins.map { it.position })

        // Removing a missing pin and moving at the edge are quiet no-ops.
        runBlocking {
            links.unpin("pin-missing")
            links.movePin("pin-missing", PinDirection.LATER)
            links.movePin(c.id, PinDirection.EARLIER)
            links.movePin(c.id, PinDirection.LATER)
        }
        assertEquals(listOf("https://a.test/", "https://c.test/"), state().pins.map { it.url })
        assertEquals(listOf(0, 1), state().pins.map { it.position })
    }

    @Test
    fun pinsAndHiddenSitesSurviveANewServiceInstance() {
        pin("https://keep.test/", "Keep")
        runBlocking { links.hideSite("gone.test") }

        val reopened = HomeLinks(database)
        val state = runBlocking { reopened.state(frequent("gone.test")).first() }

        assertEquals(listOf("https://keep.test/"), state.pins.map { it.url })
        assertEquals(listOf("gone.test"), state.hiddenSites)
        assertNull(state.links.firstOrNull { it.site == "gone.test" })
    }
}
