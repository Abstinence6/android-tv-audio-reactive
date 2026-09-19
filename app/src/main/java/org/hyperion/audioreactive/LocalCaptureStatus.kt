package org.hyperion.audioreactive

/** Process-local detailed status; never sent to a network endpoint. */
data class LocalCaptureStatus(
    val captureStatus: CaptureStatus = CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT,
    val outputs: List<String> = emptyList(),
    val calibrated: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val frames: Long = 0,
    val fps: Float = 0f,
    val lastSendSucceeded: Boolean? = null,
    val rms: Float = 0f,
    val peak: Float = 0f,
    val frameTimeMs: Float = 0f,
    val worstFrameTimeMs: Float = 0f,
    val missedFrameDeadlines: Long = 0,
    /** Local-only bounded transition failure context; never contains endpoint or media data. */
    val transitionTarget: String? = null,
    val transitionError: String? = null,
    val transitionOwnedState: String? = null,
)
object LocalStatusStore {
    @Volatile private var current = LocalCaptureStatus()
    @Synchronized fun snapshot() = current
    @Synchronized fun update(value: LocalCaptureStatus) { current = value }
    @Synchronized fun reset(status: CaptureStatus = CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT) { current = LocalCaptureStatus(captureStatus = status) }
    @Synchronized fun recordFrameTiming(elapsedNanos: Long, deadlineNanos: Long) {
        val elapsed = elapsedNanos.coerceIn(0L, MAX_FRAME_NANOS)
        val millis = elapsed / 1_000_000f
        current = current.copy(frameTimeMs = millis, worstFrameTimeMs = maxOf(current.worstFrameTimeMs, millis), missedFrameDeadlines = current.missedFrameDeadlines + if (elapsed > deadlineNanos.coerceAtLeast(1L)) 1 else 0)
    }
    private const val MAX_FRAME_NANOS = 10_000_000_000L
}
