package ch.lkmc.combray

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class UploadStagingTest {
    @Test
    fun pickedNamesStayInsideTheirDirectory() {
        assertEquals("photo.png", UploadStaging.safeName("photo.png"))
        assertEquals("passwd", UploadStaging.safeName("../../etc/passwd"))
        assertEquals("upload", UploadStaging.safeName(".."))
        assertEquals("upload", UploadStaging.safeName(null))
        assertEquals("ab", UploadStaging.safeName("a\u0000b\n"))
        assertEquals(120, UploadStaging.safeName("x".repeat(500)).length)
    }

    @Test
    fun acceptExtensionsBecomePickerTypesOrEverything() {
        val known = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg")
        assertArrayEquals(arrayOf("image/png", "image/jpeg"), UploadStaging.mimeTypesFor(listOf("PNG", "jpg", "jpeg"), known::get))
        assertArrayEquals(arrayOf("*/*"), UploadStaging.mimeTypesFor(listOf("png", "weird"), known::get))
        assertArrayEquals(arrayOf("*/*"), UploadStaging.mimeTypesFor(emptyList(), known::get))
    }

    @Test
    fun aCopyWithinTheLimitKeepsEveryByte() {
        val body = ByteArray(10_000) { it.toByte() }
        val target = ByteArrayOutputStream()
        assertTrue(UploadStaging.boundedCopy(ByteArrayInputStream(body), target, 10_000))
        assertArrayEquals(body, target.toByteArray())
    }

    @Test
    fun aCopyOverTheLimitStopsAtTheLimit() {
        val target = ByteArrayOutputStream()
        val copied = UploadStaging.boundedCopy(ByteArrayInputStream(ByteArray(20_000)), target, 10_000)
        assertFalse(copied)
        assertTrue("copied ${target.size()} bytes", target.size() <= 10_000)
    }

    @Test(timeout = 1_000)
    fun anEndlessSourceIsNeverCopiedWhole() {
        // A provider can answer far more than the limit (or lie about its size): the copy must
        // stop reading at the limit instead of draining the source.
        val endless = object : InputStream() {
            override fun read(): Int = 0

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(0, off, off + len)
                return len
            }
        }
        assertFalse(UploadStaging.boundedCopy(endless, ByteArrayOutputStream(), 10_000))
    }
}
