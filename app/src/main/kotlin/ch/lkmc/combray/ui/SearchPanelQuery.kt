package ch.lkmc.combray.ui

import ch.lkmc.combray.core.data.ServiceEntity

/**
 * Whether a search panel's [query] may leave the device. A query seeded from Recent's local
 * filter is not the user's own text: it is sent only after the user types in the panel or
 * submits it. A blank query carries no text and may load a default list.
 */
internal fun shouldSendQuery(query: String, typed: Boolean): Boolean = query.isBlank() || typed

/**
 * One page request a panel makes: the [query] and [cursor] it is bound to, the [generation] of
 * the search that produced the page, and the [service] it was read from. A page request started
 * by an older generation must not append rows or release a newer request's gate, and a request
 * must not reach a service configured after the page was read.
 */
internal data class SearchPage(
    val query: String,
    val cursor: String?,
    val generation: Int,
    val service: ServiceEntity?,
)
