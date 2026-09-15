package org.hyperion.audioreactive

import android.media.Image
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** Fixed-buffer RGBA ImageReader downsampler and video/audio compositor. */
class VideoFrameProcessor(private val width: Int, private val height: Int) {
    val video = ByteArray(width * height * 3)
    val composite = ByteArray(video.size)
    private var blackFrames = 0
    private var activeVideoAudioEffect: VideoAudioEffect? = null
    private var beatPulseAudioActive = false
    private var lastConsumedBeatSequence = 0L
    private var beatPulse = 0f
    private var lastPulseTimestampNanos = 0L
    private val silenceBrightness = SilenceBrightnessController()

    fun copyImage(image: Image): Boolean {
        val plane = image.planes[0]; val data = plane.buffer; val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
        val sx = image.width.toFloat() / width; val sy = image.height.toFloat() / height
        var y = 0; var out = 0; var total = 0
        while (y < height) {
            val sourceY = (y * sy).toInt().coerceIn(0, image.height - 1); var x = 0
            while (x < width) {
                val sourceX = (x * sx).toInt().coerceIn(0, image.width - 1); val at = sourceY * rowStride + sourceX * pixelStride
                val r = data.get(at).toInt() and 255; val g = data.get(at + 1).toInt() and 255; val b = data.get(at + 2).toInt() and 255
                video[out++] = r.toByte(); video[out++] = g.toByte(); video[out++] = b.toByte(); total += r + g + b; x++
            }
            y++
        }
        if (total <= width * height * 12) blackFrames++ else blackFrames = 0
        return blackFrames < BLACK_FRAME_HOLD
    }

    fun compose(features: AudioFeatures?, settings: AudioSettings, timestampNanos: Long = System.nanoTime()): ByteArray {
        val silenceBreathing = settings.videoAudioEffect == VideoAudioEffect.SILENCE_BREATHING
        val hasAudioAccent = settings.renderMode == RenderMode.VIDEO_AUDIO && (features?.signalPresent == true || silenceBreathing)
        updateBeatPulse(features, settings, timestampNanos, hasAudioAccent)
        var p = 0; var zone = 0
        while (p < video.size) {
            var r = video[p].toInt() and 255; var g = video[p + 1].toInt() and 255; var b = video[p + 2].toInt() and 255
            val gain = if (hasAudioAccent) videoAudioGain(features, settings.videoAudioEffect, zone, timestampNanos) else 0f
            val silenceBase = silenceBrightness.compose(settings, features?.signalPresent, timestampNanos)
            val breathingFloor = if (silenceBreathing && features?.signalPresent != true) SILENCE_BREATH_MIN_BRIGHTNESS else 0f
            val brightness = max(silenceBase, breathingFloor) * (1f + settings.audioBoost * gain)
            if (settings.renderMode != RenderMode.AUDIO) {
                val saturation = settings.videoSaturationPercent.coerceIn(VideoSaturationPolicy.MIN_PERCENT, VideoSaturationPolicy.MAX_PERCENT) / 100f
                val average = (r + g + b) / 3f
                when (settings.videoEffect) {
                    VideoEffect.NORMAL -> Unit
                    VideoEffect.SATURATION -> {
                        r = (average + (r - average) * saturation).toInt().coerceIn(0, 255)
                        g = (average + (g - average) * saturation).toInt().coerceIn(0, 255)
                        b = (average + (b - average) * saturation).toInt().coerceIn(0, 255)
                    }
                    VideoEffect.CONTRAST -> {
                        r = (128f + (r - 128f) * saturation).toInt().coerceIn(0, 255)
                        g = (128f + (g - 128f) * saturation).toInt().coerceIn(0, 255)
                        b = (128f + (b - 128f) * saturation).toInt().coerceIn(0, 255)
                    }
                }
            }
            composite[p] = (r * brightness).toInt().coerceIn(0, 255).toByte()
            composite[p + 1] = (g * brightness).toInt().coerceIn(0, 255).toByte()
            composite[p + 2] = (b * brightness).toInt().coerceIn(0, 255).toByte()
            p += 3; zone++
        }
        return composite
    }

