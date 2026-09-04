package org.hyperion.audioreactive

/** Exact one-shot route IDs carried by a capture-service intent. */
internal data class RouteBindingIds(val wled: String?, val hyperion: String?)

/**
 * Service-local admission ownership. A route handoff is either pending consumption by the
 * worker, consumed by its OutputRouter, or absent. Every rejected or abandoned pending handoff
 * is discarded exactly once.
 */
internal class ServiceRouteAdmission(private val discard: (RouteBindingIds) -> Unit) {
    private enum class State { IDLE, PENDING, CONSUMED }

    private var state = State.IDLE
    private var pending: RouteBindingIds? = null

    /** Reserves one admission; replay of the same intent leaves its pending handoff intact. */
    @Synchronized fun reserve(ids: RouteBindingIds): Boolean {
        if (state != State.IDLE) {
            // A framework replay may carry the exact same one-shot IDs. It belongs to the
            // current admission and must never discard that admission's pending binding.
            if (state == State.PENDING && pending == ids) return false
            discard(ids)
            return false
        }
        pending = ids
        state = State.PENDING
        return true
    }

    /**
     * Constructs the router while ownership remains pending. On success the router owns the
     * consumed route; on failure the still-pending exact IDs are discarded.
     */
    @Synchronized fun <T> consume(build: (RouteBindingIds) -> T): T {
        check(state == State.PENDING)
        val ids = checkNotNull(pending)
        return try {
            build(ids).also {
                pending = null
                state = State.CONSUMED
            }
        } catch (failure: Exception) {
            pending = null
            state = State.IDLE
            discard(ids)
            throw failure
        }
    }

    /** Used for invalid intents, service destruction, stop, and worker cancellation. */
    @Synchronized fun discardPending() {
        pending?.let(discard)
        pending = null
        if (state == State.PENDING) state = State.IDLE
    }

    /** Releases admission bookkeeping after router teardown; it never discards consumed IDs. */
    @Synchronized fun finish() {
        discardPending()
        state = State.IDLE
    }
}
