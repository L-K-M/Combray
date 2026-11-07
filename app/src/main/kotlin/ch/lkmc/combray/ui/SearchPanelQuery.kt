package ch.lkmc.combray.ui

/**
 * Whether a search panel's [query] may leave the device. A query seeded from Recent's local
 * filter is not the user's own text: it is sent only after the user types in the panel or
 * submits it. A blank query carries no text and may load a default list.
 */
internal fun shouldSendQuery(query: String, typed: Boolean): Boolean = query.isBlank() || typed
