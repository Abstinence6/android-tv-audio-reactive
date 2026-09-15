package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAnimationRenderLoopTest {
    @Test fun animationNeedsNoProjectionAndSendsOnTheAdmittedRouterBeforeAndAfterLocalCaptureTransition() {
        val admitted = AudioSettings.defaults().copy(renderMode = RenderMode.ANIMATION, brightness = 1f)
        val frame = admitted.liveCaptureFrame()
        val output = RecordingHyperion()
        val router = OutputRouter.forTest(OutputMode.HYPERION, output)
        val renderer = AnimationFrameRenderer(frame)
        val transitions = LocalTransitionPolicy(RenderMode.ANIMATION)

        router.start()
        LiveRendererSettings.begin(admitted)
        try {
            fun sendAnimation(tick: Long) {
                val settings = LiveRendererSettings.apply(admitted)
                assertEquals(RenderMode.ANIMATION, settings.renderMode)
                assertFalse(LiveRenderLoopPolicy.requiresProjection(settings.renderMode))
                router.send(renderer.render(settings.animationEffect, settings.animationColour, settings.brightness, tick, settings.effectParameters).copyOf())
            }

            sendAnimation(0)
            transitions.mint(1, "visible-consent")
            assertTrue(transitions.decide(LocalTransitionRequest(1, "visible-consent", RenderMode.VIDEO, true)) is TransitionDecision.Accept)
            transitions.commit(RenderMode.VIDEO)
            assertTrue(LiveRendererSettings.commitRenderMode(RenderMode.VIDEO))
            assertTrue(LiveRenderLoopPolicy.requiresProjection(LiveRendererSettings.apply(admitted).renderMode))

            transitions.mint(2, "back-to-animation")
            assertTrue(transitions.decide(LocalTransitionRequest(2, "back-to-animation", RenderMode.ANIMATION, false)) is TransitionDecision.Accept)
            transitions.commit(RenderMode.ANIMATION)
            assertTrue(LiveRendererSettings.commitRenderMode(RenderMode.ANIMATION))
            sendAnimation(1)
        } finally {
            LiveRendererSettings.end()
            router.stop()
        }

        assertEquals(listOf(frame.bytes, frame.bytes), output.frameSizes)
        assertFalse(output.frames[0].contentEquals(output.frames[1]))
    }

    private class RecordingHyperion : HyperionOutput {
        val frameSizes = mutableListOf<Int>()
        val frames = mutableListOf<ByteArray>()
        override fun register() = Unit
        override fun send(frame: ByteArray) { frameSizes += frame.size; frames += frame.copyOf() }
        override fun clear() = Unit
        override fun close() = Unit
    }
}
