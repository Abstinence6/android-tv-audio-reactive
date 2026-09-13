package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureTogglePolicyTest {
    @Test fun activeServiceStopsWithoutAnyPermissionOrCaptureStartAction() {
        assertEquals(
            CaptureTogglePolicy.Action.STOP_EXISTING,
            CaptureTogglePolicy.actionFor(serviceExists = true, requiresAudio = true, recordAudioGranted = false),
        )
        assertEquals(
            CaptureTogglePolicy.Action.STOP_EXISTING,
            CaptureTogglePolicy.actionFor(serviceExists = true, requiresAudio = true, recordAudioGranted = true),
        )
    }

    @Test fun inactiveAudioModeWithoutRecordAudioRequestsOnlyRuntimePermission() {
        assertEquals(
            CaptureTogglePolicy.Action.REQUEST_RECORD_AUDIO,
            CaptureTogglePolicy.actionFor(serviceExists = false, requiresAudio = true, recordAudioGranted = false),
        )
    }

    @Test fun inactiveAudioModeWithRecordAudioRequestsSystemProjectionConsentNotCaptureStart() {
        assertEquals(
            CaptureTogglePolicy.Action.REQUEST_MEDIA_PROJECTION,
            CaptureTogglePolicy.actionFor(serviceExists = false, requiresAudio = true, recordAudioGranted = true),
        )
    }

    @Test fun videoOnlySkipsRecordAudioPermissionAndRequestsOnlyProjectionConsent() {
        assertEquals(
            CaptureTogglePolicy.Action.REQUEST_MEDIA_PROJECTION,
            CaptureTogglePolicy.actionFor(serviceExists = false, requiresAudio = false, recordAudioGranted = false),
        )
    }

    @Test fun deniedRuntimePermissionFinishesWithoutCapture() {
        assertEquals(CaptureTogglePolicy.Action.FINISH_WITHOUT_CAPTURE, CaptureTogglePolicy.afterRecordAudioPermission(false))
    }

    @Test fun onlySuccessfulSystemProjectionResultWithTokenPermitsCaptureStart() {
        assertEquals(CaptureTogglePolicy.Action.REQUEST_MEDIA_PROJECTION, CaptureTogglePolicy.afterRecordAudioPermission(true))
        assertEquals(CaptureTogglePolicy.Action.FINISH_WITHOUT_CAPTURE, CaptureTogglePolicy.afterMediaProjectionConsent(false, true))
        assertEquals(CaptureTogglePolicy.Action.FINISH_WITHOUT_CAPTURE, CaptureTogglePolicy.afterMediaProjectionConsent(true, false))
        assertEquals(CaptureTogglePolicy.Action.START_CAPTURE, CaptureTogglePolicy.afterMediaProjectionConsent(true, true))
    }
}
