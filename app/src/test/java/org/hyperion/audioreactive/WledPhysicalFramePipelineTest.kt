package org.hyperion.audioreactive

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production 2D mapper and packet formatter, never just the source strip. */
class WledPhysicalFramePipelineTest {
    private val spec = SourceFrameSpec(64, 32, 20)
    private val parameters = EffectParameters(speed = 1.7f, trail = .72f)

    private fun features(frame: Int) = AudioFeatures(
        rms = .68f, peak = .94f, onset = if (frame % 2 == 0) .86f else .28f,
        bass = .82f - frame * .07f, mid = .56f + frame * .04f, treble = .72f - frame * .05f,
        bands = FloatArray(AudioFeatures.BAND_COUNT) { ((it * 5 + frame * 3) % 15 + 1) / 16f },
        signalPresent = true, beatSequence = (frame + 1).toLong(), beatStrength = .9f,
        beatTimestampNanos = 1_000_000_000L + frame * 90_000_000L,
        spectralCentroid = .58f, spectralFlux = .72f,
    )

    private fun calibration(leds: Int) = WledScreenCalibration(
        identity = "mac:AABBCCDDEEFF", physicalLedCount = leds, startPixel = leds / 7,
        direction = if (leds == 64) PerimeterDirection.CCW else PerimeterDirection.CW,
        bottom = leds * 31 / 100, right = leds * 21 / 100, top = leds * 29 / 100,
        left = leds - (leds * 31 / 100 + leds * 21 / 100 + leds * 29 / 100),
        bottomInsetPercent = 4, rightInsetPercent = 7, topInsetPercent = 3, leftInsetPercent = 6,
        depthPercent = 12, samplesPerEdge = 32, gamma = 2.35f, brightnessLimit = .82f,
    ).also { assertTrue(it.validFor()) }

    private fun physicalFrames(effect: Effect, leds: Int): List<ByteArray> {
        val renderer = AudioToFullFrameRenderer(spec)
        val mapper = WledPerimeterMapper(spec, calibration(leds))
        return (0..3).map { frame -> mapper.map(renderer.render(effect, features(frame), 1f, frame.toLong(), parameters)).copyOf() }
    }

    @Test fun calibratedPhysicalFramesKeepReportedFamiliesDistinctAndMovingAt16_64_and_144Leds() {
        val families = listOf(Effect.BASS_CHASE, Effect.RUNNING_SPARKS, Effect.METEOR_TRAILS, Effect.RAINBOW, Effect.COLOR_WAVES, Effect.PRISM, Effect.AURORA, Effect.NEON)
        listOf(16, 64, 144).forEach { leds ->
            val signatures: List<List<Byte>> = families.map { effect -> physicalFrames(effect, leds).flatMap { frame -> frame.asList() } }
            assertEquals("physical $leds LED signatures", families.size, signatures.distinct().size)
            families.zip(signatures).forEach { (effect, signature) ->
                val frameBytes = leds * 3
                assertTrue("$effect must light calibrated $leds LED layout", signature.any { it != 0.toByte() })
                assertFalse("$effect must move after physical mapping at $leds LEDs", signature.subList(0, frameBytes) == signature.subList(frameBytes, frameBytes * 2))
            }
        }
    }

    @Test fun nativeMappedAnd_resampledRealtimePacketsCarryPhysicalMotion() {
        val native = physicalFrames(Effect.METEOR_TRAILS, 144)
        val nativePackets = WledRealtimePackets(InetAddress.getLoopbackAddress(), 21324, 144, 144)
        nativePackets.updateRealtime(native[0])
        val firstNative = nativePackets.realtime.data.copyOf()
        nativePackets.updateRealtime(native[1])
        assertEquals(2, firstNative[0].toInt()); assertEquals(2, firstNative[1].toInt())
        assertFalse(firstNative.contentEquals(nativePackets.realtime.data))

        val legacySource = WledSourceFrame(spec, 16)
        val reducedPackets = WledRealtimePackets(InetAddress.getLoopbackAddress(), 21324, 64, 16)
        val renderer = AudioToFullFrameRenderer(spec)
        val first = reducedPackets.apply { updateRealtime(legacySource.write(renderer.render(Effect.BASS_CHASE, features(0), 1f, 0, parameters))) }.realtime.data.copyOf()
        reducedPackets.updateRealtime(legacySource.write(renderer.render(Effect.BASS_CHASE, features(2), 1f, 2, parameters)))
        assertEquals(2, first[0].toInt()); assertEquals(2, first[1].toInt())
        assertFalse("resampled packet must retain frame motion", first.contentEquals(reducedPackets.realtime.data))
    }
}
