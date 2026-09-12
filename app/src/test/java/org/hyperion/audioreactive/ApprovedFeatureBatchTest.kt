package org.hyperion.audioreactive

import org.junit.Assert.*
import org.junit.Test

/** Focused regression coverage for the approved TV feature batch. */
class ApprovedFeatureBatchTest {
    @Test fun sharedFpsDrivesEveryCaptureModeAndLegacyVideoFpsCannotOverrideIt() {
        RenderMode.entries.forEach { mode ->
            val s = AudioSettings.defaults().copy(renderMode = mode, fps = 12, videoFps = 30)
            assertEquals(12, s.captureFrame().fps)
        }
        assertTrue(VideoCapturePolicy.validFps(5)); assertFalse(VideoCapturePolicy.validFps(6))
    }

    @Test fun catalogsAreExactAndSeparatedByCaptureMode() {
        assertEquals(listOf("NORMAL", "SATURATION", "CONTRAST"), VideoEffect.entries.map { it.name })
        assertEquals(listOf("BRIGHTNESS_PULSE", "BEAT_PULSE", "EQ", "COMET", "RIPPLE", "BASS_SWEEP", "SPECTRAL_BANDS", "CENTER_BEAT_BURST", "EDGE_PULSE", "STEREO_BALANCE", "FREQUENCY_GRADIENT", "BEAT_STROBE", "COMET_TRAILS", "BASS_WAVE", "VOCAL_FOCUS", "SILENCE_BREATHING", "ADAPTIVE_SHIMMER", "BEAT_COLOUR_TEMPERATURE"), VideoAudioEffect.entries.map { it.name })
        assertTrue(VideoEffectCatalog.compatible(RenderMode.VIDEO, "NORMAL"))
        assertFalse(VideoEffectCatalog.compatible(RenderMode.VIDEO, "SPECTRUM"))
    }

