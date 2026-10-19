package ch.lkmc.combray

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
