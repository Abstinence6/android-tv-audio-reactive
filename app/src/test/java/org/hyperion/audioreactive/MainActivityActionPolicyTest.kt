package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityActionPolicyTest {
    @Test fun exactParsingRejectsNearMatches() {
        assertEquals(MainActivityActionPolicy.Request.TOGGLE, MainActivityActionPolicy.parse(MainActivity.ACTION_TOGGLE))
        assertEquals(MainActivityActionPolicy.Request.NONE, MainActivityActionPolicy.parse("${MainActivity.ACTION_TOGGLE}.extra"))
        assertEquals(MainActivityActionPolicy.Request.NONE, MainActivityActionPolicy.parse(null))
    }

    @Test fun offOnlyStopsAnExistingAppOwnedService() {
        assertEquals(MainActivityActionPolicy.Decision.NONE, MainActivityActionPolicy.decide(MainActivity.ACTION_OFF, false))
        assertEquals(MainActivityActionPolicy.Decision.STOP_APP_OWNED_SERVICE, MainActivityActionPolicy.decide(MainActivity.ACTION_OFF, true))
    }

    @Test fun onAndToggleNeverStartCaptureDirectly() {
        assertEquals(MainActivityActionPolicy.Decision.REQUEST_VISIBLE_CAPTURE_FLOW, MainActivityActionPolicy.decide(MainActivity.ACTION_ON, false))
        assertEquals(MainActivityActionPolicy.Decision.REQUEST_VISIBLE_CAPTURE_FLOW, MainActivityActionPolicy.decide(MainActivity.ACTION_TOGGLE, false))
        assertEquals(MainActivityActionPolicy.Decision.STOP_APP_OWNED_SERVICE, MainActivityActionPolicy.decide(MainActivity.ACTION_TOGGLE, true))
    }
}
