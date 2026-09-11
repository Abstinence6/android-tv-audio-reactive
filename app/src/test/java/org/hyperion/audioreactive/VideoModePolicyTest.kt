package org.hyperion.audioreactive

import org.junit.Assert.*
import org.junit.Test

class VideoModePolicyTest {
    @Test fun checkboxStatesMapToCanonicalModes() {
        assertEquals(RenderMode.AUDIO, CaptureModeCheckboxPolicy.resolve(true, false, RenderMode.VIDEO).mode)
        assertEquals(RenderMode.VIDEO, CaptureModeCheckboxPolicy.resolve(false, true, RenderMode.AUDIO).mode)
        assertEquals(RenderMode.VIDEO_AUDIO, CaptureModeCheckboxPolicy.resolve(true, true, RenderMode.AUDIO).mode)
    }

    @Test fun noModeIsRejectedAndPreviousSelectionRestored() {
        val result = CaptureModeCheckboxPolicy.resolve(false, false, RenderMode.VIDEO)
        assertTrue(result.rejected); assertEquals(RenderMode.VIDEO, result.mode); assertTrue(result.videoChecked)
    }

    @Test fun selectorAlwaysUsesTheCurrentModeCatalogueAndCanonicalIndex() {
        val base = AudioSettings.defaults().copy(effect = Effect.FIRE, videoEffect = VideoEffect.CONTRAST, videoAudioEffect = VideoAudioEffect.BASS_SWEEP)
        val audio = base.copy(renderMode = RenderMode.AUDIO)
        val video = base.copy(renderMode = RenderMode.VIDEO)
        val mixed = base.copy(renderMode = RenderMode.VIDEO_AUDIO)
        assertEquals(Effect.FIRE.ordinal, EffectSelectorPolicy.selectedIndex(audio))
        assertEquals(VideoEffect.CONTRAST.ordinal, EffectSelectorPolicy.selectedIndex(video))
        assertEquals(VideoAudioEffect.BASS_SWEEP.ordinal, EffectSelectorPolicy.selectedIndex(mixed))
        listOf(audio, video, mixed).forEach { settings ->
            val labels = EffectSelectorPolicy.labels(settings)
            assertTrue(EffectSelectorPolicy.selectedIndex(settings) in labels.indices)
        }
    }

    @Test fun videoSaturationHasTheRequiredBoundsDefaultAndLiveMutablePolicy() {
        assertEquals(125, VideoSaturationPolicy.DEFAULT_PERCENT)
        assertEquals(VideoSaturationPolicy.DEFAULT_PERCENT, AudioSettings.defaults().videoSaturationPercent)
        assertTrue(VideoSaturationPolicy.valid(0)); assertTrue(VideoSaturationPolicy.valid(200)); assertFalse(VideoSaturationPolicy.valid(201))
        assertTrue(VideoSaturationPolicy.mutable(false)); assertTrue(VideoSaturationPolicy.mutable(true))
        assertFalse(AudioSettings.defaults().copy(videoSaturationPercent = 201).valid())
    }

