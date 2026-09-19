package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTransitionCoordinatorTest {
    @Test fun serviceCoordinatorOrdersFgsBeforeEveryNewSourceAndCommitsOnce() {
        listOf(
            RenderMode.VIDEO to RenderMode.AUDIO,
            RenderMode.AUDIO to RenderMode.VIDEO,
            RenderMode.VIDEO to RenderMode.VIDEO_AUDIO,
            RenderMode.VIDEO_AUDIO to RenderMode.VIDEO,
        ).forEach { (from, target) ->
            listOf(AudioInput.PLAYBACK, AudioInput.MICROPHONE).forEach { input ->
                val calls = mutableListOf<String>()
                val policy = LocalTransitionPolicy(from).also { it.mint(1, "local-$from-$target-$input") }
                assertTrue(policy.decide(LocalTransitionRequest(1, "local-$from-$target-$input", target, false)) is TransitionDecision.Accept)
                LiveTransitionCoordinator.execute(target,
                    setForegroundTypes = { calls += "fgs" },
                    acquireNeededSources = { if (AudioSourceAdmissionPolicy.requiresNewAudioSource(from, target)) calls += "audio:$input"; if (RenderRequirements.forMode(target).video && from != RenderMode.VIDEO && from != RenderMode.VIDEO_AUDIO) calls += "video" },
                    commit = { policy.commit(target); calls += "commit" },
                )
                assertEquals("fgs", calls.first())
                assertEquals(target, policy.current())
                assertEquals(1, calls.count { it == "commit" })
                assertFalse("input transitions retain existing projection", calls.any { it == "projection" })
            }
        }
    }

    @Test fun transitionFailureDiagnosticIsBoundedAndSecretFree() {
        LocalStatusStore.reset()
        TransitionDiagnostics.report(RenderMode.VIDEO_AUDIO, IllegalStateException("https://secret.example/token"), TransitionDiagnostics.OwnedState(true, true, false, "mediaProjection|microphone"))
        val status = LocalStatusStore.snapshot()
        assertEquals("VIDEO_AUDIO", status.transitionTarget)
        assertEquals("IllegalStateException", status.transitionError)
        assertEquals("projection=true,audio=true,video=false,fgs=mediaProjection|microphone", status.transitionOwnedState)
        assertFalse(status.transitionError!!.contains("secret"))
    }

    @Test fun terminalDiagnosticCapturesFirstCauseAndTransitionOwnershipWithoutSecrets() {
        LocalStatusStore.reset(CaptureStatus.CAPTURE_ACTIVE_VIDEO_AUDIO)
        TerminalDiagnostics.capture(
            TerminalCause.UNEXPECTED_PROJECTION_REVOKE,
            requested = RenderMode.VIDEO,
            committed = RenderMode.VIDEO_AUDIO,
            epoch = 42,
            owned = TransitionDiagnostics.OwnedState(true, true, true, "mediaProjection|microphone"),
            foregroundTypes = 96,
        )
        TerminalDiagnostics.capture(
            TerminalCause.EXPLICIT_STOP,
            requested = RenderMode.AUDIO,
            committed = RenderMode.AUDIO,
            epoch = 43,
            owned = TransitionDiagnostics.OwnedState(false, false, false, "dataSync"),
            foregroundTypes = 1,
        )

        val status = LocalStatusStore.snapshot()
        assertEquals("unexpected_projection_revoke", status.terminalCause)
        assertEquals("VIDEO", status.terminalRequestedMode)
        assertEquals("VIDEO_AUDIO", status.terminalCommittedMode)
        assertEquals(42L, status.terminalEpoch)
        assertEquals("projection=true,audio=true,video=true,fgs=mediaProjection|microphone", status.terminalOwnedState)
        assertEquals(96, status.terminalForegroundTypes)
    }
}
