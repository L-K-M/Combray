package ch.lkmc.combray.core.integration

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Screenshots of queued site reports, one `<report id>.png` per report in [directory] (the
 * app's no-backup storage). A file is complete under its final name or absent: it is written
 * to a temporary name, synced and renamed. Only a report id (a lowercase UUIDv4) ever becomes
 * part of a path, so nothing from a page or a stored payload can name another file.
 *
 * Not thread-safe across write and [retainOnly]: [SiteReports] serialises them.
 */
internal class ReportFiles(
    private val directory: File,
    /** Flushes a written file to storage; replaced in tests to simulate a failing disk. */
    private val sync: (FileOutputStream) -> Unit = { it.fd.sync() },
) {
    /** Stores [bytes] as the screenshot of report [id], replacing an earlier one. */
    @Throws(IOException::class)
    fun write(id: String, bytes: ByteArray) {
        val target = file(id)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("no report directory")
        val temporary = File(directory, "$id.${UUID.randomUUID()}$TEMPORARY_SUFFIX")
        try {
            FileOutputStream(temporary).use { out ->
                out.write(bytes)
                sync(out)
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
        syncDirectory()
    }

    /** The screenshot of report [id], or null when there is none. */
    @Throws(IOException::class)
    fun read(id: String): ByteArray? = try {
        file(id).readBytes()
    } catch (missing: FileNotFoundException) {
        null
    }

    fun delete(id: String) {
        file(id).delete()
    }

    /**
     * Deletes every file but the screenshots of [ids]: finished reports' screenshots,
     * temporary files a write left behind at process death, anything else. Returns how many
     * were deleted.
     */
    fun retainOnly(ids: Collection<String>): Int {
        val keep = ids.filter(::isReportId).mapTo(HashSet()) { "$it$EXTENSION" }
        val unused = directory.listFiles().orEmpty().filter { it.name !in keep }
        return unused.count { it.deleteRecursively() }
    }

    private fun file(id: String): File {
        require(isReportId(id)) { "not a report id" }
        return File(directory, "$id$EXTENSION")
    }

    /**
     * Makes the rename itself durable. Best effort: where a directory cannot be opened for
     * syncing, the file system's own journal commit still persists it shortly after, and a
     * report row whose file was lost fails visibly (`screenshot_missing`), never silently.
     */
    private fun syncDirectory() {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (unsupported: IOException) {
            // Kept best effort on purpose, see above.
        }
    }

    companion object {
        private const val EXTENSION = ".png"
        private const val TEMPORARY_SUFFIX = ".tmp"

        /** The service's report id pattern: a lowercase UUIDv4 with the RFC 4122 variant. */
        private val REPORT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        fun isReportId(id: String): Boolean = REPORT_ID.matches(id)
    }
}
