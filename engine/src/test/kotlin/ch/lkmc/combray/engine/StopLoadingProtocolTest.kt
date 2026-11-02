package ch.lkmc.combray.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StopLoadingProtocolTest {
    @Test
    fun recognizesTheRetainedDocumentReply() {
        val event = EngineEvent.parse("""{"type":"loading_stopped","tab":3,"generation":4,"request":11,"document":7}""")
        assertEquals(EngineEvent.LoadingStopped(ViewRef(3, 4), 11, 7), event)
    }

    @Test
    fun recognizesAFailedCancellation() {
        val event = EngineEvent.parse("""{"type":"loading_stop_failed","tab":3,"generation":4,"request":11}""")
        assertEquals(EngineEvent.LoadingStopFailed(ViewRef(3, 4), 11), event)
    }

    @Test
    fun successRequiresTheRetainedDocumentIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineEvent.parse("""{"type":"loading_stopped","tab":3,"generation":4,"request":11}""")
        }
    }
}
