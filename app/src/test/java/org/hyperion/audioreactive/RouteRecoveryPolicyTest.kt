package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteRecoveryPolicyTest {
    @Test fun onlyLocalCaptureButtonCanStartANewAdmissionAfterRouteLoss() {
        assertEquals(
            RouteRecoveryPolicy.Decision.START_NEW_LOCAL_ADMISSION,
            RouteRecoveryPolicy.decide(RouteRecoveryPolicy.Origin.LOCAL_CAPTURE_BUTTON, CaptureStatus.ROUTE_LOST, serviceActive = false, admissionPending = false),
        )
        assertEquals(
            RouteRecoveryPolicy.Decision.REQUIRE_LOCAL_BUTTON,
            RouteRecoveryPolicy.decide(RouteRecoveryPolicy.Origin.REMOTE_ACTION, CaptureStatus.ROUTE_LOST, serviceActive = false, admissionPending = false),
        )
        assertEquals(
            RouteRecoveryPolicy.Decision.REQUIRE_LOCAL_BUTTON,
            RouteRecoveryPolicy.decide(RouteRecoveryPolicy.Origin.MQTT, CaptureStatus.ROUTE_LOST, serviceActive = false, admissionPending = false),
        )
    }

    @Test fun activeOrPendingCaptureCannotCreateASecondRecoveryAdmission() {
        listOf(
            RouteRecoveryPolicy.Origin.LOCAL_CAPTURE_BUTTON,
            RouteRecoveryPolicy.Origin.REMOTE_ACTION,
            RouteRecoveryPolicy.Origin.MQTT,
        ).forEach { origin ->
            assertEquals(
                RouteRecoveryPolicy.Decision.IGNORE,
                RouteRecoveryPolicy.decide(origin, CaptureStatus.ROUTE_LOST, serviceActive = true, admissionPending = false),
            )
            assertEquals(
                RouteRecoveryPolicy.Decision.IGNORE,
                RouteRecoveryPolicy.decide(origin, CaptureStatus.ROUTE_LOST, serviceActive = false, admissionPending = true),
            )
        }
    }

    @Test fun nonFailureCaptureStartsKeepTheirExistingOrdinaryFlow() {
        assertEquals(
            RouteRecoveryPolicy.Decision.ORDINARY_FLOW,
            RouteRecoveryPolicy.decide(RouteRecoveryPolicy.Origin.REMOTE_ACTION, CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT, serviceActive = false, admissionPending = false),
        )
    }
}
