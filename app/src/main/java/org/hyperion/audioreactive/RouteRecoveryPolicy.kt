package org.hyperion.audioreactive

/**
 * Recovery after an output failure is deliberately local-only.  This policy does not retain a
 * route binding, projection result, or capture resource; a permitted retry enters the ordinary
 * fresh-preflight and user-consent flow as a new admission.
 */
internal object RouteRecoveryPolicy {
    enum class Origin { LOCAL_CAPTURE_BUTTON, REMOTE_ACTION, MQTT }
    enum class Decision { ORDINARY_FLOW, START_NEW_LOCAL_ADMISSION, REQUIRE_LOCAL_BUTTON, IGNORE }

    fun decide(
        origin: Origin,
        status: CaptureStatus,
        serviceActive: Boolean,
        admissionPending: Boolean,
    ): Decision {
        if (serviceActive || admissionPending) return Decision.IGNORE
        if (status != CaptureStatus.ROUTE_LOST) return Decision.ORDINARY_FLOW
        return if (origin == Origin.LOCAL_CAPTURE_BUTTON) {
            Decision.START_NEW_LOCAL_ADMISSION
        } else {
            Decision.REQUIRE_LOCAL_BUTTON
        }
    }
}
