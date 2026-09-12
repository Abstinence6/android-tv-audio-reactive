package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputRouteReconnectTest {
    @Test fun reconnectStopsAtTenFreshRouteAttempts() {
        var attempts = 0
        val result = OutputRouteReconnect.connect(cancelled = { false }, pause = { true }) { attempts++; null as String? }

        assertNull(result.binding)
        assertFalse(result.cancelled)
        assertEquals(OutputRouteReconnect.MAX_ATTEMPTS, result.attempts)
        assertEquals(10, attempts)
    }

    @Test fun reconnectReturnsTheFirstNewRouteAndHonoursCancellation() {
        var attempts = 0
        val restored = OutputRouteReconnect.connect(cancelled = { false }, pause = { true }) { if (++attempts == 3) "fresh" else null }
        assertEquals("fresh", restored.binding)
        assertEquals(3, restored.attempts)

        val cancelled = OutputRouteReconnect.connect(cancelled = { true }, attempt = { error("must not connect") })
        assertTrue(cancelled.cancelled)
        assertEquals(0, cancelled.attempts)
    }
}