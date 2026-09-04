package org.hyperion.audioreactive

/** A brightness blackout and audio silence both bypass output smoothing. */
object FrameSmoothingPolicy {
    fun immediateBlack(brightness: Float, signalPresent: Boolean?): Boolean =
        brightness == 0f || signalPresent == false
}

/** A discovery result may only mutate persisted inventory while its admission epoch remains idle. */
object DiscoveryCompletionPolicy {
    fun mayMerge(queuedGeneration: Long, currentGeneration: Long, captureOrAdmissionActive: Boolean): Boolean =
        queuedGeneration == currentGeneration && !captureOrAdmissionActive
}