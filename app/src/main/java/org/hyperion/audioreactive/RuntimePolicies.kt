package org.hyperion.audioreactive

/** A brightness blackout and audio silence both bypass output smoothing. */
object FrameSmoothingPolicy {
    fun immediateBlack(settings: AudioSettings, signalPresent: Boolean?): Boolean =
        settings.brightness == 0f || (signalPresent == false && !VideoAudioSilenceBrightnessPolicy.retainsVideo(settings))
    /** Legacy callers are audio-only and retain their historical silence blackout. */
    fun immediateBlack(brightness: Float, signalPresent: Boolean?): Boolean = brightness == 0f || signalPresent == false
}

/** An explicit VIDEO_AUDIO toggle changes only silence behavior; protected/black source handling remains terminal. */
object VideoAudioSilenceBrightnessPolicy {
    fun retainsVideo(settings: AudioSettings) = settings.renderMode == RenderMode.VIDEO_AUDIO &&
        settings.silenceFadeEnabled
}

/** Fixed-state, timestamp-driven VIDEO_AUDIO silence transition with no render-time allocation. */
class SilenceBrightnessController {
    enum class State { ACTIVE, HOLDING, FADING, SILENT, RECOVERING }
    private var state = State.ACTIVE
    private var stateSinceNanos = 0L
    private var brightnessAtTransition = 0f

    fun currentState() = state

    fun compose(settings: AudioSettings, signalPresent: Boolean?, timestampNanos: Long): Float {
        if (settings.renderMode != RenderMode.VIDEO_AUDIO || !settings.silenceFadeEnabled) {
            reset(timestampNanos)
            return settings.brightness
        }
        val now = timestampNanos.coerceAtLeast(stateSinceNanos)
        if (signalPresent == true) {
            if (state != State.ACTIVE) {
                brightnessAtTransition = valueAt(settings, now)
                state = State.RECOVERING
                stateSinceNanos = now
            }
        } else if (signalPresent == false && state == State.ACTIVE) {
            brightnessAtTransition = settings.brightness
            state = State.HOLDING
            stateSinceNanos = now
        }
        return valueAt(settings, now).also { value ->
            if (state == State.HOLDING && now - stateSinceNanos >= settings.silenceHoldMillis * NANOS_PER_MILLI) {
                brightnessAtTransition = settings.brightness
                state = State.FADING
                stateSinceNanos = now
            } else if (state == State.FADING && value <= settings.videoAudioSilenceBrightnessFloor) {
                state = State.SILENT
            } else if (state == State.RECOVERING && value >= settings.brightness) {
                state = State.ACTIVE
            }
        }
    }

    private fun valueAt(settings: AudioSettings, now: Long): Float = when (state) {
        State.ACTIVE, State.HOLDING -> settings.brightness
        State.SILENT -> settings.videoAudioSilenceBrightnessFloor
        State.FADING -> interpolate(brightnessAtTransition, settings.videoAudioSilenceBrightnessFloor, now - stateSinceNanos, settings.silenceFadeMillis)
        State.RECOVERING -> interpolate(brightnessAtTransition, settings.brightness, now - stateSinceNanos, settings.silenceFadeMillis)
    }.coerceIn(0f, settings.brightness)

    private fun interpolate(from: Float, to: Float, elapsedNanos: Long, durationMillis: Int): Float {
        val fraction = (elapsedNanos.toDouble() / (durationMillis.coerceAtLeast(1) * NANOS_PER_MILLI)).coerceIn(0.0, 1.0).toFloat()
        return from + (to - from) * fraction
    }

    private fun reset(timestampNanos: Long) { state = State.ACTIVE; stateSinceNanos = timestampNanos; brightnessAtTransition = 0f }
    private companion object { const val NANOS_PER_MILLI = 1_000_000L }
}

/** `acquireLatestImage` plus a two-image reader bounds capture backlog to one obsolete image. */
object VideoLatencyPolicy {
    const val IMAGE_READER_MAX_IMAGES = 2
    enum class Tick { SEND_FRESH, HOLD }
    /** A held tick deliberately does not resend the prior RGB frame. */
    fun dispatch(hasFreshImage: Boolean): Tick = if (hasFreshImage) Tick.SEND_FRESH else Tick.HOLD
}

/**
 * Production capture hand-off: acquire at most one latest frame, render and send it once, then
 * always release it. This is platform-neutral so JVM tests can exercise the same ownership and
 * failure path used with ImageReader, while ImageReader remains the Android boundary.
 */
internal object FreshFrameDispatcher {
    enum class Result { NO_FRESH_FRAME, SENT, RENDER_REJECTED }

    fun <Frame> dispatch(
        acquireLatest: () -> Frame?,
        release: (Frame) -> Unit,
        render: (Frame) -> ByteArray?,
        send: (ByteArray) -> Unit,
    ): Result {
        val frame = acquireLatest() ?: return Result.NO_FRESH_FRAME
        try {
            val rendered = render(frame) ?: return Result.RENDER_REJECTED
            send(rendered)
            return Result.SENT
        } finally {
            release(frame)
        }
    }
}

/** A discovery result may only mutate persisted inventory while its admission epoch remains idle. */
object DiscoveryCompletionPolicy {
    fun mayMerge(queuedGeneration: Long, currentGeneration: Long, captureOrAdmissionActive: Boolean): Boolean = queuedGeneration == currentGeneration && !captureOrAdmissionActive
}
