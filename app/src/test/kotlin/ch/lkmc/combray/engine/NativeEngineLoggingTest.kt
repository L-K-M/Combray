package ch.lkmc.combray.engine

import android.app.Application
import android.util.Log
import java.io.File
import java.lang.reflect.Method
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * A malformed native event must be dropped, and its log must carry only the parser's exception
 * class and the event's length: native event text can be page content, so neither a message nor
 * a throwable derived from it may reach the log.
 *
 * The host is instantiated without [NativeEngineHost.start], so the JNI library is never loaded;
 * the private [NativeEngineHost.deliver] is invoked reflectively to keep the production API
 * private. Every marker is synthetic; no real site, history or report data is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, application = Application::class)
class NativeEngineLoggingTest {
    private val marker = "SYNTHETIC-PRIVATE-MARKER-7c1e"

    /** deliver() is the only production method invoked: start(), and with it JNI, is never called. */
    private val host = NativeEngineHost(File("synthetic-profile"), 1.0f)

    @Before
    fun clearLogs() {
        ShadowLog.clear()
    }

    @Test
    fun malformedJsonIsDroppedAndLoggedByClassAndLengthOnly() {
        // Truncated after the marker: the parser's JSON failure quotes its input.
        val json = """{"schema":1,"type":"load_status","tab":1,"generation":1,"document":1,""" +
            """"phase":"started","attribution":"current","detail":"$marker"""
        val failure = requireNotNull(referenceParse(json))
        assertTrue("the syntax case must fail inside the parser", failure is SerializationException)

        val delivered = mutableListOf<EngineEvent>()
        deliver(json) { event -> delivered.add(event) }

        assertTrue("a malformed event must be dropped, not delivered", delivered.isEmpty())
        val entry = droppedEventLog()
        assertNull("a throwable can carry the input", entry.throwable)
        assertEquals("a dropped event must be logged as an error", Log.ERROR, entry.type)
        assertEquals(LOG_TAG, entry.tag)
        assertFalse("the log must not quote the input marker; logged: ${entry.msg}", entry.msg.contains(marker))
        assertFalse("the log must not quote the raw event", entry.msg.contains(json))
        assertTrue(
            "the log must name the parser's exception class",
            entry.msg.contains(failure.javaClass.name),
        )
        assertTrue("the log must name the event's length", entry.msg.contains("${json.length} chars"))
    }

    @Test
    fun anUnknownSemanticValueIsDroppedAndLoggedByClassAndLengthOnly() {
        // A well-formed envelope whose load phase is unknown: the parser's own message names the
        // value, so only the exception class may be logged.
        val json = """{"schema":1,"type":"load_status","tab":1,"generation":1,"document":1,""" +
            """"phase":"$marker","attribution":"current"}"""
        val failure = requireNotNull(referenceParse(json))
        assertTrue("the semantic case must fail inside the parser", failure is IllegalArgumentException)
        assertFalse("the semantic case must not be a JSON syntax failure", failure is SerializationException)
        assertTrue(
            "the reference failure is expected to name the unknown value",
            failure.message.orEmpty().contains(marker),
        )

        val delivered = mutableListOf<EngineEvent>()
        deliver(json) { event -> delivered.add(event) }

        assertTrue("a malformed event must be dropped, not delivered", delivered.isEmpty())
        val entry = droppedEventLog()
        assertNull("a throwable can carry the input", entry.throwable)
        assertEquals("a dropped event must be logged as an error", Log.ERROR, entry.type)
        assertEquals(LOG_TAG, entry.tag)
        assertFalse("the log must not quote the input marker; logged: ${entry.msg}", entry.msg.contains(marker))
        assertFalse("the log must not quote the raw event", entry.msg.contains(json))
        assertTrue(
            "the log must name the parser's exception class",
            entry.msg.contains(failure.javaClass.name),
        )
        assertTrue("the log must name the event's length", entry.msg.contains("${json.length} chars"))
    }

    /** The parser's own reading of [json], to name the exception class the host must log. */
    private fun referenceParse(json: String): Throwable? = runCatching { EngineEvent.parse(json) }.exceptionOrNull()

    /** Invokes the host's private deliver(json, listener) without adding a production seam. */
    private fun deliver(json: String, listener: (EngineEvent) -> Unit) {
        val method: Method = NativeEngineHost::class.java.getDeclaredMethod(
            "deliver",
            String::class.java,
            Function1::class.java,
        )
        method.isAccessible = true
        method.invoke(host, json, listener)
    }

    private fun droppedEventLog(): ShadowLog.LogItem {
        val entries = ShadowLog.getLogsForTag(LOG_TAG).filter { it.msg.startsWith("unparseable engine event") }
        assertTrue("a dropped malformed event must be logged, not silently ignored", entries.isNotEmpty())
        return entries.first()
    }

    private companion object {
        const val LOG_TAG = "CombrayEngine"
    }
}
