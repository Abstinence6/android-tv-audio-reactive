package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitStopTerminalDiagnosticTest {

    @Test fun explicitStartActionRoutesThroughTerminalStopBeforeTeardownAndPreservesItsCauseOnDestruction() {
        LocalStatusStore.reset(CaptureStatus.CAPTURE_ACTIVE_VIDEO)
        val events = mutableListOf<String>()
        val lifecycle = CaptureServiceLifecycle(cleanup = { events += "teardown" })
        val terminalStop = CaptureTerminalStop(lifecycle) { cause ->
            TerminalDiagnostics.capture(cause, RenderMode.VIDEO, RenderMode.VIDEO, null, TransitionDiagnostics.OwnedState(true, false, true, "mediaProjection"), 32)
            events += "diagnostic"
        }
        assertTrue(lifecycle.beginStart { true })
        assertTrue(lifecycle.activate { })

        assertEquals(
            AudioReactiveServiceCommandDispatch.Result.TERMINAL_STOP,
            AudioReactiveServiceCommandDispatch.dispatch(
                AudioReactiveService.ACTION_EXPLICIT_STOP,
                terminalStop::stop,
                localTransition = { error("explicit stop must not run a local transition") },
            ),
        )

        assertEquals("explicit_stop", LocalStatusStore.snapshot().terminalCause)
        assertEquals(listOf("diagnostic", "teardown"), events)
        AudioReactiveServiceCommandDispatch.onDestroy(terminalStop::stop)
        assertEquals("explicit_stop", LocalStatusStore.snapshot().terminalCause)
        assertEquals(listOf("diagnostic", "teardown"), events)
    }

    @Test fun unmarkedDestructionRecordsDestroyCause() {
        LocalStatusStore.reset(CaptureStatus.CAPTURE_ACTIVE_AUDIO)
        val events = mutableListOf<String>()
        val lifecycle = CaptureServiceLifecycle(cleanup = { events += "teardown" })
        val terminalStop = CaptureTerminalStop(lifecycle) { cause ->
            TerminalDiagnostics.capture(cause, RenderMode.AUDIO, RenderMode.AUDIO, null, TransitionDiagnostics.OwnedState(true, true, false, "mediaProjection"), 32)
            events += "diagnostic"
        }
        assertTrue(lifecycle.beginStart { true })
        assertTrue(lifecycle.activate { })

        AudioReactiveServiceCommandDispatch.onDestroy(terminalStop::stop)

        assertEquals("destroy", LocalStatusStore.snapshot().terminalCause)
        assertEquals(listOf("diagnostic", "teardown"), events)
    }

    @Test fun localTransitionDispatchesOnlyTheInjectedTransitionCallback() {
        var stopped = false
        var transitioned = false

        assertEquals(
            AudioReactiveServiceCommandDispatch.Result.LOCAL_TRANSITION,
            AudioReactiveServiceCommandDispatch.dispatch(
                AudioReactiveService.ACTION_LOCAL_TRANSITION,
                terminalStop = { stopped = true; true },
                localTransition = { transitioned = true },
            ),
        )

        assertTrue(!stopped)
        assertTrue(transitioned)
    }

    @Test fun normalStartDispatchesNoEarlyServiceAction() {
        var stopped = false
        var transitioned = false

        assertEquals(
            AudioReactiveServiceCommandDispatch.Result.NORMAL_START,
            AudioReactiveServiceCommandDispatch.dispatch(
                action = null,
                terminalStop = { stopped = true; true },
                localTransition = { transitioned = true },
            ),
        )

        assertTrue(!stopped)
        assertTrue(!transitioned)
    }
}
