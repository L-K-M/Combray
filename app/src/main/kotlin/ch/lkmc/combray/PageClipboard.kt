package ch.lkmc.combray

import android.content.ClipboardManager
import android.content.Context

/** Read only after the user chooses Paste. URI/Intent clips never fetch external content. */
internal fun readPagePaste(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null

    val text = clip.getItemAt(0).text?.toString() ?: return null
    // Shared with native/src/controls.rs: preserve the whole value or refuse it.
    if (text.toByteArray(Charsets.UTF_8).size > MAX_PASTE_BYTES) return null
    return text
}

private const val MAX_PASTE_BYTES = 256 * 1024
