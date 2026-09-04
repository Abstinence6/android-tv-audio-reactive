package org.hyperion.audioreactive

import org.junit.Assert.*
import org.junit.Test

class WledDiagnosticsTest {
    private val device = WledDevice("mac:001122334455", "TV", "192.168.1.152", 8, 21324)
    private val calibration = WledScreenCalibration.proportional(device.identity, 8).copy(startPixel = 2, direction = PerimeterDirection.CCW)
    private val settings = AudioSettings.defaults().copy(outputMode = OutputMode.WLED, wledDevices = listOf(device), selectedWledIdentities = setOf(device.identity))

    @Test fun packetPatternsArePhysicalBoundedAndHonorEdgeAllocation() {
        WledDiagnosticPattern.entries.forEach { pattern ->
            val frames = WledDiagnosticPackets.frames(calibration, pattern)
            assertTrue(frames.isNotEmpty()); assertTrue(frames.size <= 16)
            assertTrue(frames.all { it.size == device.leds * 3 })
        }
        val edges = WledDiagnosticPackets.frames(calibration, WledDiagnosticPattern.FOUR_EDGE_COLORS).single()
        assertEquals(8, edges.asList().chunked(3).count { it.any { channel -> channel != 0.toByte() } })
    }

    @Test fun diagnosticRequiresFreshOneShotExactTargetThenBlackoutsAndCloses() {
        val calls = mutableListOf<String>()
        val sent = WledDiagnosticAction.execute(settings, device, calibration, WledDiagnosticPattern.SINGLE_PIXEL_CHASE,
            preflight = { current -> WledCapturePreflight.bind(current) { listOf(device) } },
            create = { object : WledDiagnosticAction.Output {
                override fun send(frame: ByteArray) { calls += "frame:${frame.size}" }
                override fun blackout() { calls += "blackout" }
                override fun close() { calls += "close" }
            } })
        assertTrue(sent); assertEquals("blackout", calls[calls.size - 2]); assertEquals("close", calls.last())
        assertTrue(calls.dropLast(2).all { it == "frame:24" })
    }

    @Test fun unsavedValidDraftRoutesVideoAndAudioVideoDiagnosticsThenCleansUp() {
        listOf(RenderMode.VIDEO, RenderMode.VIDEO_AUDIO).forEach { mode ->
            val calls = mutableListOf<String>()
            var preflightSettings: AudioSettings? = null
            val unsaved = settings.copy(renderMode = mode)
            assertTrue(unsaved.wledCalibrations.isEmpty())
            val sent = WledDiagnosticAction.execute(unsaved, device, calibration, WledDiagnosticPattern.CORNER_MARKERS,
                preflight = { current ->
                    preflightSettings = current
                    WledCapturePreflight.bind(current) { listOf(device) }
                },
                create = { object : WledDiagnosticAction.Output {
                    override fun send(frame: ByteArray) { calls += "frame:${frame.size}" }
                    override fun blackout() { calls += "blackout" }
                    override fun close() { calls += "close" }
                } })
            assertTrue(sent)
            assertEquals(setOf(device.identity), preflightSettings?.selectedWledIdentities)
            assertEquals(listOf(calibration), preflightSettings?.wledCalibrations)
            assertTrue(unsaved.wledCalibrations.isEmpty())
            assertEquals(listOf("frame:24", "blackout", "close"), calls)
        }
    }

    @Test fun changedOrUnselectedEndpointGetsNoDiagnosticTraffic() {
        var created = false
        val changed = WledDiagnosticAction.execute(settings, device, calibration, WledDiagnosticPattern.CORNER_MARKERS,
            preflight = { current -> WledCapturePreflight.bind(current) { listOf(device.copy(host = "192.168.1.9")) } },
            create = { created = true; error("must not create") })
        assertFalse(changed); assertFalse(created)
    }
}
