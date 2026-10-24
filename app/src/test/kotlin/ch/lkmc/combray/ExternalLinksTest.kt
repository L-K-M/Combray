package ch.lkmc.combray

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLinksTest {
    @Test
    fun theLinksTheEngineHandsOnGoToAnotherApp() {
        val links = listOf(
            "mailto:ada@example.org",
            "tel:+41000000000",
            "TEL:+41000000000",
            "sms:+41000000000",
            "smsto:+41000000000",
            "mms:+41000000000",
            "geo:46.2,6.1",
            "market://details?id=ch.lkmc.combray.fixture",
            "intent://fixture#Intent;scheme=combray-fixture;end",
        )
        for (link in links) assertTrue(link, ExternalLinks.isAppLink(link))
    }

    @Test
    fun nothingElseIsLaunched() {
        val links = listOf(
            "https://a.test/",
            "http://a.test/",
            "file:///system/etc/hosts",
            "content://ch.lkmc.combray.files/x",
            "javascript:alert(1)",
            "whatsapp://send?text=x",
            "mailto",
            "",
        )
        for (link in links) assertFalse(link, ExternalLinks.isAppLink(link))
    }
}
