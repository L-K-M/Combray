package ch.lkmc.combray.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun parsesPageControls() {
        val select = EngineEvent.parse(
            """{"type":"control_shown","control":5,"kind":"select","multiple":false,"selected":[2],""" +
                """"options":[{"id":1,"label":"One","disabled":true,"group":null},{"id":2,"label":"Two","disabled":false,"group":"G"}],""" +
                """"tab":1,"generation":2,"schema":1}""",
        ) as EngineEvent.ControlShown
        assertEquals(5L, select.control)
        assertEquals(
            PageControlContent.Select(listOf(SelectEntry(1, "One", true, null), SelectEntry(2, "Two", false, "G")), listOf(2), false),
            select.content,
        )

        val prompt = EngineEvent.parse(
            """{"type":"control_shown","control":6,"kind":"prompt","message":"Name?","default":"Zoë","tab":1,"generation":2}""",
        ) as EngineEvent.ControlShown
        assertEquals(PageControlContent.Prompt("Name?", "Zoë"), prompt.content)

        val files = EngineEvent.parse(
            """{"type":"control_shown","control":7,"kind":"files","multiple":true,"extensions":["png","pdf"],"tab":1,"generation":2}""",
        ) as EngineEvent.ControlShown
        assertEquals(PageControlContent.Files(true, listOf("png", "pdf")), files.content)

        val menu = EngineEvent.parse(
            """{"type":"control_shown","control":8,"kind":"link_menu","link":"https://a.test/","image":null,"tab":1,"generation":2}""",
        ) as EngineEvent.ControlShown
        assertEquals(PageControlContent.LinkMenu("https://a.test/", null), menu.content)

        val signIn = EngineEvent.parse(
            """{"type":"control_shown","control":9,"kind":"sign_in","origin":"https://a.test:8443","proxy":false,"tab":1,"generation":2}""",
        ) as EngineEvent.ControlShown
        assertEquals(PageControlContent.SignIn("https://a.test:8443", proxy = false), signIn.content)

        assertEquals(
            EngineEvent.ControlHidden(ViewRef(1, 2), 7),
            EngineEvent.parse("""{"type":"control_hidden","control":7,"tab":1,"generation":2}"""),
        )
    }

    @Test
    fun parsesLeavePageQuestion() {
        val leave = EngineEvent.parse(
            """{"type":"control_shown","control":10,"kind":"leave_page","tab":1,"generation":2}""",
        ) as EngineEvent.ControlShown
        assertEquals(PageControlContent.LeavePage, leave.content)
    }

    /** Back and Cancel must keep the page: Servo's own default would leave it. */
    @Test
    fun leavePageChoicesAnswerAsNativeExpects() {
        assertEquals("""{"type":"accept"}""", LeavePageChoice.LEAVE.response.toJson())
        assertEquals("""{"type":"dismiss"}""", LeavePageChoice.STAY.response.toJson())
    }

    @Test
    fun controlResponsesSerializeAsNativeExpects() {
        assertEquals("""{"type":"select","ids":[1,3]}""", ControlResponse.Select(listOf(1, 3)).toJson())
        assertEquals("""{"type":"accept"}""", ControlResponse.Accept.toJson())
        assertEquals("""{"type":"text","value":"a\"b"}""", ControlResponse.Text("a\"b").toJson())
        assertEquals("""{"type":"files","paths":["/x/a.png"]}""", ControlResponse.Files(listOf("/x/a.png")).toJson())
        assertEquals("""{"type":"dismiss"}""", ControlResponse.Dismiss.toJson())
        assertEquals(
            """{"type":"credentials","username":"ada","password":"p\"w"}""",
            ControlResponse.Credentials("ada", "p\"w").toJson(),
        )
    }

    /** Responses may end up in logs or crash reports; a password must not. */
    @Test
    fun credentialsHideThePasswordWhenPrinted() {
        val credentials = ControlResponse.Credentials("ada", "hunter2")
        assertFalse(credentials.toString().contains("hunter2"))
        assertTrue(credentials.toString().contains("ada"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownControlKind() {
        EngineEvent.parse("""{"type":"control_shown","control":1,"kind":"launch","tab":1,"generation":2}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsKnownTypeWithMissingFields() {
        EngineEvent.parse("""{"type":"tab_created","tab":1}""")
    }
}
