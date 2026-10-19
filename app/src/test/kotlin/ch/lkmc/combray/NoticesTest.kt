package ch.lkmc.combray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoticesTest {
    private val native = """
        {"inputs":{},"spdx":{"Apache-2.0":1,"MIT":0},
         "components":[{"name":"servo","version":"0.6.0","license":"MPL-2.0","texts":[2]},
                       {"name":"LLVM libc++","version":null,"license":"Apache-2.0 WITH LLVM-exception","texts":[0]}],
         "texts":["mit text","apache text","mpl text"]}
    """.trimIndent()

    private fun jvm(license: String) = """
        [{"groupId":"androidx.core","artifactId":"core","version":"1.19.1","name":"Core",
          "spdxLicenses":[{"identifier":"$license","name":"x","url":"https://x.test/"}]}]
    """.trimIndent()

    @Test
    fun engineAndJvmLibrariesAreListedTogetherWithTheirTexts() {
        val notices = Notices.parse(native, jvm("Apache-2.0"))

        assertEquals(listOf("androidx.core:core", "LLVM libc++", "servo"), notices.components.map { it.name })
        val core = notices.components.first()
        assertEquals("1.19.1", core.version)
        assertEquals(listOf("apache text"), core.texts.map { notices.texts[it] })
        assertEquals(null, notices.components[1].version)
    }

    @Test
    fun aJvmLicenseWithoutATextIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { Notices.parse(native, jvm("GPL-3.0-only")) }
    }

    @Test
    fun aComponentReferringToAMissingTextIsRejected() {
        val broken = native.replace("\"texts\":[2]", "\"texts\":[9]")
        assertThrows(IllegalArgumentException::class.java) { Notices.parse(broken, "[]") }
    }

    /** The committed engine notices parse and name the engine. */
    @Test
    fun theBundledEngineNoticesParse() {
        val bundled = File("src/main/assets/notices/native.json").readText()
        val notices = Notices.parse(bundled, jvm("Apache-2.0"))
        assertTrue(notices.components.any { it.name == "servo" })
    }
}
