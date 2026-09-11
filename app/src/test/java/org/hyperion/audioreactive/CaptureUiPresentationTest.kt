package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureUiPresentationTest {
    @Test fun indicatorKeepsCaptureStateAndSelectedModeVisible() {
        assertEquals(
            "Захоплення: Готово · Режим: Аудіо",
            CaptureUiPresentation.indicator(CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT, RenderMode.AUDIO),
        )
        assertEquals(
            "Захоплення: Активне · Режим: Аудіо + відео",
            CaptureUiPresentation.indicator(CaptureStatus.CAPTURE_ACTIVE_VIDEO_AUDIO, RenderMode.VIDEO_AUDIO),
        )
    }

    @Test fun routeLossIsPresentedAsAConciseTerminalState() {
        assertEquals(
            "Захоплення: Вихід втрачено · Режим: Відео",
            CaptureUiPresentation.indicator(CaptureStatus.ROUTE_LOST, RenderMode.VIDEO),
        )
    }
}
