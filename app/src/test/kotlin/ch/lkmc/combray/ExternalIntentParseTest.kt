package ch.lkmc.combray

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    /** As in Chrome: also when the app refuses Combray, and whatever the case of the scheme. */
    @Test
    fun aLinkNoAppOpensFallsBackToItsOwnWebPage() {
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun startActivity(intent: Intent) {
                    throw failure
                }
            }
            val fallback = "intent://x#Intent;scheme=combray-fixture;S.browser_fallback_url=HTTPS%3A%2F%2Fa.test%2F;end"
            assertEquals(ExternalOutcome.Fallback("HTTPS://a.test/"), ExternalLinks.open(context, fallback))

            val noPage = "intent://x#Intent;scheme=combray-fixture;S.browser_fallback_url=javascript%3Aalert(1);end"
            assertEquals(ExternalOutcome.NoApp, ExternalLinks.open(context, noPage))
            assertEquals(ExternalOutcome.NoApp, ExternalLinks.open(context, "intent://x#Intent;scheme=combray-fixture;end"))
        }
    }

    private companion object {
        const val OWN_PACKAGE = "ch.lkmc.combray"
    }
}
