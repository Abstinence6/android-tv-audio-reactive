package org.hyperion.audioreactive

/** Process-local detailed status; never sent to a network endpoint. */
data class LocalCaptureStatus(
    val stage: String = "idle",
    val outputs: List<String> = emptyList(),
    val calibrated: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val frames: Long = 0,
    val fps: Float = 0f,
    val lastSend: String = "none",
    val rms: Float = 0f,
    val peak: Float = 0f,
)
object LocalStatusStore {
    @Volatile private var current = LocalCaptureStatus()
    fun snapshot() = current
    fun update(value: LocalCaptureStatus) { current = value }
    fun reset(stage: String = "idle") { current = LocalCaptureStatus(stage = stage) }
}
