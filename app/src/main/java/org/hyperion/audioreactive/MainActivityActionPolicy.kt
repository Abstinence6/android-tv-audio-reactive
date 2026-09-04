package org.hyperion.audioreactive

/** Exported MainActivity remote-action contract. Starts always re-enter the ordinary consent flow. */
object MainActivityActionPolicy {
    enum class Request { NONE, TOGGLE, ON, OFF }
    enum class Decision { NONE, STOP_APP_OWNED_SERVICE, REQUEST_VISIBLE_CAPTURE_FLOW }

    fun parse(action: String?): Request = when (action) {
        MainActivity.ACTION_TOGGLE -> Request.TOGGLE
        MainActivity.ACTION_ON -> Request.ON
        MainActivity.ACTION_OFF -> Request.OFF
        else -> Request.NONE
    }

    fun decide(action: String?, appOwnedServiceActive: Boolean): Decision = decide(parse(action), appOwnedServiceActive)

    fun decide(request: Request, appOwnedServiceActive: Boolean): Decision = when (request) {
        Request.OFF -> if (appOwnedServiceActive) Decision.STOP_APP_OWNED_SERVICE else Decision.NONE
        Request.TOGGLE -> if (appOwnedServiceActive) Decision.STOP_APP_OWNED_SERVICE else Decision.REQUEST_VISIBLE_CAPTURE_FLOW
        Request.ON -> if (appOwnedServiceActive) Decision.NONE else Decision.REQUEST_VISIBLE_CAPTURE_FLOW
        Request.NONE -> Decision.NONE
    }
}
