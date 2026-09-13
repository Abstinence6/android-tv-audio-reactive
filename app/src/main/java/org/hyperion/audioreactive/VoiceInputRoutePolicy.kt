package org.hyperion.audioreactive

/** Fail closed if the selected external microphone is removed or AudioRecord moves away from it. */
object VoiceInputRoutePolicy {
    fun selectedDeviceWasRemoved(selectedDeviceId: Int?, removedDeviceIds: Iterable<Int>): Boolean =
        selectedDeviceId != null && removedDeviceIds.any { it == selectedDeviceId }

    /** Null is transient while AudioRecord initializes; a concrete different route is unsafe. */
    fun routedAwayFromSelectedDevice(selectedDeviceId: Int?, routedDeviceId: Int?): Boolean =
        selectedDeviceId != null && routedDeviceId != null && routedDeviceId != selectedDeviceId
}
