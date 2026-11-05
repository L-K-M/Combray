package ch.lkmc.combray.core.integration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ReportFilesTest {
    @get:Rule val folder = TemporaryFolder()

    private val directory get() = File(folder.root, "reports")

    @Test
    fun aWrittenScreenshotReadsBackAndAMissingOneIsNull() {
        val files = ReportFiles(directory)
        files.write(ID, byteArrayOf(1, 2, 3))

        assertArrayEquals(byteArrayOf(1, 2, 3), files.read(ID))
        assertNull(files.read(OTHER_ID))
        assertEquals(listOf("$ID.png"), directory.list()!!.toList())

        files.delete(ID)
        assertNull(files.read(ID))
    }

    @Test
    fun aFailedWriteLeavesNoPartialFileAndKeepsTheEarlierOne() {
        ReportFiles(directory).write(ID, byteArrayOf(1))
        // Like a disk that fails to flush: the new bytes never reach the final name.
        val failing = ReportFiles(directory) { throw IOException("simulated") }

        assertThrows(IOException::class.java) { failing.write(ID, byteArrayOf(2, 2)) }
        assertThrows(IOException::class.java) { failing.write(OTHER_ID, byteArrayOf(3)) }

        assertArrayEquals(byteArrayOf(1), failing.read(ID))
        assertNull(failing.read(OTHER_ID))
        // No temporary file is left behind either.
        assertEquals(listOf("$ID.png"), directory.list()!!.toList())
    }

    @Test
    fun onlyReportIdsBecomePaths() {
        val files = ReportFiles(directory)
        val notIds = listOf(
            "",
            "../$ID",
            "$ID/../x",
            ID.uppercase(),
            // A version 1 UUID, and one without the RFC 4122 variant.
            "6f1c2b9e-1d2a-1a5b-9c3d-2e1f0a9b8c7d",
            "6f1c2b9e-1d2a-4a5b-7c3d-2e1f0a9b8c7d",
            "$ID.png",
        )
        for (id in notIds) {
            assertThrows(IllegalArgumentException::class.java) { files.write(id, byteArrayOf(1)) }
            assertThrows(IllegalArgumentException::class.java) { files.read(id) }
            assertThrows(IllegalArgumentException::class.java) { files.delete(id) }
        }
        assertFalse(directory.exists())
    }

    @Test
    fun retainingKeepsOnlyTheNamedReportsScreenshots() {
        val files = ReportFiles(directory)
        files.write(ID, byteArrayOf(1))
        files.write(OTHER_ID, byteArrayOf(2))
        // What a write cut short by process death leaves, and anything else in the directory.
        File(directory, "$OTHER_ID.0a1b.tmp").writeBytes(byteArrayOf(3))
        File(directory, "stray").mkdirs()

        val deleted = files.retainOnly(listOf(ID, "../$OTHER_ID.png"))

        assertEquals(3, deleted)
        assertEquals(listOf("$ID.png"), directory.list()!!.toList())
    }

    private companion object {
        const val ID = "6f1c2b9e-1d2a-4a5b-9c3d-2e1f0a9b8c7d"
        const val OTHER_ID = "0b6d3a52-7c1e-4f0a-8b2d-9e4c5a6f7d80"
    }
}
