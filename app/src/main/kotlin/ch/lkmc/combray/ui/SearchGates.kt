package ch.lkmc.combray.ui

/**
 * Whether a server scope's field should run a request.
 *
 * A blank field shows the panel's own default when [blankShowsAll] (the newest Karakeep
 * bookmarks; Hister has no default and shows nothing). A non-blank field seeded from the local
 * Recent filter is not a request until the user types in the panel or submits it, so switching
 * scopes never sends the local filter to a server on its own.
 */
internal fun shouldRunSearch(query: String, requested: Boolean, blankShowsAll: Boolean): Boolean =
    if (query.isBlank()) blankShowsAll else requested
