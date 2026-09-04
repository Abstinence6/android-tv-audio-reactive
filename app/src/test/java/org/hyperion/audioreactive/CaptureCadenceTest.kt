package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCadenceTest {
    @Test fun analysisWindowFitsSupportedMaximumPeriod() {
        assertEquals(1_024, CaptureCadence.ANALYSIS_SAMPLES)
        assertEquals(21_333_333L, CaptureCadence.analysisWindowNanos())
        assertEquals(33_333_333L, CaptureCadence.periodNanos(CaptureCadence.MAX_FPS))
        assertTrue(CaptureCadence.analysisWindowNanos() < CaptureCadence.periodNanos(CaptureCadence.MAX_FPS))
    }

    @Test fun pacingSleepsOnlyTheRemainingPreReadDeadline() {
        val start = 1_000_000_000L
        val period = CaptureCadence.periodNanos(30)
        assertEquals(period - 25_000_000L, CaptureCadence.remainingSleepNanos(start, start + 25_000_000L, 30))
        assertEquals(0L, CaptureCadence.remainingSleepNanos(start, start + period + 1L, 30))
    }
}