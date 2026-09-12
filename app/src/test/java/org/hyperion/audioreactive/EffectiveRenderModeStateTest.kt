package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveRenderModeStateTest {
    @Test fun activeAudioVideoAndMixedUseOneEffectiveSourceForUiAndMqtt() {
        val persisted = AudioSettings.defaults().copy(
            renderMode = RenderMode.AUDIO,
            effect = Effect.FIRE,
            videoEffect = VideoEffect.CONTRAST,
            videoAudioEffect = VideoAudioEffect.BASS_SWEEP,
        )
        LiveRendererSettings.begin(persisted)
        try {
            assertEffective(RenderMode.AUDIO, persisted, "FIRE", listOf("FIRE"))

            assertTrue(LiveRendererSettings.setRenderMode(RenderMode.VIDEO))
            assertTrue(LiveRendererSettings.setActiveEffect("SATURATION"))
            assertEffective(RenderMode.VIDEO, persisted, "SATURATION", VideoEffect.entries.map { it.name })

            assertTrue(LiveRendererSettings.setRenderMode(RenderMode.VIDEO_AUDIO))
            assertTrue(LiveRendererSettings.setActiveEffect("EQ"))
            assertEffective(RenderMode.VIDEO_AUDIO, persisted, "EQ", VideoAudioEffect.entries.map { it.name })

            // The admission snapshot is never rewritten by these renderer-local transitions.
            assertEquals(RenderMode.AUDIO, persisted.renderMode)
        } finally {
            LiveRendererSettings.end()
        }
    }

    @Test fun uncalibratedWledVideoRequestKeepsUiAndMqttAtTheActiveAudioMode() {
        val device = WledDevice("mac:AABBCCDDEEFF", "TV", "192.168.1.2", 16, 21324)
        val admitted = AudioSettings.defaults().copy(
            outputMode = OutputMode.WLED,
            wledDevices = listOf(device),
            selectedWledIdentities = setOf(device.identity),
            renderMode = RenderMode.AUDIO,
        )
        LiveRendererSettings.begin(admitted)
        try {
            assertFalse(LiveRendererSettings.setRenderMode(RenderMode.VIDEO))
            val effective = EffectiveRenderSettings.snapshot(admitted, captureActive = true)
            assertEquals(RenderMode.AUDIO, effective.renderMode)
            val checkboxes = LiveRenderModeUiPolicy.checkboxes(effective.renderMode)
            assertTrue(checkboxes.audioChecked)
            assertFalse(checkboxes.videoChecked)
            val publications = MqttContract.snapshot(effective, activeRuntime())
            assertEquals("AUDIO", publications.first { it.topic == MqttContract.settingStateTopic("render_mode") }.payload)
            assertTrue(publications.first { it.topic == MqttContract.DIAGNOSTIC_ATTRIBUTES }.payload.contains("\"render_mode\":\"AUDIO\""))
        } finally {
            LiveRendererSettings.end()
        }
    }

    private fun assertEffective(mode: RenderMode, persisted: AudioSettings, activeEffect: String, labels: List<String>) {
        val effective = EffectiveRenderSettings.snapshot(persisted, captureActive = true)
        assertEquals(mode, effective.renderMode)
        val checkboxes = LiveRenderModeUiPolicy.checkboxes(mode)
        assertEquals(mode != RenderMode.VIDEO, checkboxes.audioChecked)
        assertEquals(mode != RenderMode.AUDIO, checkboxes.videoChecked)
        assertTrue(EffectSelectorPolicy.names(effective).containsAll(labels))
        val publications = MqttContract.snapshot(effective, activeRuntime())
        assertEquals(mode.name, publications.first { it.topic == MqttContract.settingStateTopic("render_mode") }.payload)
        assertEquals(activeEffect, publications.first { it.topic == MqttContract.EFFECT_STATE }.payload)
        assertTrue(publications.first { it.topic == MqttContract.EFFECT_DISCOVERY }.payload.contains("\"$activeEffect\""))
        assertTrue(publications.first { it.topic == MqttContract.DIAGNOSTIC_ATTRIBUTES }.payload.contains("\"render_mode\":\"${mode.name}\""))
    }

    private fun activeRuntime() = MqttContract.DiagnosticRuntime(true, "CAPTURE_ACTIVE", "capture_active")
}
