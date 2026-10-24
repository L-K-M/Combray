package ch.lkmc.combray.core.browser

import androidx.room.withTransaction
import ch.lkmc.combray.core.data.CombrayDatabase
import ch.lkmc.combray.core.data.HomeHiddenSiteEntity
import ch.lkmc.combray.core.data.HomePinEntity
import ch.lkmc.combray.core.integration.SensitiveUrls
import ch.lkmc.combray.core.policy.AddressDecision
import ch.lkmc.combray.core.policy.AddressPolicy
import ch.lkmc.combray.core.policy.HostNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import java.util.UUID

/** The new-tab page's quick links: pins and automatic tiles together at most this many. */
const val HOME_LINK_LIMIT = 6

/** Where a pin moves in the user's order. */
enum class PinDirection { EARLIER, LATER }

enum class HomePinResult {
    SAVED,

    /** Not an HTTP(S) address the address policy would open; text is never turned into a search. */
    INVALID_ADDRESS,

    /** The address looks like it carries a credential ([SensitiveUrls]); it is not pinned. */
    SENSITIVE_ADDRESS,

    /** [HOME_LINK_LIMIT] pins already; the caller asks which one to replace and retries. */
    FULL,

    /** [HomeLinks.savePin]'s `id` or `replaceId` named a pin that does not exist. */
    NOT_FOUND,
}

/** One tile on the new-tab page: a user's pin, or a site ranking fills with. */
data class HomeLink(
    /** The pin's id, or the site key for an automatic link. */
    val id: String,
    /** The www-merged site key; both spellings of a site share one automatic link. */
    val site: String,
    /** What a tap opens: the pin's exact stored address, or the site's newest page. */
    val url: String,
    /** The pin's label; automatic links have none and name the site. */
    val title: String?,
    /** Set when the link is a pin, so Unpin/Edit reach its row. */
    val pinId: String?,
)

/** A pinned quick link: the user's own order, surviving the history-retention window. */
data class HomePin(
    val id: String,
    val url: String,
    val title: String,
    val position: Int,
)

data class HomeLinksState(
    /** Every pin in the user's order (at most [HOME_LINK_LIMIT] exist). */
    val pins: List<HomePin>,
    /** Sites suppressed from automatic links until restored, ordered by name. */
    val hiddenSites: List<String>,
    /** What the tiles show: pins in their order first, automatic sites filling to the limit. */
    val links: List<HomeLink>,
)

/**
 * Owns the new-tab page's durable quick links: ordered pins and hidden site keys in Room.
 * Automatic links stay a projection of the frequent-site ranking ([BrowserCoordinator.topSites]);
 * a pin suppresses the automatic link of its site, a hidden site suppresses only its automatic
 * link, and forgetting a site removes its pins ([BrowserCoordinator.forgetSite]).
 */
