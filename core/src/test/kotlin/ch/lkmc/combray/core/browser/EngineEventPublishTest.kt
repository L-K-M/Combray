package ch.lkmc.combray.core.browser

import ch.lkmc.combray.engine.Attribution
import ch.lkmc.combray.engine.CaptureStatus
import ch.lkmc.combray.engine.EngineEvent
import ch.lkmc.combray.engine.HistoryKind
import ch.lkmc.combray.engine.LoadPhase
import ch.lkmc.combray.engine.PageControlContent
import ch.lkmc.combray.engine.ViewRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Heartbeats and echoes must not trigger publish()'s Room reads on the serialized queue;
 * everything that can move published state still must. The two lists mirror
 * changesPublishedState's branches one-for-one — eleven inert, fourteen publishing — so a
 * reclassification fails here as loudly as it fails the exhaustive when in production.
 */
class EngineEventPublishTest {
    private val view = ViewRef(1, 1)

    @Test
    fun heartbeatsAndEchoesDoNotPublish() {
        val stateless = listOf(
            EngineEvent.Painted(view, frames = 3),
            EngineEvent.SurfaceBound(width = 720, height = 1280),
            EngineEvent.SurfaceUnbound,
            EngineEvent.TabSelected(view),
            EngineEvent.NavigationRequested(view, request = 7, url = "https://a.test/"),
            EngineEvent.PageNavigationRequested(view, url = "https://a.test/"),
            EngineEvent.LateCaptureResult(capture = 11),
            EngineEvent.EvaluationResult(view, request = 3, ok = false, value = null, error = "no"),
            EngineEvent.ShutdownComplete,
            EngineEvent.ProfilePersisted(ok = true),
            EngineEvent.Other(type = "default_handled"),
        )
        stateless.forEach { event ->
            assertFalse("$event must not publish", changesPublishedState(event))
        }
    }

    @Test
    fun stateChangingEventsPublish() {
        val changing = listOf(
            EngineEvent.EngineReady(width = 720, height = 1280),
            EngineEvent.TabCreated(view),
            EngineEvent.TabOpened(view, opener = view),
            EngineEvent.TabClosed(view),
            EngineEvent.HistoryChanged(view, HistoryKind.DOCUMENT_COMMITTED, document = 10, url = "https://a.test/", index = 0, length = 1),
            EngineEvent.LoadStatus(view, document = 10, phase = LoadPhase.COMPLETE, attribution = Attribution.CURRENT),
            EngineEvent.CaptureResult(capture = 1, view = view, document = 10, status = CaptureStatus.CAPTURED, content = null, detail = null),
            EngineEvent.TitleChanged(view, document = 10, title = "Page", attribution = Attribution.CURRENT),
            EngineEvent.ImeShown(view, multiline = false, kind = "text"),
            EngineEvent.ImeHidden(view),
            EngineEvent.Crashed(view, reason = "oom"),
            EngineEvent.ControlShown(view, control = 1, content = PageControlContent.Alert("hi")),
            EngineEvent.ControlHidden(view, control = 1),
            EngineEvent.EngineError("refused", tab = 1, request = 2),
        )
        changing.forEach { event ->
            assertTrue("$event must publish", changesPublishedState(event))
        }
    }
}
