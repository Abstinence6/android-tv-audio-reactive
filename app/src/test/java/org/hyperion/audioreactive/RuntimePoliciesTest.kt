package org.hyperion.audioreactive

import org.junit.Assert.assertFalse
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
        assertFalse(FrameSmoothingPolicy.immediateBlack(.6f, null))
    }
}