    /**
     * Beat events are consumed once. Entering any beat-driven accent baselines the retained analyzer
     * event before rendering, so effect/mode activation cannot replay it. A 500 ms monotonic age bound
     * covers a 200 ms 5 FPS frame interval, a 21 ms captured block, and render scheduling while
     * rejecting stalled retained events.
     */
    private fun updateBeatPulse(features: AudioFeatures?, settings: AudioSettings, timestampNanos: Long, hasAudioAccent: Boolean) {
        val audioActive = hasAudioAccent && settings.videoAudioEffect.usesBeatEvents()
        if (activeVideoAudioEffect != settings.videoAudioEffect) {
            activeVideoAudioEffect = settings.videoAudioEffect
            beatPulse = 0f
            lastPulseTimestampNanos = timestampNanos
        }
        if (audioActive && !beatPulseAudioActive) {
            beatPulseAudioActive = true
            beatPulse = 0f
            lastPulseTimestampNanos = timestampNanos
            lastConsumedBeatSequence = features?.beatSequence ?: lastConsumedBeatSequence
            return
        }
        if (!audioActive) {
            beatPulseAudioActive = false
            beatPulse = 0f
            lastPulseTimestampNanos = timestampNanos
            return
        }
        val now = timestampNanos.coerceAtLeast(lastPulseTimestampNanos)
        val elapsed = now - lastPulseTimestampNanos
        if (elapsed > 0L) {
            val tempoDecay = if (features!!.tempoConfidence >= .25f && features.tempoBpm > 0f) (60_000f / features.tempoBpm).coerceIn(180f, 900f) else DEFAULT_PULSE_DECAY_MILLIS
            beatPulse *= kotlin.math.exp(-elapsed.toDouble() / (tempoDecay * 1_000_000.0)).toFloat()
        }
        lastPulseTimestampNanos = now
        val sequence = features!!.beatSequence
        if (sequence > lastConsumedBeatSequence) {
            lastConsumedBeatSequence = sequence
            val ageNanos = now - features.beatTimestampNanos
            if (features.beatTimestampNanos > 0L && ageNanos in 0L..MAX_BEAT_EVENT_AGE_NANOS) {
                beatPulse = max(beatPulse, (MIN_PULSE_ATTACK + features.beatStrength * PULSE_STRENGTH_RANGE).coerceIn(0f, 1f))
            }
        }
        beatPulse = beatPulse.coerceIn(0f, 1f)
    }

    /** Audio only applies a non-negative brightness accent; it never replaces source hue/chroma. */
    private fun videoAudioGain(f: AudioFeatures?, effect: VideoAudioEffect, zone: Int, timestampNanos: Long): Float {
        if (f?.signalPresent != true) return if (effect == VideoAudioEffect.SILENCE_BREATHING) {
            (.35f + .25f * sin(timestampNanos / 1_000_000_000.0 * SILENCE_BREATH_RADIANS_PER_SECOND).toFloat()).coerceIn(0f, 1f)
        } else 0f
        val position = (zone % PERIMETER_ZONES) / (PERIMETER_ZONES - 1f)
        val centred = abs(position - .5f) * 2f
        val band = f.bands[(position * (AudioFeatures.BAND_COUNT - 1)).toInt().coerceIn(0, AudioFeatures.BAND_COUNT - 1)]
        val comet = (f.onset * (1f - abs(position - f.onset).coerceIn(0f, 1f))).coerceIn(0f, 1f)
        return when (effect) {
            VideoAudioEffect.BRIGHTNESS_PULSE -> f.rms
            VideoAudioEffect.BEAT_PULSE -> beatPulse
            VideoAudioEffect.EQ -> band
            VideoAudioEffect.COMET -> comet
            VideoAudioEffect.RIPPLE -> (f.onset - centred * .7f).coerceAtLeast(0f)
            VideoAudioEffect.BASS_SWEEP -> f.bass * (1f - abs(position - f.bass).coerceIn(0f, 1f))
            VideoAudioEffect.SPECTRAL_BANDS -> band
            VideoAudioEffect.CENTER_BEAT_BURST -> beatPulse * (1f - centred)
            VideoAudioEffect.EDGE_PULSE -> f.onset * centred
            VideoAudioEffect.STEREO_BALANCE -> f.rms * if (f.stereoBalance < 0f) (1f - position) * -f.stereoBalance else position * f.stereoBalance
            VideoAudioEffect.FREQUENCY_GRADIENT -> band * (.35f + position * .65f)
            VideoAudioEffect.BEAT_STROBE -> if (beatPulse >= BEAT_STROBE_THRESHOLD) beatPulse else 0f
            VideoAudioEffect.COMET_TRAILS -> max(comet, f.rms * (.12f + .28f * (1f - abs(position - f.onset).coerceIn(0f, 1f))))
            VideoAudioEffect.BASS_WAVE -> f.bass * ((sin((position - f.bass) * BASS_WAVE_CYCLES * Math.PI) + 1.0) * .5).toFloat()
            VideoAudioEffect.VOCAL_FOCUS -> f.mid * (1f - centred * .7f)
            VideoAudioEffect.SILENCE_BREATHING -> f.rms
            VideoAudioEffect.ADAPTIVE_SHIMMER -> f.treble * (.35f + .65f * ((sin(position * SHIMMER_CYCLES * Math.PI + timestampNanos / 1_000_000_000.0 * SHIMMER_RADIANS_PER_SECOND) + 1.0) * .5f).toFloat())
            // Source RGB is deliberately preserved: this is a warm-to-cool spatial brightness emphasis, not a colour rewrite.
            VideoAudioEffect.BEAT_COLOUR_TEMPERATURE -> beatPulse * (.4f + position * .6f)
        }.coerceIn(0f, 1f)
    }

