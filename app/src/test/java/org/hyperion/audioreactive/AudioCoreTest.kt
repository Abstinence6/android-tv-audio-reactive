package org.hyperion.audioreactive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class AudioCoreTest {
    @Test fun idleToggleStartsOnlyTheExistingPermissionAndProjectionFlow() =
        assertEquals(CaptureTogglePolicy.Action.REQUEST_RECORD_AUDIO, CaptureTogglePolicy.actionFor(false, false))

    @Test fun activeToggleStopsOnlyAnExistingService() {
        assertEquals(CaptureTogglePolicy.Action.STOP_EXISTING, CaptureTogglePolicy.actionFor(true, false))
        assertTrue(ServiceStopPolicy.shouldStopExistingService(true))
    }

    @Test fun idleStopDoesNotDispatchAServiceStartOrStop() = assertFalse(ServiceStopPolicy.shouldStopExistingService(false))

    private val typicalFeatures = AudioFeatures(.65f, .85f, .62f, .72f, .52f, .42f,
        FloatArray(AudioFeatures.BAND_COUNT) { .18f + (it % 5) * .16f })

    @Test fun everyEffectWritesBoundedRgb24Frame() {
        Effect.entries.forEach { effect ->
            val frame = EffectRenderer.renderImage(effect, typicalFeatures, .8f, 99)
            assertEquals("$effect", 16 * 3, frame.size)
            frame.forEach { channel -> assertTrue("$effect channel=$channel", (channel.toInt() and 255) in 0..255) }
        }
    }

    @Test fun nonMonochromeEffectsContainMultipleColorsAtTypicalAudioFeatures() {
        Effect.entries.filter { it != Effect.MONOCHROME }.forEach { effect ->
            val frame = EffectRenderer.renderImage(effect, typicalFeatures, .8f, 99)
            assertTrue("$effect was not spatially multicolor", pixels(frame).toSet().size > 1)
        }
    }

    @Test fun statefulEffectsMoveAcrossFramesAndResetDeterministically() {
        val moving = listOf(Effect.BASS_PULSE, Effect.BASS_CHASE, Effect.RUNNING_SPARKS, Effect.METEOR_TRAILS, Effect.BEAT_EXPLOSION, Effect.FIRE, Effect.EMBERS, Effect.FIREFLIES, Effect.COLOR_WAVES, Effect.BLURZ_TRAILS, Effect.WATERFALL, Effect.BEAT_RIPPLE)
        moving.forEach { effect ->
            val renderer = EffectFrameRenderer()
            val first = renderer.render(effect, typicalFeatures, .9f, 20).copyOf()
            val second = renderer.render(effect, typicalFeatures, .9f, 27).copyOf()
            assertFalse("$effect did not move", first.contentEquals(second))
            renderer.reset()
            assertTrue("$effect reset was not deterministic", first.contentEquals(renderer.render(effect, typicalFeatures, .9f, 20).copyOf()))
        }
    }

    @Test fun formerlyAliasedEffectsProduceDistinctFramesForTheSameFeatures() {
        val pairs = listOf(Effect.SPECTRUM to Effect.MEL_SPECTRUM, Effect.PULSE to Effect.BASS_PULSE, Effect.FIRE to Effect.EMBERS)
        pairs.forEach { (first, second) ->
            val left = EffectRenderer.renderImage(first, typicalFeatures, .8f, 99)
            val right = EffectRenderer.renderImage(second, typicalFeatures, .8f, 99)
            assertFalse("$first and $second remain aliases", left.contentEquals(right))
        }
    }

    @Test fun reusableRendererReturnsOneValidRgb24BufferAcrossFrames() {
        val renderer = EffectFrameRenderer()
        val first = renderer.render(Effect.BEAT_RIPPLE, typicalFeatures, .8f, 99)
        val second = renderer.render(Effect.BEAT_RIPPLE, typicalFeatures, .8f, 100)
        assertSame("capture renderer must reuse its frame buffer", first, second)
        assertEquals(48, second.size)
        second.forEach { assertTrue((it.toInt() and 255) in 0..255) }
    }

    @Test fun monochromeIsDeliberatelySingleColor() {
        val frame = EffectRenderer.renderImage(Effect.MONOCHROME, typicalFeatures, .8f, 99)
        assertEquals(1, pixels(frame).toSet().size)
        assertTrue(pixels(frame).first().let { it[0] == it[1] && it[1] == it[2] })
    }

    @Test fun analyzerHasSixteenBoundedBandsOnsetAndReset() {
        val analyzer = PcmAnalyzer()
        val pulse = ShortArray(1024) { i -> (sin(2.0 * Math.PI * 440.0 * i / 48_000.0) * 24_000).toInt().toShort() }
        val features = analyzer.analyze(pulse, 2f)
        assertEquals(AudioFeatures.BAND_COUNT, features.bands.size)
        assertTrue(features.bands.all { it.isFinite() && it in 0f..1f })
        assertTrue(features.onset in 0f..1f)
        analyzer.reset()
        val silence = analyzer.analyze(ShortArray(256), 1f)
        assertEquals(0f, silence.rms)
        assertTrue(silence.bands.all { it == 0f })
    }

    @Test fun analyzerBoundsInvalidCountAndSensitivityWithoutNan() {
        val samples = ShortArray(64) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val features = PcmAnalyzer().analyze(samples, 9999, Float.POSITIVE_INFINITY)
        listOf(features.rms, features.peak, features.onset, features.bass, features.mid, features.treble).forEach { assertTrue(it.isFinite() && it in 0f..1f) }
    }

    @Test fun analyzerReusesFeatureContainerAcrossCaptureFrames() {
        val analyzer = PcmAnalyzer()
        val first = analyzer.analyze(ShortArray(128), 1f)
        val second = analyzer.analyze(ShortArray(128) { Short.MAX_VALUE }, 1f)
        assertSame(first, second)
        assertSame(first.bands, second.bands)
    }

    @Test fun perBandNormalizationKeepsTrebleVisibleAfterLoudBass() {
        val analyzer = PcmAnalyzer()
        val loudBass = tone(80.0, 28_000)
        repeat(4) { analyzer.analyze(loudBass, 1f) }
        val treble = analyzer.analyze(tone(7_830.0, 6_000), 1f)
        assertTrue("treble=${treble.treble} after bass=${treble.bass}", treble.treble > .05f)
        assertTrue(treble.bands.all { it.isFinite() && it in 0f..1f })
    }

    @Test fun onsetIsBoundedDebouncedAndClearedBySilence() {
        val analyzer = PcmAnalyzer()
        val hit = tone(165.0, 26_000)
        val onsets = FloatArray(5) { analyzer.analyze(hit, 1f).onset }
        assertTrue(onsets.all { it.isFinite() && it in 0f..1f })
        assertEquals("one sustained hit must not retrigger in its refractory window", 1, onsets.count { it > 0f })
        repeat(5) {
            val silence = analyzer.analyze(ShortArray(1_024), 1f)
            assertFalse(silence.signalPresent)
            assertEquals(0f, silence.onset)
        }
    }

    @Test fun gatedSilenceAlwaysBlackAndClearsBeatRippleState() {
        val renderer = EffectFrameRenderer()
        renderer.render(Effect.BEAT_RIPPLE, typicalFeatures, 1f, 10)
        val silence = AudioFeatures(0f, 0f, 0f, 0f, 0f, 0f)
        listOf(Effect.NEON, Effect.RAINBOW, Effect.BEAT_RIPPLE).forEach { effect ->
            val first = renderer.render(effect, silence, 1f, 11)
            val second = renderer.render(effect, silence, 1f, 12)
            assertTrue("$effect glowed during gated silence", first.all { it == 0.toByte() })
            assertTrue("$effect retained a false beat during silence", second.all { it == 0.toByte() })
            assertSame(first, second)
        }
    }

    @Test fun zeroBrightnessProducesAnImmediateBlackAudioFrame() {
        val frame = EffectFrameRenderer().render(Effect.SPECTRUM, typicalFeatures, 0f, 1)
        assertTrue(frame.all { it == 0.toByte() })
        val smoother = RgbFrameSmoother(frame.size)
        smoother.apply(ByteArray(frame.size) { 100.toByte() })
        assertTrue(smoother.apply(frame, FrameSmoothingPolicy.immediateBlack(0f, typicalFeatures.signalPresent)).all { it == 0.toByte() })
    }

    @Test fun zeroBrightnessImmediatelyClearsPrefilledSmoothersForAudioAndVideo() {
        val audio = RgbFrameSmoother(3).also { it.apply(byteArrayOf(90, 90, 90)) }
        val video = RgbFrameSmoother(3).also { it.apply(byteArrayOf(90, 90, 90)) }
        val black = byteArrayOf(0, 0, 0)
        assertTrue(audio.apply(black, FrameSmoothingPolicy.immediateBlack(0f, true)).all { it == 0.toByte() })
        assertTrue(video.apply(black, FrameSmoothingPolicy.immediateBlack(0f, null)).all { it == 0.toByte() })
    }

    @Test fun analyzerAndRendererKeepReusableCaptureBuffersAfterWarmup() {
        val analyzer = PcmAnalyzer()
        val renderer = EffectFrameRenderer()
        val pcm = tone(960.0, 18_000)
        val features = analyzer.analyze(pcm, 1f)
        val bands = features.bands
        val pixels = renderer.render(Effect.MEL_SPECTRUM, features, .8f, 1)
        repeat(8) { tick ->
            val next = analyzer.analyze(pcm, 1f)
            assertSame(features, next)
            assertSame(bands, next.bands)
            assertSame(pixels, renderer.render(Effect.MEL_SPECTRUM, next, .8f, tick.toLong() + 2))
        }
    }

    @Test fun smootherBoundsRapidChannelChangesAndClearsBlackImmediately() {
        val smoother = RgbFrameSmoother(3)
        assertTrue(smoother.apply(byteArrayOf(0, 0, 0)).all { it == 0.toByte() })
        val flash = smoother.apply(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte()))
        assertTrue("flash must be bounded", flash.all { (it.toInt() and 255) in 1..72 })
        val falling = smoother.apply(byteArrayOf(0, 0, 0))
        assertTrue("release must be bounded", falling.all { (it.toInt() and 255) in 1..71 })
        assertTrue(smoother.apply(byteArrayOf(0, 0, 0), immediateBlack = true).all { it == 0.toByte() })
    }

    @Test fun rendererBoundsOneDimensionalOutputWidths() {
        assertEquals(48, EffectFrameRenderer(16).render(Effect.SPECTRUM, typicalFeatures, .8f, 1).size)
        assertEquals(384, EffectFrameRenderer(128).render(Effect.SPECTRUM, typicalFeatures, .8f, 1).size)
        assertFails { EffectFrameRenderer(15) }
        assertFails { EffectFrameRenderer(17) }
        assertFails { EffectFrameRenderer(513) }
    }

    @Test fun fullCurrentOfficialWledOneDimensionalReferenceCatalogIsPresentAndRendersIndependently() {
        val required = setOf(
            "Ripple Peak", "Gravcenter", "Gravcentric", "Gravimeter", "Gravfreq", "Juggles",
            "Matripix", "Midnoise", "Noisefire", "Noisemeter", "Pixelwave", "Plasmoid",
            "Puddlepeak", "Puddles", "Pixels", "Blurz", "DJ Light", "Freqmap", "Freqmatrix",
            "Freqpixels", "Freqwave", "Noisemove", "Rocktaves", "Waterfall"
        )
        val referenceStyles = Effect.entries.filter { it.wled1dReferenceStyle }
        assertEquals(required, referenceStyles.map { it.label }.toSet())
        // WLED main's current FX.cpp registers 24 official 1D AudioReactive routines;
        // its remaining AudioReactive entries are 2D or optional particle effects.
        assertEquals(24, referenceStyles.size)
        assertTrue(referenceStyles.all { effect ->
            val frame = EffectRenderer.renderImage(effect, typicalFeatures, .8f, 99)
            frame.size == 48 && frame.any { (it.toInt() and 255) > 0 }
        })
        val firstFrames = referenceStyles.map { EffectRenderer.renderImage(it, typicalFeatures, .8f, 99).toList() }
        assertTrue("reference styles must not collapse into one renderer output", firstFrames.toSet().size >= 18)
    }

    @Test fun expandedCatalogProvidesTenAdditionalDistinctAppSideEffects() {
        val added = listOf(Effect.JUGGLE, Effect.PRISM, Effect.BASS_GRADIENT, Effect.WAVE_BANDS, Effect.SCANNER, Effect.PENDULUM, Effect.STARFIELD, Effect.PLASMA, Effect.MUSIC_BOX, Effect.EQUALIZER_SWEEP)
        assertEquals(10, added.size)
        assertEquals(added.size, added.toSet().size)
        assertTrue(added.map { EffectRenderer.renderImage(it, typicalFeatures, .8f, 99).toList() }.toSet().size >= 8)
    }

    private fun pixels(frame: ByteArray): List<List<Int>> = (0 until frame.size step 3).map { listOf(frame[it].toInt() and 255, frame[it + 1].toInt() and 255, frame[it + 2].toInt() and 255) }

    @Test fun silenceAndDcAreNoiseGatedAfterDcRemoval() {
        val analyzer = PcmAnalyzer()
        val silence = analyzer.analyze(ShortArray(256), 1f)
        val dc = analyzer.analyze(ShortArray(256) { 1_000 }, 1f)
        assertEquals(0f, silence.rms); assertEquals(0f, silence.peak); assertEquals(0f, dc.rms)
    }

    @Test fun bassToneHasHigherBassBandThanTrebleBand() {
        val samples = ShortArray(1_024) { i -> (sin(2.0 * Math.PI * 80.0 * i / 48_000.0) * 20_000).toInt().toShort() }
        val features = PcmAnalyzer().analyze(samples, 1f)
        assertTrue("bass=${features.bass}, treble=${features.treble}", features.bass > features.treble)
    }

    private fun tone(frequency: Double, amplitude: Int): ShortArray = ShortArray(1_024) { i ->
        (sin(2.0 * Math.PI * frequency * i / 48_000.0) * amplitude).toInt().toShort()
    }

    @Test fun registerAndClearMatchSchemaPriorityOriginAndFraming() {
        val register = HyperionFlatbuffer.register("x")
        val (request, command) = decodeRequest(register, 4)
        assertEquals(2, request.fieldCount); assertEquals(2, command.fieldCount)
        assertEquals("x", command.stringField(0)); assertEquals(101, command.intField(1))
        val framed = HyperionFlatbuffer.frame(register)
        assertEquals(register.size, ByteBuffer.wrap(framed, 0, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertTrue(register.contentEquals(framed.copyOfRange(4, framed.size)))
        val (_, clear) = decodeRequest(HyperionFlatbuffer.clear(), 3)
        assertEquals(1, clear.fieldCount); assertEquals(101, clear.intField(0))
    }

    @Test fun rawImageMatchesSchemaAndRgbByteCount() {
        val rgb = ByteArray(16 * 3) { it.toByte() }
        val payload = HyperionFlatbuffer.rawImage(rgb, 16, 1)
        val framed = HyperionFlatbuffer.frame(payload)
        assertEquals(payload.size, ByteBuffer.wrap(framed, 0, 4).order(ByteOrder.BIG_ENDIAN).int)
        val (request, image) = decodeRequest(framed.copyOfRange(4, framed.size), 2)
        assertEquals(2, request.fieldCount); assertEquals(3, image.fieldCount); assertEquals(1, image.byteField(0)); assertEquals(-1, image.intField(2))
        val raw = image.tableField(1)
        assertEquals(3, raw.fieldCount); assertEquals(16, raw.intField(1)); assertEquals(1, raw.intField(2)); assertTrue(rgb.contentEquals(raw.vectorField(0)))
    }

    @Test fun rawImageAcceptsAndDecodesMaximumHyperionCeilingAndRejectsOversizedDimensions() {
        val rgb = ByteArray(320 * 180 * 3) { (it * 31).toByte() }
        val payload = HyperionFlatbuffer.rawImage(rgb, 320, 180)
        val (_, image) = decodeRequest(payload, 2)
        val raw = image.tableField(1)
        assertEquals(320, raw.intField(1)); assertEquals(180, raw.intField(2)); assertTrue(rgb.contentEquals(raw.vectorField(0)))
        assertFails { HyperionFlatbuffer.rawImage(ByteArray(321 * 180 * 3), 321, 180) }
        assertFails { HyperionFlatbuffer.rawImage(ByteArray(320 * 181 * 3), 320, 181) }
        assertFails { HyperionFlatbuffer.rawImage(ByteArray(47), 16, 1) }
    }

    @Test fun maximumRawImageFrameReusesBufferHasBigEndianPrefixAndPreservesRgbData() {
        val writer = HyperionFlatbuffer.RawImageFrame(320, 180)
        val first = writer.write(ByteArray(320 * 180 * 3) { it.toByte() })
        val secondRgb = ByteArray(320 * 180 * 3) { (it * 17).toByte() }
        val second = writer.write(secondRgb)
        assertSame(first, second)
        val expectedPayloadSize = 84 + 320 * 180 * 3
        assertArrayEquals(byteArrayOf((expectedPayloadSize ushr 24).toByte(), (expectedPayloadSize ushr 16).toByte(), (expectedPayloadSize ushr 8).toByte(), expectedPayloadSize.toByte()), second.copyOfRange(0, 4))
        assertEquals(expectedPayloadSize, ByteBuffer.wrap(second, 0, 4).order(ByteOrder.BIG_ENDIAN).int)
        val (_, image) = decodeRequest(second.copyOfRange(4, second.size), 2)
        assertTrue(secondRgb.contentEquals(image.tableField(1).vectorField(0)))
    }

    private fun assertFails(block: () -> Unit) { try { block(); throw AssertionError("Expected failure") } catch (_: IllegalArgumentException) {} }
    private fun decodeRequest(bytes: ByteArray, expectedType: Int): Pair<FlatbufferTable, FlatbufferTable> {
        val request = FlatbufferReader(bytes).root(); assertEquals(expectedType, request.byteField(0)); return request to request.tableField(1)
    }
    private class FlatbufferReader(bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun root() = table(buffer.getInt(0))
        fun table(at: Int): FlatbufferTable { val v = at - buffer.getInt(at); return FlatbufferTable(this, at, (unsignedShort(v) - 4) / 2, unsignedShort(v + 2), IntArray((unsignedShort(v) - 4) / 2) { unsignedShort(v + 4 + it * 2) }) }
        fun int(at: Int) = buffer.getInt(at); fun byte(at: Int) = buffer.get(at).toInt() and 255
        fun string(at: Int): String { val start = at + buffer.getInt(at); val value = ByteArray(buffer.getInt(start)); buffer.position(start + 4); buffer.get(value); return value.decodeToString() }
        fun vector(at: Int): ByteArray { val start = at + buffer.getInt(at); return ByteArray(buffer.getInt(start)).also { buffer.position(start + 4); buffer.get(it) } }
        private fun unsignedShort(at: Int) = buffer.getShort(at).toInt() and 0xffff
    }
    private class FlatbufferTable(private val reader: FlatbufferReader, private val start: Int, val fieldCount: Int, val objectSize: Int, private val fields: IntArray) {
        private fun address(index: Int): Int { require(index in fields.indices && fields[index] != 0); return start + fields[index] }
        fun intField(index: Int) = reader.int(address(index)); fun byteField(index: Int) = reader.byte(address(index)); fun stringField(index: Int) = reader.string(address(index)); fun vectorField(index: Int) = reader.vector(address(index)); fun tableField(index: Int): FlatbufferTable { val at = address(index); return reader.table(at + reader.int(at)) }
    }
}
