package ch.lkmc.combray

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A page writes its `intent:` links; Android's parser must not crash Combray on a bad one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalIntentParseTest {
    @Test
    fun aMalformedIntentLinkIsRefusedInsteadOfThrown() {
        val malformed = listOf(
            "intent://x#Intent;i.n=abc;end",
            "intent://x#Intent;launchFlags=zz;end",
            "intent://x#Intent;l.n=1.5;end",
        )
        for (link in malformed) assertNull(link, ExternalLinks.intentFor(link, OWN_PACKAGE))
    }

    @Test
    fun aWellFormedIntentLinkStillParses() {
        assertNotNull(ExternalLinks.intentFor("intent://fixture#Intent;scheme=combray-fixture;i.n=3;end", OWN_PACKAGE))
    }

    private companion object {
        const val OWN_PACKAGE = "ch.lkmc.combray"
    }
}