    private fun VideoAudioEffect.usesBeatEvents() = when (this) {
        VideoAudioEffect.BEAT_PULSE,
        VideoAudioEffect.CENTER_BEAT_BURST,
        VideoAudioEffect.BEAT_STROBE,
        VideoAudioEffect.BEAT_COLOUR_TEMPERATURE -> true
        else -> false
    }

    companion object {
        const val BLACK_FRAME_HOLD = 30
        const val MIN_PULSE_ATTACK = .22f
        const val PULSE_STRENGTH_RANGE = .78f
        const val DEFAULT_PULSE_DECAY_MILLIS = 420f
        const val MAX_BEAT_EVENT_AGE_NANOS = 500_000_000L
        const val PERIMETER_ZONES = 16
        const val SILENCE_BREATH_MIN_BRIGHTNESS = .08f
        const val SILENCE_BREATH_RADIANS_PER_SECOND = .8
        const val BEAT_STROBE_THRESHOLD = .35f
        const val BASS_WAVE_CYCLES = 2f
        const val SHIMMER_CYCLES = 8f
        const val SHIMMER_RADIANS_PER_SECOND = 9.0
    }
}


object VideoSaturationPolicy {
    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 200
    const val DEFAULT_PERCENT = 125
    fun valid(value: Int) = value in MIN_PERCENT..MAX_PERCENT
    fun mutable(captureActive: Boolean) = true
}

/** A protected or sustained-black image must clear the last displayed output before capture ends. */
internal object VideoCaptureFailurePolicy { fun blackoutAndTerminate(stopRoute: () -> Unit) = stopRoute() }
object CaptureModeCheckboxPolicy {
    data class Result(val mode: RenderMode, val audioChecked: Boolean, val videoChecked: Boolean, val animationChecked: Boolean = false, val rejected: Boolean)
    fun resolve(audio: Boolean, video: Boolean, previous: RenderMode): Result = resolve(audio, video, false, previous)
    /** Capture inputs win if an accessibility/service callback briefly reports both selectors checked. */
    fun resolve(audio: Boolean, video: Boolean, animation: Boolean, previous: RenderMode): Result = when {
        audio && video -> Result(RenderMode.VIDEO_AUDIO, true, true, false, false)
        audio -> Result(RenderMode.AUDIO, true, false, false, false)
        video -> Result(RenderMode.VIDEO, false, true, false, false)
        animation -> Result(RenderMode.ANIMATION, false, false, true, false)
        else -> when (previous) {
            RenderMode.AUDIO -> Result(previous, true, false, false, true)
            RenderMode.VIDEO -> Result(previous, false, true, false, true)
            RenderMode.VIDEO_AUDIO -> Result(previous, true, true, false, true)
            RenderMode.ANIMATION -> Result(RenderMode.ANIMATION, false, false, true, true)
        }
    }
}
object EffectSelectionPolicy { fun enabledWhileCaptureActive() = true }
/** Source ownership is explicit; a mode cannot be committed until its complete resource set exists. */
data class RenderRequirements(val audio: Boolean, val video: Boolean) {
    companion object {
        fun forMode(mode: RenderMode) = when (mode) {
            RenderMode.ANIMATION -> RenderRequirements(false, false)
            RenderMode.AUDIO -> RenderRequirements(true, false)
            RenderMode.VIDEO -> RenderRequirements(false, true)
            RenderMode.VIDEO_AUDIO -> RenderRequirements(true, true)
        }
    }
}

