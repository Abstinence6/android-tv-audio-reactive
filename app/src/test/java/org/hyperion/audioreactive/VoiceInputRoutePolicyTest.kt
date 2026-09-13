package org.hyperion.audioreactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputRoutePolicyTest {
    @Test fun selectedExternalDeviceRemovalAlwaysStopsCapture() {
        assertTrue(VoiceInputRoutePolicy.selectedDeviceWasRemoved(42, listOf(9, 42)))
        assertFalse(VoiceInputRoutePolicy.selectedDeviceWasRemoved(42, listOf(9, 10)))
        assertFalse(VoiceInputRoutePolicy.selectedDeviceWasRemoved(null, listOf(42)))
    }

    @Test fun concreteRoutingAwayFromTheSelectedDeviceAlwaysStopsCapture() {
        assertTrue(VoiceInputRoutePolicy.routedAwayFromSelectedDevice(42, 9))
        assertFalse(VoiceInputRoutePolicy.routedAwayFromSelectedDevice(42, 42))
        assertFalse(VoiceInputRoutePolicy.routedAwayFromSelectedDevice(42, null))
        assertFalse(VoiceInputRoutePolicy.routedAwayFromSelectedDevice(null, 9))
    }
}
