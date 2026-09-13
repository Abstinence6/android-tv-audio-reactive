package org.hyperion.audioreactive

/** Pure availability rules for the optional physical microphone input. */
object VoiceInputPolicy {
    fun shown(renderMode: RenderMode, microphoneAvailable: Boolean): Boolean =
        microphoneAvailable && (renderMode == RenderMode.AUDIO || renderMode == RenderMode.VIDEO_AUDIO)

    /** A disconnected microphone never silently changes a persisted choice into a capture source. */
    fun usable(input: AudioInput, microphoneAvailable: Boolean): Boolean =
        input != AudioInput.MICROPHONE || microphoneAvailable
}