/** The no-input renderer owns no MediaProjection; capture modes do. */
object LiveRenderLoopPolicy {
    fun requiresProjection(mode: RenderMode): Boolean = mode != RenderMode.ANIMATION
}

data class LocalTransitionRequest(val epoch: Long, val nonce: String, val target: RenderMode, val hasProjectionResult: Boolean)
sealed interface TransitionDecision {
    data class Accept(val requirements: RenderRequirements) : TransitionDecision
    data object Reject : TransitionDecision
}

/** Pure exactly-once local capability gate; no remote path can mint a capability. */
class LocalTransitionPolicy(private var active: RenderMode) {
    private var epoch = 0L
    private var nonce: String? = null
    fun mint(nextEpoch: Long, capability: String) { epoch = nextEpoch - 1; nonce = capability }
    fun decide(request: LocalTransitionRequest): TransitionDecision {
        val crossingIntoInput = active == RenderMode.ANIMATION && request.target != RenderMode.ANIMATION
        if (request.epoch != epoch + 1 || request.nonce != nonce || (crossingIntoInput != request.hasProjectionResult)) return TransitionDecision.Reject
        nonce = null
        epoch = request.epoch
        return TransitionDecision.Accept(RenderRequirements.forMode(request.target))
    }
    fun commit(target: RenderMode) { active = target }
    fun current() = active
    fun epoch() = epoch
}

/** Process-local capabilities are minted by visible MainActivity actions and consumed once by the service. */
object LocalTransitionCapabilities {
    private val pending = mutableMapOf<String, Long>()
    @Synchronized fun mint(epoch: Long): String = java.util.UUID.randomUUID().toString().also { pending[it] = epoch }
    @Synchronized fun consume(epoch: Long, nonce: String): Boolean = pending.remove(nonce) == epoch
}

object AnimationModeTransitionPolicy {
    fun requiresRestart(serviceActive: Boolean, current: RenderMode, requested: RenderMode): Boolean = false
}
/** Local UI-only diagnostic state. It deliberately has no capture, route, discovery, or socket API. */
object RainbowVisualSourcePolicy {
    @Volatile var running = false
        private set
    fun start() { running = true }
    fun stop() { running = false }
    fun mayStartCapture() = false
    fun mayUseOutputRoute() = false
    fun mayUseNetwork() = false
}
object TvUiStatePolicy {
    fun showVideoControls(mode: RenderMode): Boolean = mode == RenderMode.VIDEO || mode == RenderMode.VIDEO_AUDIO
    fun showAudioControls(mode: RenderMode): Boolean = mode == RenderMode.AUDIO || mode == RenderMode.VIDEO_AUDIO
    fun showVideoAudioControls(mode: RenderMode): Boolean = mode == RenderMode.VIDEO_AUDIO
    fun showAnimationControls(mode: RenderMode): Boolean = mode == RenderMode.ANIMATION
    fun showVideoColourTreatment(mode: RenderMode): Boolean = mode == RenderMode.VIDEO || mode == RenderMode.VIDEO_AUDIO
    fun showVideoSaturation(mode: RenderMode): Boolean = mode == RenderMode.VIDEO || mode == RenderMode.VIDEO_AUDIO
    fun showWledZones(output: OutputMode): Boolean = output == OutputMode.WLED
}
