package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRouteAdmissionTest {
    @Test fun secondIntentBeforeFirstBindingIsConsumedIsRejectedAndItsBindingDiscarded() {
        val discarded = mutableListOf<RouteBindingIds>()
        val admission = ServiceRouteAdmission { discarded += it }
        val first = RouteBindingIds("first-wled", null)
        val second = RouteBindingIds("second-wled", null)

        assertTrue(admission.reserve(first))
        assertFalse(admission.reserve(second))
        assertEquals(listOf(second), discarded)
        assertEquals("router", admission.consume { "router" })
        admission.finish()
        assertEquals(listOf(second), discarded)
    }

    @Test fun destructionBeforeConsumptionDiscardsTheExactReservedBinding() {
        val discarded = mutableListOf<RouteBindingIds>()
        val admission = ServiceRouteAdmission { discarded += it }
        val pending = RouteBindingIds(null, "hyperion")

        assertTrue(admission.reserve(pending))
        admission.finish()
        assertEquals(listOf(pending), discarded)
        assertTrue(admission.reserve(RouteBindingIds("next", null)))
    }

    @Test fun failedConsumptionDiscardsPendingBindingAndMakesNewAdmissionPossible() {
        val discarded = mutableListOf<RouteBindingIds>()
        val admission = ServiceRouteAdmission { discarded += it }
        val pending = RouteBindingIds("wled", null)

        assertTrue(admission.reserve(pending))
        runCatching { admission.consume<String> { error("route creation failed") } }
        assertEquals(listOf(pending), discarded)
        assertTrue(admission.reserve(RouteBindingIds(null, "next-hyperion")))
    }
}
