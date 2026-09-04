package org.hyperion.audioreactive

/** Public-safe capture lifecycle values for the UI. */
enum class CaptureStatus(val uiText: String, val isActive: Boolean = false) {
    NEEDS_MEDIA_PROJECTION_CONSENT("Idle — playback capture needs RECORD_AUDIO and user MediaProjection consent."),
    PREPARING_PROJECTION("Preparing MediaProjection."),
    PREPARING_AUDIO_RECORD("Preparing playback audio capture."),
    AUDIO_RECORD_INIT_FAILED("Playback audio capture could not be initialized."),
    AUDIO_RECORD_START_FAILED("Playback audio capture could not be started."),
    AUDIO_RECORD_INIT_TIMEOUT("Playback audio capture initialization timed out."),
    ROUTER_INIT_FAILED("Output routing could not be initialized."),
    ROUTE_LOST("Output route was lost; capture stopped and outputs were cleaned up. Revalidate manually before starting again."),
    CAPTURE_ACTIVE("Audio effects active; routes are locked until capture stops.", true),
    CAPTURE_ACTIVE_AUDIO("Audio capture active; routes are locked.", true),
    CAPTURE_ACTIVE_VIDEO("Video capture active; routes are locked.", true),
    CAPTURE_ACTIVE_VIDEO_AUDIO("Audio and video capture active; routes are locked.", true),
    VIDEO_UNAVAILABLE_OR_PROTECTED("Video is unavailable or protected.", true),
}

/**
 * Serialized, token-scoped startup state. Every transition validates the one current token.
 * A terminal transition invalidates all delayed watchdog/capture callbacks for that token.
 */
internal class CaptureStartupState {
    private enum class Phase { IDLE, PREPARING_PROJECTION, START_ADMITTED, PREPARING_AUDIO, AUDIO_INITIALIZED, AUDIO_STARTED, ACTIVE, STOPPED }

    private var generation = 0L
    private var phase = Phase.IDLE
    @Volatile var status: CaptureStatus = CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT
        private set

    @Synchronized fun begin(token: Long = generation + 1): Long {
        require(token > generation) { "Capture token must be monotonic" }
        generation = token
        phase = Phase.PREPARING_PROJECTION
        status = CaptureStatus.PREPARING_PROJECTION
        return generation
    }

    /**
     * The service has one MediaProjection token and can admit exactly one start command for it.
     * Rejected commands deliberately have no lifecycle side effects: a newer Android startId
     * cannot use stopSelfResult without also stopping the admitted owner.
     */
    @Synchronized fun admitStart(token: Long, startId: Int): CaptureStartAdmission {
        if (startId <= 0 || token != generation || phase != Phase.PREPARING_PROJECTION) return CaptureStartAdmission.REJECTED
        phase = Phase.START_ADMITTED
        return CaptureStartAdmission.ADMITTED
    }

    @Synchronized fun preparingAudioRecord(token: Long): CaptureStatus? = transition(token, Phase.START_ADMITTED, Phase.PREPARING_AUDIO, CaptureStatus.PREPARING_AUDIO_RECORD)
    @Synchronized fun audioRecordInitialized(token: Long): Boolean = transition(token, Phase.PREPARING_AUDIO, Phase.AUDIO_INITIALIZED, null) != null
    @Synchronized fun audioRecordStarted(token: Long): Boolean = transition(token, Phase.AUDIO_INITIALIZED, Phase.AUDIO_STARTED, null) != null
    @Synchronized fun canCreateRouter(token: Long): Boolean = token == generation && phase == Phase.AUDIO_STARTED
    @Synchronized fun activate(token: Long): CaptureStatus? = transition(token, Phase.AUDIO_STARTED, Phase.ACTIVE, CaptureStatus.CAPTURE_ACTIVE)
    @Synchronized fun isActive(token: Long): Boolean = token == generation && phase == Phase.ACTIVE
    @Synchronized fun isCurrent(token: Long): Boolean = token == generation && phase != Phase.STOPPED && phase != Phase.IDLE

    /** The first terminal outcome wins; later callbacks are deliberately ignored. */
    @Synchronized fun stop(token: Long, finalStatus: CaptureStatus): CaptureStatus? {
        if (token != generation || phase == Phase.STOPPED || phase == Phase.IDLE) return null
        phase = Phase.STOPPED
        status = finalStatus
        return status
    }

    @Synchronized fun timedOut(token: Long): CaptureStatus? {
        if (token != generation || phase !in setOf(Phase.PREPARING_PROJECTION, Phase.START_ADMITTED, Phase.PREPARING_AUDIO, Phase.AUDIO_INITIALIZED, Phase.AUDIO_STARTED)) return null
        phase = Phase.STOPPED
        status = CaptureStatus.AUDIO_RECORD_INIT_TIMEOUT
        return status
    }
    @Synchronized fun isTerminalFailure(): Boolean = phase == Phase.STOPPED && status != CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT

    private fun transition(token: Long, required: Phase, next: Phase, nextStatus: CaptureStatus?): CaptureStatus? {
        if (token != generation || phase != required) return null
        phase = next
        if (nextStatus != null) status = nextStatus
        return status
    }
}

/** Pure service-command seam: only the first startId for a token can own capture initialization. */
internal enum class CaptureStartAdmission { ADMITTED, REJECTED }

/** Keeps the startup watchdog policy deterministic and unit-testable. */
internal object CaptureStartupTimeoutPolicy {
    const val TIMEOUT_MILLIS = 8_000L
    fun expired(elapsedMillis: Long, timeoutMillis: Long = TIMEOUT_MILLIS): Boolean = elapsedMillis >= timeoutMillis
}
