package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePreflightRetryTest {
    @Test fun firstReadOnlyValidationSuccessStopsImmediately() {
        var calls = 0
        val result = CapturePreflightRetry.bind(cancelled = { false }, pause = { true }) { calls++; "binding" }
        assertEquals("binding", result.binding)
        assertEquals(1, result.attempts)
        assertEquals(1, calls)
    }

    @Test fun laterSuccessStopsBeforeTenAttempts() {
        var calls = 0
        val result = CapturePreflightRetry.bind(cancelled = { false }, pause = { true }) { if (++calls == 4) "binding" else null }
        assertEquals("binding", result.binding)
        assertEquals(4, result.attempts)
        assertEquals(4, calls)
    }

    @Test fun allFailuresHaveExactlyTenAttemptsAndNoBindingToLeakOrStartConsent() {
        var validationCalls = 0
        var mediaProjectionRequests = 0
        val result = CapturePreflightRetry.bind(cancelled = { false }, pause = { true }) { validationCalls++; null as String? }
        if (result.binding != null) mediaProjectionRequests++
        assertNull(result.binding)
        assertFalse(result.cancelled)
        assertEquals(CapturePreflightRetry.MAX_ATTEMPTS, result.attempts)
        assertEquals(10, validationCalls)
        assertEquals(0, mediaProjectionRequests)
    }

    @Test fun cancellationPreventsFurtherValidationAttempts() {
        var calls = 0
        val result = CapturePreflightRetry.bind(cancelled = { calls >= 2 }, pause = { true }) { calls++; null as String? }
        assertNull(result.binding)
        assertTrue(result.cancelled)
        assertEquals(2, result.attempts)
        assertEquals(2, calls)
    }
}
