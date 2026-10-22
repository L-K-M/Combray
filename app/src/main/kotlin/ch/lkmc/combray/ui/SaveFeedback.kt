package ch.lkmc.combray.ui

import ch.lkmc.combray.core.integration.SaveStatus

/**
 * The Save button's [status], tagged with the address it was read for. After a tab switch or a
 * navigation the bar briefly keeps the previous page's status; the tag tells the two apart.
 */
data class PageSaveStatus(val url: String?, val status: SaveStatus)

/** What a save that finished on the shown page should feel like. */
internal enum class SaveFeedback { CONFIRM, REJECT }

/**
 * Feedback when [current] follows [previous]: only for a save of the same address that was
 * in flight (pending, or waiting for Karakeep to be reachable) and ended, so opening or switching
 * to an already-saved page stays silent.
 */
internal fun saveFeedback(previous: PageSaveStatus, current: PageSaveStatus): SaveFeedback? {
    if (previous.url != current.url || previous.status !in IN_FLIGHT) return null

    return when (current.status) {
        SaveStatus.Saved -> SaveFeedback.CONFIRM
        is SaveStatus.NeedsAttention -> SaveFeedback.REJECT
        SaveStatus.NotConfigured, SaveStatus.Unsaved, SaveStatus.Pending, SaveStatus.Waiting -> null
    }
}

private val IN_FLIGHT = setOf(SaveStatus.Pending, SaveStatus.Waiting)
