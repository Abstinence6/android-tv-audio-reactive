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
            MqttContract.DIAGNOSTIC_DISCOVERY,
        ), snapshot.take(4).map { it.topic })
        assertTrue(snapshot.all { it.retained })
        assertEquals("online", snapshot.first().payload)
        assertEquals("OFF", snapshot.first { it.topic == MqttContract.CAPTURE_STATE }.payload)
        assertEquals("NEEDS_MEDIA_PROJECTION_CONSENT", snapshot.first { it.topic == MqttContract.DIAGNOSTIC_STATE }.payload)
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

    @Test fun settingsCommandsAreNonRetainedStrictAndRangeCheckedWithoutCaptureConsent() {
        val brightness = MqttContract.parseCommand(MqttContract.SETTINGS_COMMAND, "{\"field\":\"brightness\",\"value\":\"0.8\"}", false) as MqttContract.Command.SetSetting
        assertEquals(.8f, MqttSettingsPolicy.apply(AudioSettings.defaults(), brightness)?.brightness)
        assertNull(MqttContract.parseCommand(MqttContract.SETTINGS_COMMAND, "{\"value\":\"0.8\",\"field\":\"brightness\"}", false))
        assertNull(MqttContract.parseCommand(MqttContract.SETTINGS_COMMAND, "{\"field\":\"host\",\"value\":\"8.8.8.8\"}", false))
        assertNull(MqttContract.parseCommand(MqttContract.SETTINGS_COMMAND, "{\"field\":\"brightness\",\"value\":\"0.8\"}", true))
        assertNull(MqttSettingsPolicy.apply(AudioSettings.defaults(), MqttContract.Command.SetSetting("brightness", "4")))
        val publications = MqttContract.snapshot(AudioSettings.defaults(), false, "idle")
        assertTrue(publications.any { it.topic == MqttContract.settingStateTopic("render_mode") && it.payload == "AUDIO" })
        assertTrue(publications.any { it.topic == MqttContract.settingDiscoveryTopic("brightness") && it.payload.contains("\"command_topic\"") })
        assertEquals(MqttCommandPolicy.Action.ReportConsentRequired, MqttCommandPolicy.decide(MqttContract.Command.On, false))
    }

    @Test fun calibrationAndSelectedRoutesOnlyAcceptKnownInventory() {
        val device = WledDevice("mac:AABBCCDDEEFF", "TV", "10.1.2.3", 16, 21324)
        val base = AudioSettings.defaults().copy(outputMode = OutputMode.WLED, wledDevices = listOf(device))
        val selected = MqttSettingsPolicy.apply(base, MqttContract.Command.SetSetting("selected_wled_identities", device.identity))!!
        assertEquals(setOf(device.identity), selected.selectedWledIdentities)
        assertNull(MqttSettingsPolicy.apply(base, MqttContract.Command.SetSetting("selected_wled_identities", "mac:FFFFFFFFFFFF")))
        val calibration = MqttSettingsPolicy.apply(base, MqttContract.Command.SetSetting("calibration_gamma", "${device.identity},2.0"))
        assertEquals(2f, calibration?.calibrationFor(device)?.gamma)
    }

    @Test fun discoveryCreatesUsableEntitiesForPersistedSettingsRoutesAndCalibration() {
        val wled = WledDevice("mac:AABBCCDDEEFF", "TV", "10.1.2.3", 16, 21324)
        val hyperion = HyperionDevice("uuid:123e4567-e89b-12d3-a456-426614174000", "Hyperion", "192.168.1.2")
        val settings = AudioSettings.defaults().copy(wledDevices = listOf(wled), hyperionDevices = listOf(hyperion))
        val publications = MqttContract.snapshot(settings, false, "idle")
        val brightness = publications.first { it.topic == MqttContract.settingDiscoveryTopic("brightness") }.payload
        assertTrue(brightness.contains("\"command_topic\":\"${MqttContract.settingCommandTopic("brightness")}\""))
        assertTrue(brightness.contains("\"min\":0"))
        assertTrue(brightness.contains("\"max\":1"))
        val route = publications.first { it.topic == MqttContract.wledRouteDiscoveryTopic(wled.identity) }.payload
        assertTrue(route.contains("\"command_topic\":\"${MqttContract.wledRouteCommandTopic(wled.identity)}\""))
        val gamma = publications.first { it.topic == MqttContract.calibrationDiscoveryTopic(wled.identity, "gamma") }.payload
        assertTrue(gamma.contains("\"command_topic\":\"${MqttContract.calibrationCommandTopic(wled.identity, "gamma")}\""))
        assertEquals(MqttContract.Command.SetSetting("brightness", "0.8"), MqttContract.parseCommand(MqttContract.settingCommandTopic("brightness"), "0.8", false))
        assertEquals(MqttContract.Command.SetSetting("selected_wled_identities", "${wled.identity},ON"), MqttContract.parseCommand(MqttContract.wledRouteCommandTopic(wled.identity), "ON", false))
        assertEquals(MqttContract.Command.SetSetting("calibration_gamma", "${wled.identity},2.0"), MqttContract.parseCommand(MqttContract.calibrationCommandTopic(wled.identity, "gamma"), "2.0", false))
        assertEquals(MqttContract.Command.SetSetting("selected_hyperion_identity", "none"), MqttContract.parseCommand(MqttContract.hyperionRouteCommandTopic(), "none", false))
    }
}
