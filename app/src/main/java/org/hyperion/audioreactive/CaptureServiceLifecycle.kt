package org.hyperion.audioreactive

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single lifecycle gate for one capture-service instance.
 *
 * Teardown publishes cancellation under the same monitor as every startup action. A start action
 * therefore either completes before cancellation linearizes, or sees cancellation and never runs.
 * Acquisitions still reject and release resources when cancellation has already won the gate.
 */
internal class CaptureServiceLifecycle(
    private val cleanup: () -> Unit,
    private val onTeardownRequested: () -> Unit = {},
) {
    private enum class Phase { IDLE, STARTING, ACTIVE, STOPPING, STOPPED }

    private val monitor = Any()
    private val teardownRequested = AtomicBoolean(false)
    private var phase = Phase.IDLE

    /** Atomically admits the exact route handoff only while teardown has not begun. */
    fun beginStart(reserveAdmission: () -> Boolean): Boolean = synchronized(monitor) {
        if (teardownRequested.get() || phase != Phase.IDLE) return false
        if (!reserveAdmission()) return false
        phase = Phase.STARTING
        true
    }

    /** Runs a start-only operation, rejecting starts cancelled before it can begin. */
    fun whileStarting(action: () -> Unit): Boolean = synchronized(monitor) {
        if (!isStarting()) return false
        action()
        return isStarting()
    }

    /**
     * Acquires and assigns a resource under the startup/teardown serialization gate. If teardown
     * wins during acquisition, the unassigned resource is immediately released instead.
     */
    fun <T> acquire(acquire: () -> T, release: (T) -> Unit, assign: (T) -> Unit): Boolean =
        synchronized(monitor) {
            if (!isStarting()) return false
            val resource = acquire()
            if (!isStarting()) {
                release(resource)
                return false
            }
            assign(resource)
            true
        }

    /** Publishes the active session while still serialized against teardown. */
    fun activate(action: () -> Unit): Boolean = synchronized(monitor) {
        if (!isStarting()) return false
        action()
        if (!isStarting()) return false
        phase = Phase.ACTIVE
        true
    }

    /** Linearizes cancellation, cleanup, and every startup action on the same monitor. */
    fun stop(): Boolean = synchronized(monitor) {
        if (!teardownRequested.compareAndSet(false, true)) return false
        onTeardownRequested()
        phase = Phase.STOPPING
        cleanup()
        phase = Phase.STOPPED
        true
    }

    private fun isStarting() = !teardownRequested.get() && phase == Phase.STARTING
}
