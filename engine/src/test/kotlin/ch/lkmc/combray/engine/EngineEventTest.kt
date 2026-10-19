package ch.lkmc.combray.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Events as libcombray_engine.so emits them (copied from M0 run reports). */
class EngineEventTest {
    @Test
    fun parsesDocumentCommitWithHistoryPosition() {
        val event = EngineEvent.parse(
            """{"type":"document_committed","document":4294967331,"sequence":12,"url":"http://localhost:8765/b.html",""" +
                """"index":1,"length":3,"tab":1,"generation":2,"schema":1}""",
        )

        val history = event as EngineEvent.HistoryChanged
        assertEquals(ViewRef(1, 2), history.view)
        assertEquals(HistoryKind.DOCUMENT_COMMITTED, history.kind)
        assertEquals(4294967331L, history.document)
        assertTrue(history.canGoBack)
        assertTrue(history.canGoForward)
    }

    @Test
    fun parsesStaleTitleWithNullTitle() {
        val event = EngineEvent.parse(
            """{"type":"title","document":7,"title":null,"attribution":"stale","tab":3,"generation":4,"schema":1}""",
        )

        val title = event as EngineEvent.TitleChanged
        assertNull(title.title)
        assertEquals(Attribution.STALE, title.attribution)
    }

    @Test
    fun parsesCaptureWithContentAndStaleWithout() {
        val captured = EngineEvent.parse(
            """{"type":"capture","capture":10155,"document":4294967331,"status":"captured","elapsed_ms":122,""" +
                """"content":{"url":"http://localhost:8765/b.html","title":"M0-B","text":"M0-B NONCE","text_bytes":10,""" +
                """"sha256":"a0cf","truncated_in_page":false,"truncated_natively":false,"nodes_visited":13,""" +
                """"node_budget_exhausted":false},"observed_url":null,"detail":null,"tab":1,"generation":1,"schema":1}""",
        ) as EngineEvent.CaptureResult
        val stale = EngineEvent.parse(
            """{"type":"capture","capture":10064,"document":4294967317,"status":"stale_document","elapsed_ms":10332,""" +
                """"content":null,"observed_url":"http://localhost:8765/slow-capture.html","detail":null,"tab":1,""" +
                """"generation":1,"schema":1}""",
        ) as EngineEvent.CaptureResult

        assertEquals(CaptureStatus.CAPTURED, captured.status)
        assertEquals("M0-B NONCE", captured.content?.text)
        assertEquals(CaptureStatus.STALE_DOCUMENT, stale.status)
        assertNull(stale.content)
    }

    @Test
    fun keepsUnknownTypesAsOther() {
        assertEquals(EngineEvent.Other("default_handled"), EngineEvent.parse("""{"type":"default_handled","request":"x"}"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsKnownTypeWithMissingFields() {
        EngineEvent.parse("""{"type":"tab_created","tab":1}""")
    }
}
