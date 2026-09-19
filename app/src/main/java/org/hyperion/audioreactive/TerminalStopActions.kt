package org.hyperion.audioreactive

/** Runs terminal diagnostics before the lifecycle closes capture resources. */
internal class CaptureTerminalStop(
    private val lifecycle: CaptureServiceLifecycle,
    private val captureDiagnostic: (TerminalCause) -> Unit,
) {
    fun stop(cause: TerminalCause, beforeCleanup: () -> Unit = {}): Boolean = lifecycle.stop {
        captureDiagnostic(cause)
        beforeCleanup()
    }
}

/** The service's complete early command dispatcher. */
internal object AudioReactiveServiceCommandDispatch {
    enum class Result { TERMINAL_STOP, LOCAL_TRANSITION, NORMAL_START }

    fun dispatch(
        action: String?,
        terminalStop: (TerminalCause) -> Boolean,
        localTransition: () -> Unit,
    ): Result = when (action) {
        AudioReactiveService.ACTION_EXPLICIT_STOP -> {
            terminalStop(TerminalCause.EXPLICIT_STOP)
            Result.TERMINAL_STOP
        }
        AudioReactiveService.ACTION_LOCAL_TRANSITION -> {
            localTransition()
            Result.LOCAL_TRANSITION
        }
        else -> Result.NORMAL_START
    }

    fun onDestroy(terminalStop: (TerminalCause) -> Boolean) {
        terminalStop(TerminalCause.DESTROY)
    }
}