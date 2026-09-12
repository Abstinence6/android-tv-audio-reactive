package org.hyperion.audioreactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePoliciesTest {
    @Test fun queuedDiscoveryCompletionIsIgnoredAfterCaptureAdmissionStarts() {
        val queued = 9L
        val captureAdmissionGeneration = 10L
        assertFalse(DiscoveryCompletionPolicy.mayMerge(queued, captureAdmissionGeneration, true))
        assertFalse(DiscoveryCompletionPolicy.mayMerge(queued, captureAdmissionGeneration, false))
    }

    @Test fun stoppedDiscoveryCompletionRemainsMergeEligible() {
        assertTrue(DiscoveryCompletionPolicy.mayMerge(9L, 9L, false))
        assertFalse(DiscoveryCompletionPolicy.mayMerge(9L, 9L, true))
    }

    @Test fun brightnessAndSilenceBothRequestImmediateBlack() {
        assertTrue(FrameSmoothingPolicy.immediateBlack(0f, true))
        assertTrue(FrameSmoothingPolicy.immediateBlack(.6f, false))
        assertTrue(FrameSmoothingPolicy.immediateBlack(AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO_AUDIO, videoAudioSilenceBrightnessFloor = .1f), false))
        assertFalse(FrameSmoothingPolicy.immediateBlack(AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO_AUDIO, videoAudioSilenceBrightnessFloor = .1f, silenceFadeEnabled = true), false))
        assertFalse(FrameSmoothingPolicy.immediateBlack(.6f, null))
    }

    @Test fun silenceFadeIsOptInAndInterpolatesOnlyWhenEnabled() {
        val controller = SilenceBrightnessController()
        val disabled = AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO_AUDIO, brightness = 1f, videoAudioSilenceBrightnessFloor = 0f, silenceHoldMillis = 0, silenceFadeMillis = 500)
        assertEquals(1f, controller.compose(disabled, false, 0L), 0f)
        assertTrue(FrameSmoothingPolicy.immediateBlack(disabled, false))

        val enabled = disabled.copy(silenceFadeEnabled = true)
        controller.compose(enabled, false, 0L) // enters HOLDING
        controller.compose(enabled, false, 1L) // enters FADING
        assertEquals(.5f, controller.compose(enabled, false, 250_000_001L), .001f)
        assertFalse(FrameSmoothingPolicy.immediateBlack(enabled, false))
    }
}
