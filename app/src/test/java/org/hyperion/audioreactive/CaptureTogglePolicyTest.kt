package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureTogglePolicyTest {
    @Test fun activeServiceStopsWithoutAnyPermissionOrCaptureStartAction() {
        assertEquals(
            CaptureTogglePolicy.Action.STOP_EXISTING,
            CaptureTogglePolicy.actionFor(serviceExists = true, recordAudioGranted = false),
        )
        assertEquals(
            CaptureTogglePolicy.Action.STOP_EXISTING,
            CaptureTogglePolicy.actionFor(serviceExists = true, recordAudioGranted = true),
        )
    }

    @Test fun inactiveWithoutRecordAudioRequestsOnlyRuntimePermission() {
        assertEquals(
            CaptureTogglePolicy.Action.REQUEST_RECORD_AUDIO,
            CaptureTogglePolicy.actionFor(serviceExists = false, recordAudioGranted = false),
        )
    }

    @Test fun inactiveWithRecordAudioRequestsSystemProjectionConsentNotCaptureStart() {
        assertEquals(
            CaptureTogglePolicy.Action.REQUEST_MEDIA_PROJECTION,
            CaptureTogglePolicy.actionFor(serviceExists = false, recordAudioGranted = true),
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
