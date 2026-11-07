package ch.lkmc.combray

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Copies files the user picked for a page's `<input type=file>` into app storage. Servo reads
 * uploads from filesystem paths when the form is sent; Android hands out content URIs.
 *
 * ```
 * picker ─content://…─▶ stage() ─▶ <root>/<pick>/<n>/<name> ─path─▶ Servo
 * ```
 *
 * Copies stay until the next process start ([clear]): a page may send its form much later.
 * A pick is bounded by [maxPickBytes]; a larger provider answer fails that pick and rolls back
 * its directory, so one hostile or mistaken pick cannot fill the disk.
 */
class UploadStaging(
    private val root: File,
    private val resolver: ContentResolver,
    private val maxPickBytes: Int = MAX_PICK_BYTES,
) {
    /**
     * Copy and cleanup share one lock: a process-start [clear] cannot delete a directory a
     * [stage] is writing, and a stage cannot leave files a clear is about to miss.
     */
    private val lock = Mutex()

    /** Removes every copy from earlier picks. Serialized with [stage]. */
    suspend fun clear() = lock.withLock { root.deleteRecursively() }

    /** Paths of the copies, or null if any file could not be read (nothing is uploaded then). */
    suspend fun stage(uris: List<Uri>): List<String>? = withContext(Dispatchers.IO) {
        lock.withLock {
            val pick = File(root, UUID.randomUUID().toString())
            try {
                // One directory per file: two picked files may share a name.
                uris.mapIndexed { index, uri -> copy(uri, File(pick, index.toString())) }
            } catch (failure: Exception) {
                // Another app's provider may throw anything (IO, permission, bad URI, an
                // oversized file): cancel the upload. No suspension point in the block, so
                // cancellation cannot be swallowed here.
                Log.w(LOG_TAG, "could not copy a picked file", failure)
                pick.deleteRecursively()
                null
            }
        }
    }

    private fun copy(uri: Uri, directory: File): String {
        if (!directory.mkdirs()) throw IOException("cannot create upload directory")
        val target = File(directory, safeName(displayName(uri)))
        val input = resolver.openInputStream(uri) ?: throw IOException("no content")
        input.use { source ->
            target.outputStream().use { out ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (copied + read > maxPickBytes) throw IOException("picked file is larger than $maxPickBytes bytes")
                    out.write(buffer, 0, read)
                    copied += read
                }
            }
        }
        return target.path
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    companion object {
        private const val LOG_TAG = "CombrayUpload"

        /** Combray's own bound for one pick; a page's file upload should not fill the disk. */
        internal const val MAX_PICK_BYTES = 64 * 1024 * 1024

        private const val COPY_BUFFER_BYTES = 8192

        private const val MAX_NAME_CHARS = 120
        private const val FALLBACK_NAME = "upload"
        private const val ANY_TYPE = "*/*"

        /** The page sees this name (`File.name`); keep it but never let it leave the directory. */
        fun safeName(name: String?): String {
            val cleaned = name.orEmpty()
                .substringAfterLast('/')
                .filter { !it.isISOControl() }
                .trim()
                .take(MAX_NAME_CHARS)
            return cleaned.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: FALLBACK_NAME
        }

        /**
         * Picker MIME types for the page's `accept` extensions. `accept` is only a hint: any
         * extension Android does not know widens the picker to every type.
         */
        fun mimeTypesFor(extensions: List<String>, lookup: (String) -> String? = ::systemMimeType): Array<String> {
            if (extensions.isEmpty()) return arrayOf(ANY_TYPE)
            val types = extensions.map { lookup(it.lowercase()) ?: return arrayOf(ANY_TYPE) }
            return types.distinct().toTypedArray()
        }

        private fun systemMimeType(extension: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }
}
