package org.hyperion.audioreactive

/**
 * Finite recovery after a live output-send failure. Each attempt must establish a newly
 * revalidated route; it never reuses a consumed binding or starts a capture admission.
 */
internal object OutputRouteReconnect {
    const val MAX_ATTEMPTS = CapturePreflightRetry.MAX_ATTEMPTS

    fun <T> connect(
        cancelled: () -> Boolean,
        onAttempt: (Int) -> Unit = {},
        pause: (Long) -> Boolean = { millis -> try { Thread.sleep(millis); true } catch (_: InterruptedException) { false } },
        attempt: () -> T?,
    ): CapturePreflightRetry.Result<T> = CapturePreflightRetry.bind(cancelled, onAttempt, pause, attempt)
}
