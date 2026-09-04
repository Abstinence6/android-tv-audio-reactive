package org.hyperion.audioreactive

/** Finite, cancellable retry around read-only route validation before MediaProjection is requested. */
object CapturePreflightRetry {
    const val MAX_ATTEMPTS = 10
    /** Keeps a retrying button press responsive while avoiding a hot loop between bounded probes. */
    const val RETRY_DELAY_MILLIS = 350L

    fun <T> bind(
        cancelled: () -> Boolean,
        onAttempt: (Int) -> Unit = {},
        pause: (Long) -> Boolean = { millis -> try { Thread.sleep(millis); true } catch (_: InterruptedException) { false } },
        attempt: () -> T?,
    ): Result<T> {
        var attempts = 0
        while (attempts < MAX_ATTEMPTS && !cancelled()) {
            attempts++
            onAttempt(attempts)
            attempt()?.let { return Result(it, attempts, cancelled = false) }
            if (attempts < MAX_ATTEMPTS && !cancelled() && !pause(RETRY_DELAY_MILLIS)) {
                return Result(null, attempts, cancelled = true)
            }
        }
        return Result(null, attempts, cancelled = cancelled())
    }

    data class Result<T>(val binding: T?, val attempts: Int, val cancelled: Boolean)
}
