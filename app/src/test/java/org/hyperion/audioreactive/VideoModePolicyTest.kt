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

    @Test fun videoSaturationHasTheRequiredBoundsDefaultAndInactiveOnlyPolicy() {
        assertEquals(125, VideoSaturationPolicy.DEFAULT_PERCENT)
        assertEquals(VideoSaturationPolicy.DEFAULT_PERCENT, AudioSettings.defaults().videoSaturationPercent)
        assertTrue(VideoSaturationPolicy.valid(0)); assertTrue(VideoSaturationPolicy.valid(200)); assertFalse(VideoSaturationPolicy.valid(201))
        assertTrue(VideoSaturationPolicy.mutable(false)); assertFalse(VideoSaturationPolicy.mutable(true))
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
        assertTrue(VideoColourTreatmentPolicy.mutable(false)); assertFalse(VideoColourTreatmentPolicy.mutable(true))
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

    private fun processorWith(r: Int, g: Int, b: Int): VideoFrameProcessor = VideoFrameProcessor(1, 1).also { processor ->
        val field = VideoFrameProcessor::class.java.getDeclaredField("video").apply { isAccessible = true }
        byteArrayOf(r.toByte(), g.toByte(), b.toByte()).copyInto(field.get(processor) as ByteArray)
    }
}
