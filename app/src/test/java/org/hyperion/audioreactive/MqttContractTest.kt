package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttContractTest {
    @Test fun pinnedAnonymousEndpointAndNamespaceAreExact() {
        assertTrue(MqttContract.validBroker("tcp://192.168.1.1:1883"))
        assertFalse(MqttContract.validBroker("tcp://192.168.1.1:1884"))
        assertFalse(MqttContract.validBroker("ssl://192.168.1.1:1883"))
        assertTrue(MqttContract.isAllowedTopic("audio_reactive_tv/capture/set"))
        assertFalse(MqttContract.isAllowedTopic("homeassistant/switch/anything/set"))
    }

    @Test fun retainedMalformedAndOffNamespaceCommandsAreRejectedBeforeMutation() {
        assertNull(MqttContract.parseCommand(MqttContract.CAPTURE_COMMAND, "ON", true))
        assertNull(MqttContract.parseCommand(MqttContract.CAPTURE_COMMAND, "START", false))
        assertNull(MqttContract.parseCommand("audio_reactive_tv/other/set", "ON", false))
        assertNull(MqttContract.parseCommand(MqttContract.EFFECT_COMMAND, "x".repeat(33), false))
    }

    @Test fun snapshotIsRetainedAuthoritativeAndOrderedForEveryReconnect() {
        val snapshot = MqttContract.snapshot(AudioSettings.defaults(), false, "needs_media_projection_consent")
        assertEquals(listOf(
            MqttContract.AVAILABILITY, MqttContract.CAPTURE_DISCOVERY, MqttContract.EFFECT_DISCOVERY,
            MqttContract.DIAGNOSTIC_DISCOVERY, MqttContract.CAPTURE_STATE, MqttContract.EFFECT_STATE,
            MqttContract.STATUS, MqttContract.DIAGNOSTIC_STATE, MqttContract.DIAGNOSTIC_ATTRIBUTES,
        ), snapshot.map { it.topic })
        assertTrue(snapshot.all { it.retained })
        assertEquals("online", snapshot.first().payload)
        assertEquals("OFF", snapshot[4].payload)
        assertEquals("NEEDS_MEDIA_PROJECTION_CONSENT", snapshot[7].payload)
    }

    @Test fun diagnosticSensorIsReadOnlyDiagnosticAndNeverAddsACommandTopic() {
        val snapshot = MqttContract.snapshot(AudioSettings.defaults(), false, "idle")
        val discovery = snapshot.first { it.topic == MqttContract.DIAGNOSTIC_DISCOVERY }.payload
        assertTrue(discovery.contains("\"state_topic\":\"${MqttContract.DIAGNOSTIC_STATE}\""))
        assertTrue(discovery.contains("\"json_attributes_topic\":\"${MqttContract.DIAGNOSTIC_ATTRIBUTES}\""))
        assertTrue(discovery.contains("\"availability_topic\":\"${MqttContract.AVAILABILITY}\""))
        assertTrue(discovery.contains("\"entity_category\":\"diagnostic\""))
        assertFalse(discovery.contains("command_topic"))
        assertFalse(discovery.contains("entity_category\":\"config"))
        assertFalse(snapshot.any { it.topic.endsWith("/parameters/set") })
    }

    @Test fun diagnosticAttributesContainCurrentSettingsRuntimeAndSafeSelectedOutputs() {
        val wled = WledDevice("mac:AABBCCDDEEFF", "TV strip", "192.168.1.152", 32, 21324)
        val hyperion = HyperionDevice("uuid:123e4567-e89b-12d3-a456-426614174000", "TV Hyperion", "192.168.1.158")
        val settings = AudioSettings.defaults().copy(
            effect = Effect.FIRE, brightness = .8f, sensitivity = 1.5f, fps = 24,
            outputMode = OutputMode.WLED, renderMode = RenderMode.VIDEO_AUDIO, videoQuality = VideoQuality.HIGH,
            audioBoost = .5f, wledSourceZones = 160, effectParameters = EffectParameters(2f, .7f, .3f, 45f),
            wledDevices = listOf(wled), selectedWledIdentities = setOf(wled.identity),
            hyperionDevices = listOf(hyperion), selectedHyperionIdentity = hyperion.identity,
            wledCalibrations = listOf(WledScreenCalibration.proportional(wled.identity, wled.leds)),
            videoEffect = VideoEffect.CONTRAST, videoAudioEffect = VideoAudioEffect.EQ, videoSaturationPercent = 150,
        )
        val runtime = MqttContract.DiagnosticRuntime(true, "CAPTURE_ACTIVE_VIDEO_AUDIO", "active", "0.2.0", "Acme TV")
        val attributes = MqttContract.snapshot(settings, runtime).first { it.topic == MqttContract.DIAGNOSTIC_ATTRIBUTES }.payload
        listOf("\"capture_active\":true", "\"capture_status\":\"CAPTURE_ACTIVE_VIDEO_AUDIO\"", "\"app_version\":\"0.2.0\"", "\"device_name\":\"Acme TV\"", "\"render_mode\":\"VIDEO_AUDIO\"", "\"output_mode\":\"WLED\"", "\"effect\":\"FIRE\"", "\"brightness\":0.8", "\"sensitivity\":1.5", "\"fps\":24", "\"video_quality\":\"HIGH\"", "\"video_saturation_percent\":150", "\"wled_source_zones\":160", "\"speed\":2.0", "\"identity\":\"mac:AABBCCDDEEFF\"", "\"name\":\"TV strip\"", "\"calibration_status\":\"valid\"", "\"selected_hyperion_identity\":\"uuid:123e4567-e89b-12d3-a456-426614174000\"").forEach { assertTrue("Missing $it", attributes.contains(it)) }
        assertFalse(attributes.contains("192.168.1.152"))
        assertFalse(attributes.contains("192.168.1.158"))
        assertFalse(attributes.contains("realtimePort"))
    }

    @Test fun onNeverRequestsConsentOrStartsCaptureAndOffOnlyStopsOwnedCapture() {
        assertEquals(MqttCommandPolicy.Action.ReportConsentRequired, MqttCommandPolicy.decide(MqttContract.Command.On, false))
        assertEquals(MqttCommandPolicy.Action.ReportConsentRequired, MqttCommandPolicy.decide(MqttContract.Command.On, true))
        assertEquals(MqttCommandPolicy.Action.Ignore, MqttCommandPolicy.decide(MqttContract.Command.Off, false))
        assertEquals(MqttCommandPolicy.Action.StopOwnedCapture, MqttCommandPolicy.decide(MqttContract.Command.Off, true))
    }

    @Test fun effectCommandsRemainAllowlistedAndCanSafelyChangeRendererDuringCapture() {
        val spectrum = MqttContract.parseCommand(MqttContract.EFFECT_COMMAND, "SPECTRUM", false)!!
        assertEquals(MqttCommandPolicy.Action.ChangeEffect(Effect.SPECTRUM), MqttCommandPolicy.decide(spectrum, true))
        assertEquals(MqttCommandPolicy.Action.ChangeEffect(Effect.SPECTRUM), MqttCommandPolicy.decide(spectrum, false))
        assertNull(MqttContract.parseCommand(MqttContract.EFFECT_COMMAND, "NOT_AN_EFFECT", false))
    }
}
