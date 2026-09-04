package org.hyperion.audioreactive

import org.junit.Assert.*
import org.junit.Test

class WledCapturePreflightTest {
    private val selected = WledDevice("mac:AABBCCDDEEFF", "Desk", "192.168.1.152", 30, 21324)
    private val settings = AudioSettings(Effect.FIRE, .5f, 1f, 20, OutputMode.WLED, listOf(selected), setOf(selected.identity))

    @Test fun emptyOrChangedFreshSetCannotCreateABinding() {
        assertNull(WledCapturePreflight.bind(settings) { emptyList() })
        assertNull(WledCapturePreflight.bind(settings) { listOf(selected.copy(host = "192.168.1.26")) })
    }

    @Test fun freshBindingIsExactAndOneShot() {
        val id = WledCapturePreflight.bind(settings) { listOf(selected) }
        assertNotNull(id)
        assertEquals(listOf(selected), WledRouteBindings.consume(id, settings))
        assertNull(WledRouteBindings.consume(id, settings))
    }

    @Test fun changedRouteSettingsCannotReachStartAfterPreflight() {
        val id = WledCapturePreflight.bind(settings) { listOf(selected) }
        val changed = settings.copy(renderMode = RenderMode.VIDEO, videoQuality = VideoQuality.HIGH)

        assertFalse(WledRouteBindings.has(id, changed))
        assertNull(WledRouteBindings.consume(id, changed))
        assertFalse(WledRouteBindings.has(id, settings)) // mismatch consumes and discards the stale one-shot binding
    }

    @Test fun videoEligibilityExactlyMatchesPreflightCalibrationContract() {
        val videoWithoutCalibration = settings.copy(renderMode = RenderMode.VIDEO)
        val videoWithCalibration = videoWithoutCalibration.copy(wledCalibrations = listOf(WledScreenCalibration.proportional(selected.identity, selected.leds)))

        listOf(videoWithoutCalibration, videoWithCalibration).forEach { candidate ->
            assertEquals(WledCapturePreflight.eligible(candidate), CaptureStartEligibility.permits(candidate))
            assertEquals(WledCapturePreflight.eligible(candidate), WledCapturePreflight.bind(candidate) { listOf(selected) } != null)
        }
    }

    @Test fun hyperionDoesNotNeedOrCreateAWledBinding() {
        assertNull(WledCapturePreflight.bind(AudioSettings.defaults()) { error("must not revalidate Hyperion") })
    }

    @Test fun everySelectedVideoTargetMustBeFreshAndCalibrationRouteable() {
        val second = WledDevice("mac:001122334455", "Shelf", "192.168.1.153", 30, 21324)
        val video = settings.copy(renderMode = RenderMode.VIDEO, wledDevices = listOf(selected, second), selectedWledIdentities = setOf(selected.identity, second.identity), wledCalibrations = listOf(WledScreenCalibration.proportional(selected.identity, selected.leds)))
        assertFalse(WledCapturePreflight.eligible(video))
        assertNull(WledCapturePreflight.bind(video) { listOf(selected) })
        val calibrated = video.copy(wledCalibrations = video.wledDevices.map { WledScreenCalibration.proportional(it.identity, it.leds) })
        assertNotNull(WledCapturePreflight.bind(calibrated) { listOf(selected, second) })
        assertNull(WledCapturePreflight.bind(calibrated) { listOf(selected) })
    }
}