    @Test fun baseVideoColourTreatmentIsAvailableForVideoAndVideoAudioSeparatelyFromAudioEffects() {
        val mixed = AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO_AUDIO, videoEffect = VideoEffect.CONTRAST)
        assertEquals(listOf("Normal", "Saturation", "Contrast"), VideoColourTreatmentPolicy.labels())
        assertTrue(VideoColourTreatmentPolicy.visible(RenderMode.VIDEO))
        assertTrue(VideoColourTreatmentPolicy.visible(RenderMode.VIDEO_AUDIO))
        assertFalse(VideoColourTreatmentPolicy.visible(RenderMode.AUDIO))
        assertEquals(VideoEffect.CONTRAST.ordinal, VideoColourTreatmentPolicy.selectedIndex(mixed))
        assertEquals(VideoEffect.SATURATION, VideoColourTreatmentPolicy.selection(VideoEffect.SATURATION.ordinal))
        assertNull(VideoColourTreatmentPolicy.selection(VideoEffect.entries.size))
        assertTrue(VideoColourTreatmentPolicy.mutable(false)); assertTrue(VideoColourTreatmentPolicy.mutable(true))
        assertEquals(listOf("Brightness pulse", "Beat pulse", "EQ", "Comet", "Ripple", "Bass sweep"), EffectSelectorPolicy.labels(mixed))
    }

    @Test fun videoPresetsAreDistinctTreatments() {
        val processor = processorWith(50, 100, 150)
        val base = AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO, brightness = 1f)
        val outputs = VideoEffect.entries.map { processor.compose(null, base.copy(videoEffect = it)).copyOf().toList() }
        assertEquals(VideoEffect.entries.size, outputs.distinct().size)
    }

    @Test fun zeroBrightnessMakesVideoOutputBlackWithoutChangingFrameShape() {
        val processor = processorWith(50, 100, 150)
        val output = processor.compose(null, AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO, brightness = 0f))
        assertEquals(3, output.size)
        assertTrue(output.all { it == 0.toByte() })
    }

    @Test fun videoAudioSilenceFloorPreservesNonBlackVideoAndHoldDefersZeroFloorBlackout() {
        val processor = processorWith(80, 40, 20)
        val silent = AudioFeatures(0f, 0f, 0f, 0f, 0f, 0f, FloatArray(AudioFeatures.BAND_COUNT), false)
        val base = AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO_AUDIO, brightness = .7f)
        val raw = processor.compose(silent, base.copy(videoAudioSilenceBrightnessFloor = .2f)).copyOf()
        assertTrue(raw.any { it != 0.toByte() })
        assertFalse(FrameSmoothingPolicy.immediateBlack(base.copy(videoAudioSilenceBrightnessFloor = .2f), false))
        assertFalse(FrameSmoothingPolicy.immediateBlack(base.copy(videoAudioSilenceBrightnessFloor = 0f), false))
        assertFalse(AudioSettings.defaults().copy(brightness = .4f, videoAudioSilenceBrightnessFloor = .45f).valid())
    }

    @Test fun liveInputTransitionsPreserveOneValidInputAndNeverRequireASettingsRestart() {
        val audio = CaptureModeCheckboxPolicy.resolve(true, false, RenderMode.VIDEO_AUDIO)
        val video = CaptureModeCheckboxPolicy.resolve(false, true, audio.mode)
        val both = CaptureModeCheckboxPolicy.resolve(true, true, video.mode)
        val rejected = CaptureModeCheckboxPolicy.resolve(false, false, both.mode)
        assertEquals(RenderMode.AUDIO, audio.mode); assertEquals(RenderMode.VIDEO, video.mode); assertEquals(RenderMode.VIDEO_AUDIO, both.mode)
        assertTrue(rejected.rejected); assertTrue(rejected.audioChecked); assertTrue(rejected.videoChecked)
        LiveRendererSettings.begin(AudioSettings.defaults())
        try { LiveRendererSettings.setRenderMode(RenderMode.VIDEO); assertEquals(RenderMode.VIDEO, LiveRendererSettings.apply(AudioSettings.defaults()).renderMode) } finally { LiveRendererSettings.end() }
    }

    @Test fun liveCaptureFrameIsStableAcrossInputTransitionsAndLatencyPolicySendsOnlyFreshImages() {
        val base = AudioSettings.defaults().copy(videoQuality = VideoQuality.HIGH)
        assertEquals(base.copy(renderMode = RenderMode.AUDIO).liveCaptureFrame(), base.copy(renderMode = RenderMode.VIDEO_AUDIO).liveCaptureFrame())
        assertEquals(2, VideoLatencyPolicy.IMAGE_READER_MAX_IMAGES)
        assertEquals(VideoLatencyPolicy.Tick.SEND_FRESH, VideoLatencyPolicy.dispatch(true))
        assertEquals(VideoLatencyPolicy.Tick.HOLD, VideoLatencyPolicy.dispatch(false))
    }

    @Test fun heldLatencyTicksDoNotResendAStaleFrame() {
        val sends = mutableListOf<Int>()
        listOf(true, false, false, true).forEachIndexed { frame, fresh ->
            if (VideoLatencyPolicy.dispatch(fresh) == VideoLatencyPolicy.Tick.SEND_FRESH) sends += frame
        }
        assertEquals(listOf(0, 3), sends)
    }

    @Test fun videoAudioAppliesVideoSaturationAndOnlyAddsBrightnessModulation() {
        val processor = processorWith(40, 80, 120)
        val features = AudioFeatures(.4f, .9f, .2f, .8f, .3f, .7f, FloatArray(16) { if (it == 0) .1f else .9f }, true)
        val base = AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO_AUDIO, brightness = 1f, audioBoost = .5f)
        val saturationZero = processor.compose(null, base.copy(videoEffect = VideoEffect.SATURATION, videoSaturationPercent = 0)).copyOf()
        val saturationFull = processor.compose(null, base.copy(videoEffect = VideoEffect.SATURATION, videoSaturationPercent = 100)).copyOf()
        val brightness = processor.compose(features, base.copy(videoAudioEffect = VideoAudioEffect.BRIGHTNESS_PULSE)).copyOf()
        val eq = processor.compose(features, base.copy(videoAudioEffect = VideoAudioEffect.EQ)).copyOf()
        assertArrayEquals(byteArrayOf(80, 80, 80), saturationZero)
        assertArrayEquals(byteArrayOf(40, 80, 120), saturationFull)
        val silence = processor.compose(null, base.copy(videoEffect = VideoEffect.SATURATION, videoSaturationPercent = 100)).copyOf()
        assertFalse(brightness.contentEquals(eq))
        for (i in 0..2) assertTrue((brightness[i].toInt() and 255) >= (silence[i].toInt() and 255))
    }

    @Test fun liveVideoTreatmentAndSaturationChangeVideoAndVideoAudioWithoutPersisting() {
        val processor = processorWith(40, 80, 120)
        val persisted = AudioSettings.defaults().copy(brightness = 1f, videoEffect = VideoEffect.NORMAL, videoSaturationPercent = 125)
        LiveRendererSettings.begin(persisted)
        try {
            LiveRendererSettings.setVideoEffect(VideoEffect.SATURATION)
            LiveRendererSettings.setVideoSaturationPercent(0)
            val live = LiveRendererSettings.apply(persisted)
            assertEquals(VideoEffect.NORMAL, persisted.videoEffect)
            assertEquals(125, persisted.videoSaturationPercent)
            assertEquals(VideoEffect.SATURATION, live.videoEffect)
            assertEquals(0, live.videoSaturationPercent)
            assertArrayEquals(byteArrayOf(80, 80, 80), processor.compose(null, live.copy(renderMode = RenderMode.VIDEO)).copyOf())
            assertArrayEquals(byteArrayOf(80, 80, 80), processor.compose(null, live.copy(renderMode = RenderMode.VIDEO_AUDIO)).copyOf())
        } finally { LiveRendererSettings.end() }
    }

    @Test fun audioAdmittedWledWithoutCalibrationCannotTransitionToVideoAndStateIsUnchanged() {
        val device = WledDevice("mac:AABBCCDDEEFF", "TV", "192.168.1.2", 16, 21324)
        val admitted = AudioSettings.defaults().copy(outputMode = OutputMode.WLED, wledDevices = listOf(device), selectedWledIdentities = setOf(device.identity), renderMode = RenderMode.AUDIO)
        LiveRendererSettings.begin(admitted)
        try {
            assertFalse(LiveRendererSettings.setRenderMode(RenderMode.VIDEO))
            assertFalse(LiveRendererSettings.setRenderMode(RenderMode.VIDEO_AUDIO))
            assertEquals(RenderMode.AUDIO, LiveRendererSettings.apply(admitted).renderMode)
        } finally { LiveRendererSettings.end() }
    }

    @Test fun hyperionAndPreflightedVideoWledCanTransitionLiveWithoutChangingRouteSettings() {
        val hyperion = HyperionDevice("uuid:123e4567-e89b-12d3-a456-426614174000", "Hyperion", "192.168.1.2")
        val hyperionAdmitted = AudioSettings.defaults().copy(hyperionDevices = listOf(hyperion), selectedHyperionIdentity = hyperion.identity, renderMode = RenderMode.AUDIO)
        val wled = WledDevice("mac:AABBCCDDEEFF", "TV", "192.168.1.3", 16, 21324)
        val videoWled = AudioSettings.defaults().copy(outputMode = OutputMode.WLED, wledDevices = listOf(wled), selectedWledIdentities = setOf(wled.identity), wledCalibrations = listOf(WledScreenCalibration.proportional(wled.identity, wled.leds)), renderMode = RenderMode.VIDEO)
        listOf(hyperionAdmitted, videoWled).forEach { admitted ->
            LiveRendererSettings.begin(admitted)
            try {
                assertTrue(LiveRendererSettings.setRenderMode(RenderMode.VIDEO_AUDIO))
                assertEquals(admitted.outputMode, LiveRendererSettings.apply(admitted).outputMode)
                assertEquals(RenderMode.VIDEO_AUDIO, LiveRendererSettings.apply(admitted).renderMode)
            } finally { LiveRendererSettings.end() }
        }
    }

    @Test fun activeSilenceFloorIsObservedAndBrightnessReductionKeepsItValid() {
        val persisted = AudioSettings.defaults().copy(brightness = .7f, renderMode = RenderMode.VIDEO_AUDIO)
        LiveRendererSettings.begin(persisted)
        try {
            LiveRendererSettings.setVideoAudioSilenceBrightnessFloor(.4f, .7f)
            assertFalse(FrameSmoothingPolicy.immediateBlack(LiveRendererSettings.apply(persisted), false))
            LiveRendererSettings.setBrightness(.2f)
            val live = LiveRendererSettings.apply(persisted)
            assertEquals(.2f, live.videoAudioSilenceBrightnessFloor)
            assertTrue(live.valid())
        } finally { LiveRendererSettings.end() }
    }

    @Test fun tabsAreFixedDpadButtonPanelsWithExactlyOneVisible() {
        assertEquals(listOf("Керування", "Додатково"), LeanbackTabPolicy.tabs)
        assertEquals("toggle", LeanbackTabPolicy.controls(0).first())
        assertTrue("base-video-colour-treatment" in LeanbackTabPolicy.controls(1))
        assertTrue("video-saturation" in LeanbackTabPolicy.controls(1))
        (0..1).forEach { selected -> assertEquals(1, (0..1).count { panel -> TvTabSelectionPolicy.panelIsVisible(panel, selected) }) }
    }

    @Test fun conditionalTvControlsFollowCanonicalMode() {
        assertFalse(TvUiStatePolicy.showVideoControls(RenderMode.AUDIO)); assertTrue(TvUiStatePolicy.showVideoControls(RenderMode.VIDEO))
        assertFalse(TvUiStatePolicy.showVideoColourTreatment(RenderMode.AUDIO)); assertTrue(TvUiStatePolicy.showVideoColourTreatment(RenderMode.VIDEO)); assertTrue(TvUiStatePolicy.showVideoColourTreatment(RenderMode.VIDEO_AUDIO))
        assertFalse(TvUiStatePolicy.showVideoSaturation(RenderMode.AUDIO)); assertTrue(TvUiStatePolicy.showVideoSaturation(RenderMode.VIDEO)); assertTrue(TvUiStatePolicy.showVideoSaturation(RenderMode.VIDEO_AUDIO))
    }

    @Test fun switchingIntoBeatPulseBaselinesRetainedBeatUntilTheNextSequence() {
        val processor = processorWith(100, 50, 25)
        val base = AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO_AUDIO, brightness = 1f, audioBoost = .5f)
        val otherEffect = base.copy(videoAudioEffect = VideoAudioEffect.BRIGHTNESS_PULSE)
        val beatPulse = base.copy(videoAudioEffect = VideoAudioEffect.BEAT_PULSE)
        val retained = AudioFeatures(.8f, .9f, .8f, .8f, .3f, .2f, FloatArray(16) { .5f }, true, 7L, 1f, 1_000_000_000L, 120f, 1f)
        processor.compose(retained, otherEffect, 1_000_000_000L)
        val noStaleAttack = processor.compose(retained, beatPulse, 1_100_000_000L).copyOf()
        assertArrayEquals(byteArrayOf(100, 50, 25), noStaleAttack)
        val next = AudioFeatures(.8f, .9f, .8f, .8f, .3f, .2f, FloatArray(16) { .5f }, true, 8L, 1f, 1_200_000_000L, 120f, 1f)
        val oneNewAttack = processor.compose(next, beatPulse, 1_200_000_000L).copyOf()
        val repeated = processor.compose(next, beatPulse, 1_200_000_000L).copyOf()
        assertFalse(oneNewAttack.contentEquals(noStaleAttack))
        assertArrayEquals("only the higher sequence may attack", oneNewAttack, repeated)
    }

    @Test fun initialBeatPulseActivationBaselinesRetainedEventThenAttacksOneNewSequence() {
        val processor = processorWith(100, 50, 25)
        val settings = AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO_AUDIO, brightness = 1f, audioBoost = .5f, videoAudioEffect = VideoAudioEffect.BEAT_PULSE)
        val retained = beat(sequence = 7L, timestampNanos = 1_000_000_000L)
        val noStaleAttack = processor.compose(retained, settings, 1_100_000_000L).copyOf()
        assertArrayEquals(byteArrayOf(100, 50, 25), noStaleAttack)
        val next = beat(sequence = 8L, timestampNanos = 1_200_000_000L)
        val attack = processor.compose(next, settings, 1_200_000_000L).copyOf()
        val repeated = processor.compose(next, settings, 1_200_000_000L).copyOf()
        val decay = processor.compose(next, settings, 1_500_000_000L).copyOf()
        assertFalse(attack.contentEquals(noStaleAttack))
        assertArrayEquals("only the higher sequence may attack", attack, repeated)
        assertTrue((decay[0].toInt() and 255) < (attack[0].toInt() and 255))
        assertTrue(attack.all { (it.toInt() and 255) in 0..255 })
    }

    @Test fun videoToVideoAudioBeatPulseBaselinesRetainedEventThenAttacksOneNewSequence() {
        val processor = processorWith(100, 50, 25)
        val video = AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO, brightness = 1f, audioBoost = .5f, videoAudioEffect = VideoAudioEffect.BEAT_PULSE)
        val videoAudio = video.copy(renderMode = RenderMode.VIDEO_AUDIO)
        val retained = beat(sequence = 7L, timestampNanos = 1_000_000_000L)
        processor.compose(retained, video, 1_000_000_000L)
        val noStaleAttack = processor.compose(retained, videoAudio, 1_100_000_000L).copyOf()
        assertArrayEquals(byteArrayOf(100, 50, 25), noStaleAttack)
        val next = beat(sequence = 8L, timestampNanos = 1_200_000_000L)
        val attack = processor.compose(next, videoAudio, 1_200_000_000L).copyOf()
        val repeated = processor.compose(next, videoAudio, 1_200_000_000L).copyOf()
        assertFalse(attack.contentEquals(noStaleAttack))
        assertArrayEquals("only the higher sequence may attack", attack, repeated)
    }

    @Test fun beatPulseRejectsAnAgedHigherSequence() {
        val processor = processorWith(100, 50, 25)
        val settings = AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO_AUDIO, brightness = 1f, audioBoost = .5f, videoAudioEffect = VideoAudioEffect.BEAT_PULSE)
        processor.compose(beat(sequence = 7L, timestampNanos = 1_000_000_000L), settings, 1_000_000_000L)
        val aged = beat(sequence = 8L, timestampNanos = 1_000_000_000L)
        val rejected = processor.compose(aged, settings, 1_500_000_001L).copyOf()
        val repeated = processor.compose(aged, settings, 1_500_000_001L).copyOf()
        assertArrayEquals(byteArrayOf(100, 50, 25), rejected)
        assertArrayEquals("aged event is consumed without an attack", rejected, repeated)
    }

    private fun beat(sequence: Long, timestampNanos: Long) = AudioFeatures(.8f, .9f, .8f, .8f, .3f, .2f, FloatArray(16) { .5f }, true, sequence, 1f, timestampNanos, 120f, 1f)

    private fun processorWith(r: Int, g: Int, b: Int): VideoFrameProcessor = VideoFrameProcessor(1, 1).also { processor ->
        val field = VideoFrameProcessor::class.java.getDeclaredField("video").apply { isAccessible = true }
        byteArrayOf(r.toByte(), g.toByte(), b.toByte()).copyInto(field.get(processor) as ByteArray)
    }
}
