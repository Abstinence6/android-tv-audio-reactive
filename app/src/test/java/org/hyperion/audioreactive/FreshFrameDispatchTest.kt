package org.hyperion.audioreactive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshFrameDispatchTest {
    @Test fun noFreshFrameDoesNotRenderOrSend() {
        var rendered = 0
        var sent = 0
        assertEquals(FreshFrameDispatcher.Result.NO_FRESH_FRAME, FreshFrameDispatcher.dispatch<FakeFrame>(
            acquireLatest = { null }, release = { error("nothing acquired") },
            render = { rendered++; byteArrayOf(1) }, send = { sent++ },
        ))
        assertEquals(0, rendered)
        assertEquals(0, sent)
    }

    @Test fun latestFrameSourceDiscardsStaleFramesAndOnlyDispatchesItsNewestFrame() {
        val stale = FakeFrame(1)
        val latest = FakeFrame(2)
        val source = FakeLatestSource(listOf(stale, latest))
        val sent = mutableListOf<Int>()
        assertEquals(FreshFrameDispatcher.Result.SENT, FreshFrameDispatcher.dispatch(
            acquireLatest = source::acquireLatest, release = FakeFrame::close,
            render = { byteArrayOf(it.id.toByte()) }, send = { sent += it[0].toInt() },
        ))
        assertEquals(listOf(2), sent)
        assertEquals(1, stale.closes) // ImageReader.acquireLatestImage is the Android equivalent boundary.
        assertEquals(1, latest.closes)
    }

    @Test fun rejectedStaleFrameIsNotSentAndEachAcquiredFrameIsClosed() {
        val stale = FakeFrame(1)
        val current = FakeFrame(2)
        val sent = mutableListOf<Int>()
        assertEquals(FreshFrameDispatcher.Result.RENDER_REJECTED, FreshFrameDispatcher.dispatch(
            acquireLatest = { stale }, release = FakeFrame::close,
            render = { null }, send = { sent += it[0].toInt() },
        ))
        assertEquals(FreshFrameDispatcher.Result.SENT, FreshFrameDispatcher.dispatch(
            acquireLatest = { current }, release = FakeFrame::close,
            render = { byteArrayOf(it.id.toByte()) }, send = { sent += it[0].toInt() },
        ))
        assertEquals(listOf(2), sent)
        assertEquals(1, stale.closes)
        assertEquals(1, current.closes)
    }

    @Test fun rendererFailureRouterFailureAndSuccessAlwaysCloseTheAcquiredFrame() {
        val rendererFailure = FakeFrame(1)
        try {
            FreshFrameDispatcher.dispatch(acquireLatest = { rendererFailure }, release = FakeFrame::close,
                render = { throw IllegalStateException("render") }, send = {})
        } catch (_: IllegalStateException) {}
        val routerFailure = FakeFrame(2)
        try {
            FreshFrameDispatcher.dispatch(acquireLatest = { routerFailure }, release = FakeFrame::close,
                render = { byteArrayOf(2) }, send = { throw IllegalStateException("router") })
        } catch (_: IllegalStateException) {}
        val success = FakeFrame(3)
        assertEquals(FreshFrameDispatcher.Result.SENT, FreshFrameDispatcher.dispatch(
            acquireLatest = { success }, release = FakeFrame::close,
            render = { byteArrayOf(3) }, send = {},
        ))
        assertEquals(1, rendererFailure.closes)
        assertEquals(1, routerFailure.closes)
        assertEquals(1, success.closes)
    }

    @Test fun audioExpansionAndActualRouterAcceptStableVideoShapeAcrossLiveTransitions() {
        val spec = SourceFrameSpec(64, 36, 20)
        val features = AudioFeatures(.5f, .7f, .4f, .3f, .2f, .1f, FloatArray(AudioFeatures.BAND_COUNT) { .6f }, true)
        val audioRenderer = AudioToFullFrameRenderer(spec)
        val audio = audioRenderer.render(Effect.SPECTRUM, features, .8f, 3)
        assertEquals(spec.bytes, audio.size)
        assertSame(audio, audioRenderer.render(Effect.SPECTRUM, features, .8f, 4))
        for (y in 1 until spec.height) assertArrayEquals(audio.copyOfRange(0, spec.width * 3), audio.copyOfRange(y * spec.width * 3, (y + 1) * spec.width * 3))
        val video = ByteArray(spec.bytes) { (it * 13).toByte() }
        val videoAudio = ByteArray(spec.bytes) { (it * 29).toByte() }

        val nativeWled = FakeWled(native = true)
        val reducedWled = FakeWled(native = false)
        val wled = OutputRouter.forTest(OutputMode.WLED, wled = arrayOf(nativeWled, reducedWled), wledSource = WledSourceFrame(spec, 16))
        wled.start()
        listOf(video, videoAudio, audio).forEach(wled::send) // VIDEO -> VIDEO_AUDIO -> AUDIO
        wled.stop()
        assertEquals(listOf(spec.bytes, spec.bytes, spec.bytes), nativeWled.shapes)
        assertEquals(listOf(48, 48, 48), reducedWled.shapes)

        val hyperion = FakeHyperion()
        val hyperionRouter = OutputRouter.forTest(OutputMode.HYPERION, hyperion)
        hyperionRouter.start()
        listOf(video, videoAudio, audio).forEach(hyperionRouter::send) // VIDEO -> VIDEO_AUDIO -> AUDIO
        hyperionRouter.stop()
        assertEquals(listOf(spec.bytes, spec.bytes, spec.bytes), hyperion.shapes)
        assertTrue(hyperion.cleared)
    }

    @Test fun imageReaderPolicyRemainsBoundedToTwoImages() {
        assertEquals(2, VideoLatencyPolicy.IMAGE_READER_MAX_IMAGES)
    }

    private class FakeFrame(val id: Int) { var closes = 0; fun close() { closes++ } }
    private class FakeLatestSource(private val queued: List<FakeFrame>) {
        fun acquireLatest(): FakeFrame? {
            if (queued.isEmpty()) return null
            queued.dropLast(1).forEach(FakeFrame::close)
            return queued.last()
        }
    }
    private class FakeWled(private val native: Boolean) : WledOutput {
        val shapes = mutableListOf<Int>()
        override fun requiresNativeFrame() = native
        override fun send(sourceFrame: ByteArray) { shapes += sourceFrame.size }
        override fun blackout() = Unit
        override fun close() = Unit
    }
    private class FakeHyperion : HyperionOutput {
        val shapes = mutableListOf<Int>()
        var cleared = false
        override fun register() = Unit
        override fun send(frame: ByteArray) { shapes += frame.size }
        override fun clear() { cleared = true }
        override fun close() = Unit
    }
}
