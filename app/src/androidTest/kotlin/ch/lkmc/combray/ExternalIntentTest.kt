package ch.lkmc.combray

import android.content.ComponentName
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The intents Combray builds from page links (`Intent.parseUri` needs Android, not a plain JVM). */
@RunWith(AndroidJUnit4::class)
class ExternalIntentTest {
    private val ownPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName

    @Test
    fun aPageWrittenIntentLosesItsFlagsComponentAndSelector() {
        val written = Intent(Intent.ACTION_VIEW, "https://a.test/".toUri()).apply {
            component = ComponentName("com.example", "com.example.Main")
            selector = Intent(Intent.ACTION_MAIN)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }.toUri(Intent.URI_INTENT_SCHEME)

        val intent = checkNotNull(ExternalLinks.intentFor(written, ownPackage))
        assertEquals(0, intent.flags)
        assertNull(intent.component)
        assertNull(intent.selector)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun aPageCannotSendAnIntentToCombrayItself() {
        val written = Intent(Intent.ACTION_VIEW, "https://a.test/".toUri())
            .setPackage(ownPackage)
            .toUri(Intent.URI_INTENT_SCHEME)

        assertNull(ExternalLinks.intentFor(written, ownPackage))
    }

    @Test
    fun aPageCannotPointAnotherAppAtDeviceFiles() {
        val own = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        assertNull(ExternalLinks.intentFor("intent:///etc/hosts#Intent;scheme=file;end", own))
        assertNull(ExternalLinks.intentFor("intent://media/external/images/1#Intent;scheme=content;end", own))
    }

    @Test
    fun aPlainLinkIsABrowsableViewWithoutFlags() {
        val intent = checkNotNull(ExternalLinks.intentFor("tel:+41000000000", ownPackage))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(0, intent.flags)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }
}
