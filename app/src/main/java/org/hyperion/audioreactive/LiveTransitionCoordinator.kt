package org.hyperion.audioreactive

/** Sanitises transition failure context before it reaches local status or logcat. */
internal object TransitionDiagnostics {
    data class OwnedState(val projection: Boolean, val audio: Boolean, val video: Boolean, val foreground: String) {
        override fun toString() = "projection=$projection,audio=$audio,video=$video,fgs=$foreground"
    }

    fun report(target: RenderMode, failure: Throwable, owned: OwnedState) {
        LocalStatusStore.update(LocalStatusStore.snapshot().copy(
            transitionTarget = target.name,
            transitionError = failure.javaClass.simpleName.take(80),
            transitionOwnedState = owned.toString().take(160),
        ))
    }
}

internal enum class TerminalCause {
    TRANSITION_EXCEPTION,
    UNEXPECTED_PROJECTION_REVOKE,
    ROUTE_LOST,
    MICROPHONE_LOSS,
    EXPLICIT_STOP,
    DESTROY;

    val wireName get() = name.lowercase()
}

/** First terminal event wins so re-entrant teardown cannot obscure the initiating failure. */
internal object TerminalDiagnostics {
    fun capture(
        cause: TerminalCause,
        requested: RenderMode?,
        committed: RenderMode?,
        epoch: Long?,
        owned: TransitionDiagnostics.OwnedState,
        foregroundTypes: Int,
    ) {
        val status = LocalStatusStore.snapshot()
        if (status.terminalCause != null) return
        LocalStatusStore.update(status.copy(
            terminalCause = cause.wireName,
            terminalRequestedMode = requested?.name,
            terminalCommittedMode = committed?.name,
            terminalEpoch = epoch,
            terminalOwnedState = owned.toString().take(160),
            terminalForegroundTypes = foregroundTypes.coerceAtLeast(0),
        ))
    }
}

/** The ordering contract used by the service before source acquisition. */
internal object LiveTransitionCoordinator {
    fun execute(target: RenderMode, setForegroundTypes: () -> Unit, acquireNeededSources: () -> Unit, commit: () -> Unit) {
        setForegroundTypes()
        acquireNeededSources()
        commit()
    }
}