    @Test fun videoAudioOnlyAddsBrightnessAndSilenceIsVideoOnly() {
        val p = VideoFrameProcessor(1, 1)
        val field = VideoFrameProcessor::class.java.getDeclaredField("video").apply { isAccessible = true }
        (field.get(p) as ByteArray).also { byteArrayOf(30, 60, 120).copyInto(it) }
        val video = AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO, brightness = 1f, videoEffect = VideoEffect.CONTRAST, videoSaturationPercent = 200)
        val mixed = video.copy(renderMode = RenderMode.VIDEO_AUDIO)
        val videoOnly = p.compose(null, video).copyOf()
        val silence = p.compose(AudioFeatures(0f, 0f, 0f, 0f, 0f, 0f, signalPresent = false), mixed)
        assertArrayEquals(videoOnly, silence)
        val boosted = p.compose(AudioFeatures(1f,1f,1f,1f,1f,1f,FloatArray(16){1f},true), mixed.copy(audioBoost=.5f))
        for (i in 0..2) assertTrue((boosted[i].toInt() and 255) >= (silence[i].toInt() and 255))
    }

    @Test fun videoTreatmentsAreDistinctAndSaturationIsBounded() {
        val processor = VideoFrameProcessor(1, 1)
        val field = VideoFrameProcessor::class.java.getDeclaredField("video").apply { isAccessible = true }
        byteArrayOf(20, 80, 160.toByte()).copyInto(field.get(processor) as ByteArray)
        val base = AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO, brightness = 1f, videoSaturationPercent = 200)
        val outputs = VideoEffect.entries.map { processor.compose(null, base.copy(videoEffect = it)).copyOf().toList() }
        assertEquals(VideoEffect.entries.size, outputs.distinct().size)
        assertTrue(VideoSaturationPolicy.valid(0)); assertTrue(VideoSaturationPolicy.valid(200)); assertFalse(VideoSaturationPolicy.valid(201))
        assertTrue(TvUiStatePolicy.showVideoSaturation(RenderMode.VIDEO)); assertTrue(TvUiStatePolicy.showVideoSaturation(RenderMode.VIDEO_AUDIO)); assertFalse(TvUiStatePolicy.showVideoSaturation(RenderMode.AUDIO))
    }

    @Test fun liveParameterEditsComposeFromTheCurrentLiveValue() {
        val persisted = EffectParameters(speed = 1f, trail = .2f, beatThreshold = .3f, hueShift = 0f)
        LiveRendererSettings.begin(AudioSettings.defaults())
        try {
            LiveRendererSettings.updateParameters(persisted) { it.copy(speed = 2f) }
            LiveRendererSettings.updateParameters(persisted) { it.copy(trail = .8f) }
            LiveRendererSettings.setBrightness(0f)
            LiveRendererSettings.setSensitivity(2.5f)
            LiveRendererSettings.setVideoEffect(VideoEffect.CONTRAST)
            LiveRendererSettings.setVideoSaturationPercent(175)
            val live = LiveRendererSettings.apply(AudioSettings.defaults().copy(effectParameters = persisted))
            assertEquals(2f, live.effectParameters.speed); assertEquals(.8f, live.effectParameters.trail)
            assertEquals(0f, live.brightness); assertEquals(2.5f, live.sensitivity)
            assertEquals(VideoEffect.CONTRAST, live.videoEffect); assertEquals(175, live.videoSaturationPercent)
        } finally { LiveRendererSettings.end() }
    }

    @Test fun controlsFollowTheActiveRenderCapabilityRatherThanLocalizedLabels() {
        assertTrue(TvUiStatePolicy.showAudioControls(RenderMode.AUDIO))
        assertTrue(TvUiStatePolicy.showAudioControls(RenderMode.VIDEO_AUDIO))
        assertFalse(TvUiStatePolicy.showAudioControls(RenderMode.VIDEO))
        assertFalse(TvUiStatePolicy.showAudioControls(RenderMode.ANIMATION))
        assertTrue(TvUiStatePolicy.showVideoAudioControls(RenderMode.VIDEO_AUDIO))
        assertFalse(TvUiStatePolicy.showVideoAudioControls(RenderMode.ANIMATION))
        assertTrue(TvUiStatePolicy.showAnimationControls(RenderMode.ANIMATION))
    }


    @Test fun edgeEditsAreIndependentAndSaveRequiresExplicitRemainingZero() {
        val c = WledScreenCalibration.proportional("mac:001122334455", 8)
        val edited = WledCalibrationEditor.changeAllocation(c, ScreenEdge.BOTTOM, -1)
        assertEquals(c.right, edited.right); assertEquals(c.top, edited.top); assertEquals(c.left, edited.left)
        assertEquals(1, WledCalibrationEditor.remaining(edited)); assertFalse(edited.validFor())
        assertTrue(WledCalibrationEditor.changeAllocation(edited, ScreenEdge.BOTTOM, 1).validFor())
    }

    @Test fun perimeterMapperCoversAllFourEdgesAndDirectionChangesPacketOrder() {
        val c = WledScreenCalibration("mac:001122334455", 8, 0, PerimeterDirection.CW, 2,2,2,2, depthPercent=25, samplesPerEdge=4, gamma=1f, brightnessLimit=1f)
        val frame = ByteArray(8 * 8 * 3)
        fun put(x:Int,y:Int,r:Int,g:Int,b:Int) { val i=(y*8+x)*3; frame[i]=r.toByte();frame[i+1]=g.toByte();frame[i+2]=b.toByte() }
        for (x in 0 until 8) { put(x,7,255,0,0); put(x,0,0,0,255) }
        for (y in 0 until 8) { put(7,y,0,255,0); put(0,y,255,255,0) }
        val cw=WledPerimeterMapper(SourceFrameSpec(8,8,20),c).map(frame).copyOf()
        val ccw=WledPerimeterMapper(SourceFrameSpec(8,8,20),c.copy(direction=PerimeterDirection.CCW)).map(frame).copyOf()
        assertFalse(cw.contentEquals(ccw)); assertTrue(cw.any { (it.toInt() and 255) > 0 })
        assertEquals(24, cw.size)
    }
}
