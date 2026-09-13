package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureUiPresentationTest {
    @Test fun indicatorUsesLocalizedResourceIdsForCaptureState() {
        assertEquals(
            R.string.status_ready,
            CaptureUiPresentation.stateLabel(CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT),
        )
        assertEquals(
            R.string.status_active,
            CaptureUiPresentation.stateLabel(CaptureStatus.CAPTURE_ACTIVE_VIDEO_AUDIO),
        )
    }

    @Test fun routeLossIsPresentedAsAConciseTerminalState() {
        assertEquals(
            R.string.status_route_lost,
            CaptureUiPresentation.stateLabel(CaptureStatus.ROUTE_LOST),
        )
        assertEquals(
            R.string.status_microphone_lost,
            CaptureUiPresentation.stateLabel(CaptureStatus.MICROPHONE_ROUTE_LOST),
        )
    }
}
