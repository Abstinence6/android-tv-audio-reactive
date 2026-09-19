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

/** The ordering contract used by the service before source acquisition. */
internal object LiveTransitionCoordinator {
    fun execute(target: RenderMode, setForegroundTypes: () -> Unit, acquireNeededSources: () -> Unit, commit: () -> Unit) {
        setForegroundTypes()
        acquireNeededSources()
        commit()
    }
}
