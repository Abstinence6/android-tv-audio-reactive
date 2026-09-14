package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTransitionPolicyTest {
    @Test fun everyLocalRenderPairHasExactSourceRequirements() {
        RenderMode.entries.forEach { from -> RenderMode.entries.forEach { target ->
            val policy = LocalTransitionPolicy(from)
            val nonce = "${from}_${target}"
            policy.mint(1, nonce)
            val result = policy.decide(LocalTransitionRequest(1, nonce, target, from == RenderMode.ANIMATION && target != RenderMode.ANIMATION))
            assertTrue("$from -> $target", result is TransitionDecision.Accept)
            assertEquals(RenderRequirements.forMode(target), (result as TransitionDecision.Accept).requirements)
            policy.commit(target)
            assertEquals(target, policy.current())
        } }
    }

    @Test fun nonceAndEpochAreExactlyOnceAndBothOffIsRejectedByCheckboxPolicy() {
        val policy = LocalTransitionPolicy(RenderMode.AUDIO)
        policy.mint(1, "n")
        assertTrue(policy.decide(LocalTransitionRequest(1, "n", RenderMode.VIDEO, false)) is TransitionDecision.Accept)
        assertFalse(policy.decide(LocalTransitionRequest(1, "n", RenderMode.VIDEO, false)) is TransitionDecision.Accept)
        policy.mint(3, "later")
        assertFalse(policy.decide(LocalTransitionRequest(2, "later", RenderMode.AUDIO, false)) is TransitionDecision.Accept)
        assertTrue(CaptureModeCheckboxPolicy.resolve(false, false, false, RenderMode.VIDEO).rejected)
    }

    @Test fun wledAdmissionIsTransitionCapableEvenWhenInitialModeIsAudioOrAnimation() {
        val device = WledDevice("mac:AABBCCDDEEFF", "TV", "192.168.1.2", 16, 21324)
        val base = AudioSettings.defaults().copy(outputMode = OutputMode.WLED, wledDevices = listOf(device), selectedWledIdentities = setOf(device.identity))
        assertFalse(WledCapturePreflight.transitionCapable(base.copy(renderMode = RenderMode.AUDIO)))
        val calibrated = base.copy(wledCalibrations = listOf(WledScreenCalibration.proportional(device.identity, device.leds)))
        assertTrue(WledCapturePreflight.transitionCapable(calibrated.copy(renderMode = RenderMode.AUDIO)))
        assertTrue(WledCapturePreflight.transitionCapable(calibrated.copy(renderMode = RenderMode.ANIMATION)))
        assertTrue(LiveRenderModeTransitionPolicy.permits(calibrated, RenderMode.VIDEO_AUDIO))
    }

    @Test fun animationCrossingRequiresAndOnlyAcceptsFreshProjectionResult() {
        val policy = LocalTransitionPolicy(RenderMode.ANIMATION)
        policy.mint(1, "missing")
        assertFalse(policy.decide(LocalTransitionRequest(1, "missing", RenderMode.VIDEO, false)) is TransitionDecision.Accept)
        policy.mint(1, "consent")
        assertTrue(policy.decide(LocalTransitionRequest(1, "consent", RenderMode.VIDEO, true)) is TransitionDecision.Accept)
    }
}
