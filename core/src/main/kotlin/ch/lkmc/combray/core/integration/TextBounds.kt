package ch.lkmc.combray.core.integration

/**
 * At most [maxChars] UTF-16 units of this text, never ending on the first half of a surrogate
 * pair: half a character reaches a server as "?" and a screen as a replacement mark.
 */
internal fun String.takeWholeChars(maxChars: Int): String {
    if (length <= maxChars) return this
    val end = if (maxChars > 0 && this[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
    return substring(0, end)
}

/** At most [maxBytes] of UTF-8, without splitting a character. */
internal fun String.takeUtf8Bytes(maxBytes: Int): String {
    // No character is shorter than a byte, so the answer lies within the first maxBytes units:
    // a megabyte of pasted text is not encoded to find its first kilobytes.
    val head = takeWholeChars(maxBytes)
    val bytes = head.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return head
    var end = maxBytes
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return String(bytes, 0, end, Charsets.UTF_8)
}
