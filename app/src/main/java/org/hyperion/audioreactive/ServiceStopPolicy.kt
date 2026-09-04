package org.hyperion.audioreactive

/**
 * A stop request must never create a foreground service. An existing service can be stopped with
 * Context.stopService; an idle process has nothing to dispatch.
 */
internal object ServiceStopPolicy {
    fun shouldStopExistingService(serviceExists: Boolean): Boolean = serviceExists
}
