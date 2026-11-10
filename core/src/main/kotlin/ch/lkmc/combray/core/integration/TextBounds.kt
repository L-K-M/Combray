package ch.lkmc.combray.core.integration

/**
 * At most [maxChars] UTF-16 units of this text, never ending inside a surrogate pair: half a
 * character reaches a server as "?" and a screen as a replacement mark.
 */
internal fun String.takeWholeChars(maxChars: Int): String {
    if (length <= maxChars) return this
    val end = if (maxChars > 0 && this[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
    return substring(0, end)
}
