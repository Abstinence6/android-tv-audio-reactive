package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAudioWledMappedOutputTest {
    private val spec = SourceFrameSpec(64, 32, 20)
    private val calibration = WledScreenCalibration("mac:AABBCCDDEEFF", 144, 19, PerimeterDirection.CCW, 45, 30, 42, 27, 3, 6, 4, 5, 14, 32, 2.2f, .85f)
    private val settings = AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO_AUDIO, brightness = 1f, audioBoost = 1f)

    private fun fixture(bright: Boolean) = VideoFrameProcessor(spec.width, spec.height).also { processor ->
        val video = VideoFrameProcessor::class.java.getDeclaredField("video").apply { isAccessible = true }.get(processor) as ByteArray
        for (y in 0 until spec.height) for (x in 0 until spec.width) {
            val at = (y * spec.width + x) * 3
            val lift = if (bright) 150 else 20
            video[at] = (lift + x * 3 % 105).coerceAtMost(255).toByte()
            video[at + 1] = (lift / 2 + y * 6 % 145).coerceAtMost(255).toByte()
            video[at + 2] = (lift / 3 + (x + y) * 2 % 180).coerceAtMost(255).toByte()
        }
    }
    private fun audio(frame: Int) = AudioFeatures(.78f, .96f, if (frame == 1) .95f else .42f, .82f, .58f, .76f, FloatArray(16) { ((it + frame * 5) % 16 + 1) / 16f }, true, (frame + 1).toLong(), 1f, 1_000_000_000L + frame * 100_000_000L, 124f, 1f, .45f, .66f, .84f)

    @Test fun colorfulAndBrightVideoAccentsRemainDistinctAfterCalibratedPhysicalMapping() {
        assertTrue(calibration.validFor())
        listOf(false, true).forEach { bright ->
            val signatures = VideoAudioEffectCatalogue.visible.map { effect ->
                val processor = fixture(bright)
                val mapper = WledPerimeterMapper(spec, calibration)
                (0..2).flatMap { frame -> mapper.map(processor.compose(audio(frame), settings.copy(videoAudioEffect = effect), 1_000_000_000L + frame * 100_000_000L)).asList() }
            }
            assertEquals(VideoAudioEffectCatalogue.visible.size, signatures.distinct().size)
            signatures.forEach { signature -> assertTrue(signature.any { it != 0.toByte() }) }
        }
    }

    @Test fun silentVideoAudioMapsExactlyLikeBaseVideo() {
        val silent = AudioFeatures(0f, 0f, 0f, 0f, 0f, 0f, FloatArray(16), false)
        val base = fixture(true)
        val accented = fixture(true)
        val mapper = WledPerimeterMapper(spec, calibration)
        val expected = mapper.map(base.compose(null, settings.copy(renderMode = RenderMode.VIDEO))).copyOf()
        val actual = mapper.map(accented.compose(silent, settings.copy(videoAudioEffect = VideoAudioEffect.COMET))).copyOf()
        assertFalse(expected.isEmpty())
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
