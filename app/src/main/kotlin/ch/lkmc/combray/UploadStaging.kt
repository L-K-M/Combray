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
import java.io.InputStream
import java.io.OutputStream
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
 *
 * A pick that would leave less than [MIN_FREE_BYTES] free on the staging volume is refused
 * whole instead of copied: filling the volume breaks far more than this upload.
 *
 * @param reserveBytes Free space staging always leaves on the volume of [root].
 * @param usableSpace Reads the volume's free space; injectable so tests can fake a filling disk.
 */
class UploadStaging(
    private val root: File,
    private val resolver: ContentResolver,
    private val reserveBytes: Long = MIN_FREE_BYTES,
    private val usableSpace: (File) -> Long = { it.usableSpace },
) {
    private val process = processFor(root)

    /** What one pick produced. Only [Staged.Files] is uploaded; the other answers say why not. */
    sealed interface Staged {
        data class Files(val paths: List<String>) : Staged

        /** Some file could not be read. */
        data object Unreadable : Staged

        /** Staging it would leave less than the free-space reserve: no file is staged. */
        data object NoSpace : Staged
    }

    /** Removes copies from earlier processes, once per process and before any new pick. */
    fun clear() = process.clear()

    /** The copies' paths, or why the whole pick was refused: a form sent later needs all files. */
    suspend fun stage(uris: List<Uri>): Staged = withContext(Dispatchers.IO) {
        val pick = process.admitPick()
        try {
            // One directory per file: two picked files may share a name.
            Staged.Files(uris.mapIndexed { index, uri -> copy(uri, File(pick, index.toString())) })
        } catch (cancellation: CancellationException) {
            pick.deleteRecursively()
            throw cancellation
        } catch (refused: OutOfStagingSpace) {
            pick.deleteRecursively()
            Staged.NoSpace
        } catch (failure: Exception) {
            // Another app's provider may throw anything (IO, permission, bad URI): cancel the upload.
            // Its message and causes can name the private document: log only the class.
            // No suspension point in the block, so cancellation cannot be swallowed here.
            Log.w(LOG_TAG, "could not copy a picked file: ${failure.javaClass.name}")
            // A failure while the volume is below the reserve reads as a full disk, not as a
            // broken file: the same pick may work after the user frees space. Read the volume
            // before deleting the partial copy, which would free space and hide a full disk.
            val full = failure is IOException && usableSpace(root) < reserveBytes
            pick.deleteRecursively()
            if (full) Staged.NoSpace else Staged.Unreadable
        }
    }

    private fun copy(uri: Uri, directory: File): String {
        if (!directory.mkdirs()) throw IOException("cannot create upload directory")
        val target = File(directory, safeName(displayName(uri)))
        val size = reportedSize(uri)
        if (size != null && usableSpace(root) - size < reserveBytes) throw OutOfStagingSpace()
        val input = resolver.openInputStream(uri) ?: throw IOException("no content")
        input.use { source -> target.outputStream().use { copyWithinReserve(source, it) } }
        return target.path
    }

    /**
     * Copies in chunks and keeps the reserve free on [root]: providers often cannot name their
     * size, so the volume only runs out as the bytes land. Free space is re-read at most once
     * per [SPACE_RECHECK_BYTES] written: reading it per chunk would stat for every 64 KiB.
     */
    private fun copyWithinReserve(source: InputStream, target: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var free = usableSpace(root)
        var sinceCheck = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) return
            if (sinceCheck >= SPACE_RECHECK_BYTES) {
                free = usableSpace(root)
                sinceCheck = 0L
            }
            if (free - read < reserveBytes) throw OutOfStagingSpace()
            target.write(buffer, 0, read)
            // Between re-reads, count what this copy used so the reserve cannot be overshot.
            free -= read
            sinceCheck += read
        }
    }

    /** The provider's size for the file; null or negative means it does not know. */
    private fun reportedSize(uri: Uri): Long? =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
        }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    /** Copying on would leave less than [reserveBytes] free on [root]. */
    private class OutOfStagingSpace : IOException("staging would leave too little free space")

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

        /** Free space a pick always leaves on the staging volume, for every app on the phone. */
        private const val MIN_FREE_BYTES = 512L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val SPACE_RECHECK_BYTES = 8L * 1024 * 1024

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
