package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestFrameActionTest {
    private val wled = WledDevice("mac:AABBCCDDEEFF", "Desk", "192.168.1.152", 30, 21324)
    private val hyperion = HyperionDevice("uuid:123e4567-e89b-12d3-a456-426614174000", "Desk", "192.168.1.158", 19444, 19400)

    @Test fun wledTestRequiresFreshPreflightAndConsumesItsBindingOnce() {
        val settings = AudioSettings.defaults().copy(outputMode = OutputMode.WLED, wledDevices = listOf(wled), selectedWledIdentities = setOf(wled.identity))
        var preflightCalls = 0
        var sent: List<WledDevice>? = null
        val result = TestFrameAction.execute(
            settings,
            wledPreflight = { current -> preflightCalls++; WledCapturePreflight.bind(current) { listOf(wled) } },
            wledSender = { targets, frame -> sent = targets; assertEquals(48, frame.size) },
        )
        assertTrue(result)
        assertEquals(1, preflightCalls)
        assertEquals(listOf(wled), sent)
    }

    @Test fun refusesTestWhenFreshEndpointChangedOrUnselected() {
        val selected = AudioSettings.defaults().copy(outputMode = OutputMode.WLED, wledDevices = listOf(wled), selectedWledIdentities = setOf(wled.identity))
        var sent = false
        assertFalse(TestFrameAction.execute(selected, wledPreflight = { current -> WledCapturePreflight.bind(current) { listOf(wled.copy(host = "192.168.1.26")) } }, wledSender = { _, _ -> sent = true }))
        assertFalse(sent)
        assertFalse(TestFrameAction.execute(AudioSettings.defaults().copy(outputMode = OutputMode.WLED), wledPreflight = { current -> WledCapturePreflight.bind(current) { emptyList() } }, wledSender = { _, _ -> sent = true }))
        assertFalse(sent)
    }

    @Test fun hyperionTestUsesFreshOneShotBindingAndOnlySenderSeam() {
        val settings = AudioSettings.defaults().copy(outputMode = OutputMode.HYPERION, hyperionDevices = listOf(hyperion), selectedHyperionIdentity = hyperion.identity)
        var sent: HyperionDevice? = null
        val binding = HyperionCapturePreflight.bind(settings) { listOf(hyperion) }
        assertNotNull(binding)
        assertEquals(hyperion, HyperionRouteBindings.consume(binding, settings))
        assertNull(HyperionRouteBindings.consume(binding, settings))
        assertTrue(TestFrameAction.execute(settings, hyperionPreflight = { current -> HyperionCapturePreflight.bind(current) { listOf(hyperion) } }, hyperionSender = { target, frame -> sent = target; assertEquals(TestFrameAction.frame.toList(), frame.toList()) }))
        assertEquals(hyperion, sent)
    }

    @Test fun testActionDoesNotNeedCaptureEligibilityOrProjectionState() {
        val settings = AudioSettings.defaults().copy(outputMode = OutputMode.WLED, wledDevices = listOf(wled), selectedWledIdentities = setOf(wled.identity))
        // The only injected behavior is preflight plus direct frame send: no permission, service, or projection seam exists.
        assertTrue(TestFrameAction.execute(settings, wledPreflight = { current -> WledCapturePreflight.bind(current) { listOf(wled) } }, wledSender = { _, _ -> Unit }))
    }

    @Test fun hyperionDiagnosticRegistersDistinctOriginSendsThenClears() {
        val calls = mutableListOf<String>()
        TestFrameAction.sendHyperionForTest(hyperion, TestFrameAction.frame) {
            object : TestFrameAction.HyperionDiagnosticOutput {
                override fun register() { calls += "register:${HyperionFlatbuffer.DIAGNOSTIC_ORIGIN}:${HyperionFlatbuffer.PRIORITY}" }
                override fun send(frame: ByteArray) { calls += "image:${frame.size}" }
                override fun clear() { calls += "clear:${HyperionFlatbuffer.PRIORITY}" }
                override fun close() { calls += "close" }
            }
        }
        assertEquals(listOf("register:${HyperionFlatbuffer.DIAGNOSTIC_ORIGIN}:101", "image:48", "clear:101", "close"), calls)
        assertFalse(HyperionFlatbuffer.DIAGNOSTIC_ORIGIN == HyperionFlatbuffer.CAPTURE_ORIGIN)
    }

    @Test fun failedHyperionDiagnosticRegistrationDoesNotClearPriority() {
        val calls = mutableListOf<String>()
        try {
            TestFrameAction.sendHyperionForTest(hyperion, TestFrameAction.frame) {
                object : TestFrameAction.HyperionDiagnosticOutput {
                    override fun register() { calls += "register"; throw IllegalStateException("register failed") }
                    override fun send(frame: ByteArray) { calls += "image" }
                    override fun clear() { calls += "clear" }
                    override fun close() { calls += "close" }
                }
            }
        } catch (_: IllegalStateException) {}
        assertEquals(listOf("register", "close"), calls)
    }

    @Test fun outputDiagnosticIsExplicitlyWithheldDuringCapture() {
        assertTrue(TestFrameActionPolicy.mayExecute(false))
        assertFalse(TestFrameActionPolicy.mayExecute(true))
        assertTrue(TestFrameActionPolicy.ACTIVE_CAPTURE_REASON.contains("пріоритетом/сокетом"))
    }
}