class HomeLinks(
    private val database: CombrayDatabase,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.homeLinks()

    /**
     * The tiles and the pin/hidden-site lists behind them. [frequent] is the unbounded ranking
     * (top sites read with `limit = Int.MAX_VALUE`): pins come first in the user's order, then
     * sites neither pinned nor hidden fill the rest, capped at [HOME_LINK_LIMIT].
     */
    fun state(frequent: Flow<List<TopSite>>): Flow<HomeLinksState> =
        combine(dao.observePins(), dao.observeHiddenSites(), frequent) { pins, hidden, sites ->
            val hiddenSites = hidden.map { it.site }
            val suppressed = pins.mapTo(HashSet()) { it.site } + hiddenSites
            val links = pins.map { it.toLink() } +
                sites.filter { it.site !in suppressed }
                    .map { HomeLink(id = it.site, site = it.site, url = it.url, title = null, pinId = null) }
            HomeLinksState(
                pins = pins.map { HomePin(it.id, it.url, it.title, it.position) },
                hiddenSites = hiddenSites,
                links = links.take(HOME_LINK_LIMIT),
            )
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /**
     * Pins [url] under [title]: a new pin when [id] is null, otherwise that pin is edited.
     * The text must be an HTTP(S) address the address policy would navigate to — never a
     * search — and not a credential-bearing one. An existing pin of the exact same address is
     * updated in place (its slot and id kept); a different pin's address is refused for an
     * edit. With the pins full, [replaceId] names the pin the new one replaces, keeping its
     * slot; naming an already pinned address instead moves the existing pin into that slot.
     */
    suspend fun savePin(id: String?, url: String, title: String, replaceId: String? = null): HomePinResult =
        database.withTransaction {
            val navigate = AddressPolicy.interpret(url).primary as? AddressDecision.Navigate
                ?: return@withTransaction HomePinResult.INVALID_ADDRESS
            if (SensitiveUrls.looksSensitive(navigate.url)) return@withTransaction HomePinResult.SENSITIVE_ADDRESS

            val editing = id?.let { dao.pin(it) ?: return@withTransaction HomePinResult.NOT_FOUND }
            val sameUrl = dao.pinForUrl(navigate.url)
            val site = siteKeyOf(navigate.url)
            val label = title.trim().ifEmpty { HostNames.of(navigate.url)?.let(HostNames::forDisplay) ?: navigate.url }

            if (sameUrl != null && sameUrl.id != editing?.id) {
                if (editing != null) return@withTransaction HomePinResult.INVALID_ADDRESS
                val replaced = replaceId?.let { dao.pin(it) ?: return@withTransaction HomePinResult.NOT_FOUND }
                if (replaced == null || replaced.id == sameUrl.id) {
                    // Pinning an already pinned page relabels it; no second tile, no slot spent.
                    dao.updatePinFields(sameUrl.id, sameUrl.url, site, label)
                    return@withTransaction HomePinResult.SAVED
                }
                // An explicit different replacement merges the two pins: the chosen one goes
                // and the existing pin keeps its id but moves into the freed relative slot.
                dao.deletePin(replaced.id)
                dao.closePositionGap(replaced.position)
                val others = dao.pinsInOrder().filterTo(mutableListOf()) { it.id != sameUrl.id }
                others.add(others.count { it.position < replaced.position }, sameUrl)
                others.forEachIndexed { index, pin ->
                    if (pin.position != index) dao.movePinTo(pin.id, index)
                }
                dao.updatePinFields(sameUrl.id, sameUrl.url, site, label)
                return@withTransaction HomePinResult.SAVED
            }

            val replaced = replaceId?.let { dao.pin(it) ?: return@withTransaction HomePinResult.NOT_FOUND }
            if (editing == null && replaced == null && dao.pinCount() >= HOME_LINK_LIMIT) {
                return@withTransaction HomePinResult.FULL
            }
            if (replaced != null && replaced.id != editing?.id) {
                dao.deletePin(replaced.id)
                if (editing == null) {
                    // The new pin takes the freed slot; an edit only leaves a gap to close.
                    dao.insertPin(HomePinEntity(newId(), navigate.url, site, label, replaced.position))
                    return@withTransaction HomePinResult.SAVED
                }
                dao.closePositionGap(replaced.position)
            }
            if (editing != null) {
                dao.updatePinFields(editing.id, navigate.url, site, label)
            } else {
                dao.insertPin(HomePinEntity(newId(), navigate.url, site, label, dao.pinCount()))
            }
            HomePinResult.SAVED
        }

    suspend fun unpin(id: String) {
        database.withTransaction {
            val pin = dao.pin(id) ?: return@withTransaction
            dao.deletePin(id)
            dao.closePositionGap(pin.position)
        }
    }

    /** Moves a pin one slot, swapping with its neighbour; at either end nothing changes. */
    suspend fun movePin(id: String, direction: PinDirection) {
        database.withTransaction {
            val pin = dao.pin(id) ?: return@withTransaction
            val target = when (direction) {
                PinDirection.EARLIER -> pin.position - 1
                PinDirection.LATER -> pin.position + 1
            }
            val other = dao.pinAt(target) ?: return@withTransaction
            dao.movePinTo(other.id, pin.position)
            dao.movePinTo(pin.id, target)
        }
    }

    /**
     * Suppresses [site]'s automatic link until [restoreSite]; pins of the site are deliberate
     * and stay. Accepts either spelling ("www." is the same site), like a tile offers it.
     */
    suspend fun hideSite(site: String) {
        val key = HostNames.normalize(site)?.let(HostNames::mergedSite) ?: return
        dao.hideSite(HomeHiddenSiteEntity(key))
    }

    suspend fun restoreSite(site: String) {
        val key = HostNames.normalize(site)?.let(HostNames::mergedSite) ?: return
        dao.restoreSite(key)
    }

    /**
     * The key a pin's site is stored and matched under. A hostless address (which the policy
     * hardly produces) keys to itself, so it can never half-match a site name.
     */
    private fun siteKeyOf(url: String): String =
        HostNames.of(url)?.let(HostNames::mergedSite) ?: url

    private fun HomePinEntity.toLink() = HomeLink(id = id, site = site, url = url, title = title, pinId = id)
}
