package ch.lkmc.combray

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
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
 */
class UploadStaging(private val root: File, private val resolver: ContentResolver) {
    fun clear() {
        root.deleteRecursively()
    }

    /** Paths of the copies, or null if any file could not be read (nothing is uploaded then). */
    suspend fun stage(uris: List<Uri>): List<String>? = withContext(Dispatchers.IO) {
        val pick = File(root, UUID.randomUUID().toString())
        try {
            // One directory per file: two picked files may share a name.
            uris.mapIndexed { index, uri -> copy(uri, File(pick, index.toString())) }
        } catch (failure: Exception) {
            // Another app's provider may throw anything (IO, permission, bad URI): cancel the upload.
            // No suspension point in the block, so cancellation cannot be swallowed here.
            Log.w(LOG_TAG, "could not copy a picked file", failure)
            pick.deleteRecursively()
            null
        }
    }

    private fun copy(uri: Uri, directory: File): String {
        if (!directory.mkdirs()) throw IOException("cannot create upload directory")
        val target = File(directory, safeName(displayName(uri)))
        val input = resolver.openInputStream(uri) ?: throw IOException("no content")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        return target.path
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    companion object {
        private const val LOG_TAG = "CombrayUpload"
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
