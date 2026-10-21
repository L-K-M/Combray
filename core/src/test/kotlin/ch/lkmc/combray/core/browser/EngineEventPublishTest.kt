package ch.lkmc.combray.core.browser

import ch.lkmc.combray.engine.Attribution
import ch.lkmc.combray.engine.EngineEvent
import ch.lkmc.combray.engine.LoadPhase
import ch.lkmc.combray.engine.PageControlContent
import ch.lkmc.combray.engine.ViewRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Heartbeats and echoes must not trigger publish()'s Room reads on the serialized queue;
 * everything that can move published state (or is not yet known) still must.
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
            EngineEvent.TabClosed(view),
            EngineEvent.LoadStatus(view, document = 10, phase = LoadPhase.COMPLETE, attribution = Attribution.CURRENT),
            EngineEvent.TitleChanged(view, document = 10, title = "Page", attribution = Attribution.CURRENT),
            EngineEvent.ImeHidden(view),
            EngineEvent.Crashed(view, reason = "oom"),
            EngineEvent.ControlShown(view, control = 1, content = PageControlContent.Alert("hi")),
            EngineEvent.EngineError("refused", tab = 1, request = 2),
        )
        changing.forEach { event ->
            assertTrue("$event must publish", changesPublishedState(event))
        }
    }
}
