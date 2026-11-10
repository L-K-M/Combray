package ch.lkmc.combray.core.browser

/**
 * Bounds for the strings a page chooses and the database keeps.
 *
 * A page sets its title and its address, at any length. Android reads a row through a cursor
 * window of about 2 MB, so a row with a longer string is written without an error and can never
 * be read again: Recent, session restore and every later update of that tab would fail for good.
 * These bounds are far below that limit, so a list of such rows also stays small in memory.
 */
internal object StoredText {
    /** As Hister and a site report keep a title. */
    const val MAX_TITLE_CHARS = 4 * 1024

    /** The longest link the app takes from another app or from a page (native MAX_URL_CHARS). */
    const val MAX_ADDRESS_CHARS = 32_768

    /** [title] as it is stored: cut to [MAX_TITLE_CHARS], never through a surrogate pair. */
    fun title(title: String): String {
        if (title.length <= MAX_TITLE_CHARS) return title
        val end = if (title[MAX_TITLE_CHARS - 1].isHighSurrogate()) MAX_TITLE_CHARS - 1 else MAX_TITLE_CHARS
        return title.substring(0, end)
    }

    /** An address is stored whole or not at all: a cut address names another page. */
    fun keeps(address: String): Boolean = address.length <= MAX_ADDRESS_CHARS
}
