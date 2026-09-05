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

    @Test fun replayedIntentWithTheSamePendingIdsCannotDiscardTheFirstBinding() {
        val discarded = mutableListOf<RouteBindingIds>()
        val admission = ServiceRouteAdmission { discarded += it }
        val first = RouteBindingIds("same-wled", null)

        assertTrue(admission.reserve(first))
        assertFalse(admission.reserve(first))
        assertTrue(discarded.isEmpty())
        assertEquals("router", admission.consume { "router" })
        assertTrue(discarded.isEmpty())
    }

    @Test fun lifecycleRejectedDistinctIntentDiscardsOnlyItsIncomingBinding() {
        val discarded = mutableListOf<RouteBindingIds>()
        val admission = ServiceRouteAdmission { discarded += it }
        val pending = RouteBindingIds("pending-wled", null)
        val rejected = RouteBindingIds("rejected-wled", null)

        assertTrue(admission.reserve(pending))
        admission.discardLifecycleRejectedStart(rejected)

        assertEquals(listOf(rejected), discarded)
        assertEquals("router", admission.consume { "router" })
    }

    @Test fun lifecycleRejectedReplayKeepsMatchingPendingBinding() {
        val discarded = mutableListOf<RouteBindingIds>()
        val admission = ServiceRouteAdmission { discarded += it }
        val pending = RouteBindingIds(null, "pending-hyperion")

        assertTrue(admission.reserve(pending))
        admission.discardLifecycleRejectedStart(pending)

        assertTrue(discarded.isEmpty())
        assertEquals("router", admission.consume { "router" })
    }

    @Test fun lifecycleRejectedIntentWithoutPendingBindingIsDiscarded() {
        val discarded = mutableListOf<RouteBindingIds>()
        val admission = ServiceRouteAdmission { discarded += it }
        val rejectedWhileStopped = RouteBindingIds("stopped-wled", null)
        val active = RouteBindingIds(null, "active-hyperion")

        admission.discardLifecycleRejectedStart(rejectedWhileStopped)
        assertTrue(admission.reserve(active))
        admission.consume { "router" }
        admission.discardLifecycleRejectedStart(active)

        assertEquals(listOf(rejectedWhileStopped, active), discarded)
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

    @Test fun repeatedTeardownDiscardsPendingBindingOnceButNeverDiscardsConsumedRouterBinding() {
        val discarded = mutableListOf<RouteBindingIds>()
        val admission = ServiceRouteAdmission { discarded += it }
        val pending = RouteBindingIds("pending-wled", null)

        assertTrue(admission.reserve(pending))
        admission.finish()
        admission.finish()
        assertEquals(listOf(pending), discarded)

        val consumed = RouteBindingIds(null, "consumed-hyperion")
        assertTrue(admission.reserve(consumed))
        admission.consume { "router" }
        admission.finish()
        admission.finish()
        assertEquals(listOf(pending), discarded)
    }
}
