package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VisualDistinctnessRuntimeTest {
    private fun features(frame: Int) = AudioFeatures(
        rms = .62f, peak = .88f, onset = if (frame == 1) .92f else .18f,
        bass = .72f - frame * .08f, mid = .48f + frame * .06f, treble = .30f + frame * .12f,
        bands = FloatArray(AudioFeatures.BAND_COUNT) { index -> ((index + frame * 3) % 9 + 1) / 10f },
        signalPresent = true, beatSequence = frame.toLong(), beatStrength = .8f,
        beatTimestampNanos = 1_000_000_000L + frame * 100_000_000L,
        spectralCentroid = .2f + frame * .16f, spectralFlux = .25f + frame * .12f,
    )

    @Test fun formerlyCollidingAudioFamiliesHaveDistinctMultiFrameMotionSignatures() {
        val candidates = listOf(
            Effect.BASS_CHASE, Effect.RUNNING_SPARKS, Effect.METEOR_TRAILS,
            Effect.COLOR_WAVES, Effect.PRISM, Effect.AURORA, Effect.NEON, Effect.RAINBOW,
        )
        val signatures = candidates.map { effect ->
            val renderer = EffectFrameRenderer(64)
            (0L..3L).flatMap { frame -> renderer.render(effect, features(frame.toInt()), 1f, frame, EffectParameters(speed = 1.5f, trail = .7f)).toList() }
        }
        assertEquals(candidates.size, signatures.distinct().size)
        assertFalse("a moving effect must not collapse to its first frame", signatures[0].subList(0, 192) == signatures[0].subList(192, 384))
    }

    @Test fun wledReducedAndNativeRoutesKeepDifferentStripPixelsAndTemporalMovement() {
        val source = ByteArray(64 * 3) { index -> ((index * 37) and 255).toByte() }
        val reduced = ByteArray(2 + 24 * 3)
        resampleTo(source, 64, reduced, 2, 24)
        val native = ByteArray(2 + 144 * 3)
        resampleTo(source, 64, native, 2, 144)
        assertEquals(24, (0 until 24).map { reduced[2 + it * 3] }.distinct().size)
        assertEquals(64, (0 until 144).map { native[2 + it * 3] }.distinct().size)
        val next = source.copyOf().also { it.rotateLeft(9) }
        val nextReduced = ByteArray(reduced.size)
        resampleTo(next, 64, nextReduced, 2, 24)
        assertFalse(reduced.contentEquals(nextReduced))
    }

    private fun ByteArray.rotateLeft(bytes: Int) {
        val copy = copyOf()
        for (index in indices) this[index] = copy[(index + bytes) % size]
    }
}
