package ch.lkmc.combray

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Copies files the user picked for a page's `<input type=file>` into app storage. Servo reads
 * uploads from filesystem paths when the form is sent; Android hands out content URIs.
 *
 * ```
 * picker ─content://…─▶ stage() ─▶ <root>/<process>/<pick>/<n>/<name> ─path─▶ Servo
 * ```
 *
 * Copies stay until the next process start ([clear]): a page may send its form much later.
 * Every instance in one process shares a process directory, so a cleanup from any of them
 * removes only copies from earlier processes and never a pick this process is still using,
 * including one already returned to Servo.
 */
class UploadStaging(private val root: File, private val resolver: ContentResolver) {
    private val process = processFor(root)

    /** Removes copies from earlier processes, once per process and before any new pick. */
    fun clear() = process.clear()

    /** Paths of the copies, or null if any file could not be read (nothing is uploaded then). */
    suspend fun stage(uris: List<Uri>): List<String>? = withContext(Dispatchers.IO) {
        val pick = process.admitPick()
        try {
            // One directory per file: two picked files may share a name.
            uris.mapIndexed { index, uri -> copy(uri, File(pick, index.toString())) }
        } catch (cancellation: CancellationException) {
            pick.deleteRecursively()
            throw cancellation
        } catch (failure: Exception) {
            // Another app's provider may throw anything (IO, permission, bad URI): cancel the upload.
            // Its message and causes can name the private document: log only the class.
            // No suspension point in the block, so cancellation cannot be swallowed here.
            Log.w(LOG_TAG, "could not copy a picked file: ${failure.javaClass.name}")
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

    /**
     * One process's staging state. [directory] is created lazily with the first pick; until then
     * a cleanup has nothing of this process to keep.
     */
    private class UploadProcess(private val root: File) {
        private val lock = Any()
        private var prepared = false
        val directory = File(root, UUID.randomUUID().toString())

        fun clear() = synchronized(lock) { clean() }

        /** Runs the one-time cleanup, then names a pick directory no cleanup will remove. */
        fun admitPick(): File = synchronized(lock) {
            clean()
            File(directory, UUID.randomUUID().toString())
        }

        private fun clean() {
            if (prepared) return
            prepared = true
            // Everything in the root except this process's directory belongs to an earlier process.
            root.listFiles()?.filter { it != directory }?.forEach { it.deleteRecursively() }
        }
    }

    companion object {
        private const val LOG_TAG = "CombrayUpload"
        private const val MAX_NAME_CHARS = 120
        private const val FALLBACK_NAME = "upload"
        private const val ANY_TYPE = "*/*"

        /**
         * One staging state per root. Instances in this process share it, so a cleanup from any
         * of them sees the same one-time flag and the same protected directory; a new process
         * starts with an empty map and cleans what the previous one left.
         */
        private val processes = ConcurrentHashMap<String, UploadProcess>()

        private fun processFor(root: File): UploadProcess =
            processes.computeIfAbsent(root.absoluteFile.normalize().path) { UploadProcess(root) }

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
