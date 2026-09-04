package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RuntimeSettingsTest {
    @Test fun restoredSettingsDriveRuntime() {
        assertEquals(20, AudioSettings.defaults().fps)
        val saved = AudioSettings(Effect.AURORA, .8f, 1.75f, 30)
        val store = MemoryStore(saved)
        RuntimeSettings.initialize(store)
        assertEquals(saved, RuntimeSettings.snapshot())
    }

    @Test fun validUpdatesPersistBeforeFutureReloadAndInvalidValuesAreRejected() {
        val store = MemoryStore(null)
        RuntimeSettings.initialize(store)
        val updated = AudioSettings(Effect.FIRE, .5f, 2f, 30)
        RuntimeSettings.apply(updated)
        assertEquals(updated, store.value)
        RuntimeSettings.initialize(store)
        assertEquals(updated, RuntimeSettings.snapshot())
        try { RuntimeSettings.apply(updated.copy(fps = 31)); throw AssertionError("Expected invalid settings rejection") } catch (_: IllegalArgumentException) {}
        assertEquals(updated, RuntimeSettings.snapshot())
    }

    @Test fun everyAppliedLocalMutationNotifiesDiagnosticPublisherWithFreshStateAndAttributes() {
        RuntimeSettings.initialize(MemoryStore(null))
        val publications = mutableListOf<Pair<String, String>>()
        val listener: (AudioSettings) -> Unit = { settings ->
            MqttContract.snapshot(settings, false, "needs_media_projection_consent")
                .filter { it.topic == MqttContract.DIAGNOSTIC_STATE || it.topic == MqttContract.DIAGNOSTIC_ATTRIBUTES }
                .forEach { publications += it.topic to it.payload }
        }
        RuntimeSettings.addListener(listener)
        RuntimeSettings.update { it.copy(effect = Effect.FIRE, fps = 24) }
        RuntimeSettings.removeListener(listener)
        assertEquals(listOf(MqttContract.DIAGNOSTIC_STATE, MqttContract.DIAGNOSTIC_ATTRIBUTES), publications.map { it.first })
        assertEquals("NEEDS_MEDIA_PROJECTION_CONSENT", publications[0].second)
        assertTrue(publications[1].second.contains("\"effect\":\"FIRE\""))
        assertTrue(publications[1].second.contains("\"fps\":24"))
    }

    @Test fun invalidPersistedValuesFallBackToDefaults() {
        RuntimeSettings.initialize(MemoryStore(AudioSettings(Effect.NEON, 2f, 1f, 20)))
        assertEquals(AudioSettings.defaults(), RuntimeSettings.snapshot())
    }

    @Test fun zeroBrightnessIsValidAndPersistsExactly() {
        val zero = AudioSettings.defaults().copy(brightness = 0f)
        val store = MemoryStore(null)
        assertTrue(zero.valid())
        RuntimeSettings.initialize(store)
        RuntimeSettings.apply(zero)
        assertEquals(0f, store.value!!.brightness)
        RuntimeSettings.initialize(store)
        assertEquals(0f, RuntimeSettings.snapshot().brightness)
        assertTrue(!zero.copy(brightness = -.01f).valid())
    }

    @Test fun everyVideoQualityMapsToASupportedCaptureFrame() {
        val expected = listOf(
            VideoQuality.VERY_LOW to SourceFrameSpec(64, 36, 20),
            VideoQuality.ECONOMY to SourceFrameSpec(80, 45, 20),
            VideoQuality.LOW to SourceFrameSpec(96, 54, 20),
            VideoQuality.BALANCED to SourceFrameSpec(112, 63, 20),
            VideoQuality.STANDARD to SourceFrameSpec(128, 72, 20),
            VideoQuality.HIGH to SourceFrameSpec(160, 90, 20),
            VideoQuality.VERY_HIGH to SourceFrameSpec(192, 108, 20),
            VideoQuality.ULTRA to SourceFrameSpec(256, 144, 20),
            VideoQuality.MAXIMUM to SourceFrameSpec(320, 180, 20),
        )
        assertEquals(expected.map { it.first }, VideoQuality.entries)
        expected.forEach { (quality, frame) ->
            assertEquals(frame, AudioSettings.defaults().copy(renderMode = RenderMode.VIDEO, videoQuality = quality).sourceFrame())
            assertTrue(frame.bytes <= HyperionFlatbuffer.MAX_IMAGE_BYTES)
        }
        assertEquals(HyperionFlatbuffer.MAX_IMAGE_BYTES, expected.last().second.bytes)
    }

    @Test fun missingLegacyOutputSelectionMigratesToHyperionDefault() {
        val legacy = AudioSettings(Effect.FIRE, .65f, 1f, 20)
        RuntimeSettings.initialize(MemoryStore(legacy))
        assertEquals(OutputMode.HYPERION, RuntimeSettings.snapshot().outputMode)
    }

    @Test fun incompleteScanKeepsOfflineDiscoveredDeviceAndItsSelection() {
        val offline = WledDevice("mac:001122334455", "TV", "192.168.1.152", 120, 21324)
        val fresh = WledDevice("mac:AABBCCDDEEFF", "Desk", "192.168.1.153", 30, 21324)
        val before = AudioSettings(Effect.FIRE, .65f, 1f, 20, OutputMode.WLED, listOf(offline), setOf(offline.identity))
        val after = before.copy(wledDevices = WledInventory.merge(before.wledDevices, listOf(fresh)))
        assertEquals(listOf(offline, fresh), after.wledDevices)
        assertEquals(setOf(offline.identity), after.selectedWledIdentities)
        assertTrue(after.hasSelectedWledDestinations())
    }

    @Test fun outputsWithoutValidatedSelectionAreIneligible() {
        assertEquals(false, CaptureStartEligibility.permits(AudioSettings.defaults().copy(outputMode = OutputMode.WLED)))
        assertEquals(false, CaptureStartEligibility.permits(AudioSettings.defaults()))
    }

    @Test fun freshDiscoveryUpdatesSameIdentityEndpointDetails() {
        val saved = WledDevice("mac:001122334455", "Old", "192.168.1.152", 30, 21324)
        val fresh = WledDevice(saved.identity, "New", "192.168.1.153", 60, 19400)
        assertEquals(listOf(fresh), WledInventory.merge(listOf(saved), listOf(fresh)))
    }

    @Test fun wledZoneSettingIsPersistedAndChangesOnlyWledAudioCaptureFrame() {
        val saved = AudioSettings.defaults().copy(outputMode = OutputMode.WLED, wledSourceZones = 256)
        val store = MemoryStore(saved)
        RuntimeSettings.initialize(store)
        assertEquals(256, RuntimeSettings.snapshot().wledSourceZones)
        assertEquals(SourceFrameSpec(256, 1, saved.fps), saved.captureFrame())
        assertEquals(SourceFrameSpec(128,72,saved.fps), saved.copy(renderMode = RenderMode.VIDEO).captureFrame())
        assertEquals(SourceFrameSpec(16, 1, saved.fps), saved.copy(outputMode = OutputMode.HYPERION).captureFrame())
    }

    @Test fun concurrentUpdatesTransformAndPersistUnderOneLockWithoutLostMutation() {
        RuntimeSettings.initialize(MemoryStore(null))
        val transformEntered = CountDownLatch(1)
        val releaseFirstTransform = CountDownLatch(1)
        val secondAttempted = CountDownLatch(1)
        val completed = CountDownLatch(2)
        thread {
            RuntimeSettings.update {
                transformEntered.countDown()
                assertTrue(releaseFirstTransform.await(2, TimeUnit.SECONDS))
                it.copy(brightness = .4f)
            }
            completed.countDown()
        }
        assertTrue(transformEntered.await(2, TimeUnit.SECONDS))
        thread {
            secondAttempted.countDown()
            RuntimeSettings.update { it.copy(sensitivity = 2f) }
            completed.countDown()
        }
        assertTrue(secondAttempted.await(2, TimeUnit.SECONDS))
        releaseFirstTransform.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(.4f, RuntimeSettings.snapshot().brightness)
        assertEquals(2f, RuntimeSettings.snapshot().sensitivity)
    }

    private class MemoryStore(initial: AudioSettings?) : AudioSettingsStore {
        var value = initial
        override fun load(): AudioSettings? = value
        override fun save(settings: AudioSettings) { assertTrue(settings.valid()); value = settings }
    }
}
