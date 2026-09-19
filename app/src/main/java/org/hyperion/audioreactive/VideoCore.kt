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
    private var cometHead = 0f
    private var rippleRadius = 0f
    private var bassSweepHead = 0f
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
        val hasAudioAccent = settings.renderMode == RenderMode.VIDEO_AUDIO && features?.signalPresent == true
        updateBeatPulse(features, settings, timestampNanos, hasAudioAccent)
        if (hasAudioAccent) updateSpatialAccents(features!!, settings.videoAudioEffect)
        var p = 0; var zone = 0
        while (p < video.size) {
            var r = video[p].toInt() and 255; var g = video[p + 1].toInt() and 255; var b = video[p + 2].toInt() and 255
            val gain = if (hasAudioAccent) videoAudioGain(features, settings.videoAudioEffect, zone, timestampNanos) else 0f
            // A small chromatic layer remains visible even when a bright source cannot accept gain.
            // It is blended before brightness, so zero/silent input remains byte-for-byte video.
            if (hasAudioAccent && gain > 0f) {
                val accent = videoAudioAccent(features!!, settings.videoAudioEffect, zone)
                val amount = (gain * settings.audioBoost.coerceIn(0f, 1f) * MAX_CHROMA_ACCENT).coerceIn(0f, MAX_CHROMA_ACCENT)
                r = (r * (1f - amount) + accent[0] * amount).toInt().coerceIn(0, 255)
                g = (g * (1f - amount) + accent[1] * amount).toInt().coerceIn(0, 255)
                b = (b * (1f - amount) + accent[2] * amount).toInt().coerceIn(0, 255)
            }
            val silenceBase = silenceBrightness.compose(settings, features?.signalPresent, timestampNanos)
            val brightness = silenceBase * (1f + settings.audioBoost * gain * MAX_BRIGHTNESS_ACCENT)
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

    /** Source hue/chroma is retained; effects differ by spatial placement and temporal state. */
    private fun updateSpatialAccents(f: AudioFeatures, effect: VideoAudioEffect) {
        when (VideoAudioEffectCatalogue.pickerEffect(effect)) {
            VideoAudioEffect.COMET -> cometHead = (cometHead + .018f + f.onset * .22f + f.spectralCentroid * .035f) % 1f
            VideoAudioEffect.RIPPLE -> rippleRadius = (rippleRadius + .025f + f.spectralFlux * .16f) % 1f
            VideoAudioEffect.BASS_SWEEP -> bassSweepHead = (bassSweepHead + .012f + f.bass * .13f) % 1f
            else -> Unit
        }
    }

    private fun videoAudioGain(f: AudioFeatures?, effect: VideoAudioEffect, zone: Int, timestampNanos: Long): Float {
        if (f?.signalPresent != true) return 0f
        val x = (zone % width) / (width - 1f).coerceAtLeast(1f)
        val y = (zone / width) / (height - 1f).coerceAtLeast(1f)
        val perimeter = when { y < .18f -> x; x > .82f -> 1f + y; y > .82f -> 3f - x; else -> 4f - y } / 4f
        val band = f.bands[(perimeter * (AudioFeatures.BAND_COUNT - 1)).toInt().coerceIn(0, AudioFeatures.BAND_COUNT - 1)]
        fun wrappedDistance(a: Float, b: Float): Float { val d = abs(a - b); return minOf(d, 1f - d) }
        return when (VideoAudioEffectCatalogue.pickerEffect(effect)) {
            VideoAudioEffect.BRIGHTNESS_PULSE -> f.rms * (.55f + f.spectralCentroid * .25f + f.spectralFlux * .20f)
            VideoAudioEffect.BEAT_PULSE -> beatPulse * (if (abs(x - .5f) + abs(y - .5f) < .62f) 1f else .38f)
            VideoAudioEffect.EQ -> band * (.35f + if (y < .2f || y > .8f || x < .2f || x > .8f) .65f else .18f)
            VideoAudioEffect.COMET -> f.onset.coerceAtLeast(f.spectralFlux * .45f) * (1f - wrappedDistance(perimeter, cometHead) / .13f).coerceIn(0f, 1f)
            VideoAudioEffect.RIPPLE -> f.spectralFlux.coerceAtLeast(f.onset * .55f) * (1f - abs(wrappedDistance(perimeter, .5f) - rippleRadius) / .10f).coerceIn(0f, 1f)
            VideoAudioEffect.BASS_SWEEP -> f.bass * (1f - wrappedDistance(perimeter, bassSweepHead) / .20f).coerceIn(0f, 1f)
            else -> 0f
        }.coerceIn(0f, 1f)
    }

    /** Deliberately saturated, non-white accents; family masks are supplied by videoAudioGain(). */
    private fun videoAudioAccent(f: AudioFeatures, effect: VideoAudioEffect, zone: Int): IntArray {
        val phase = (zone * 37 + (f.spectralCentroid * 127f).toInt() + (f.spectralFlux * 71f).toInt()) and 255
        return when (VideoAudioEffectCatalogue.pickerEffect(effect)) {
            VideoAudioEffect.BRIGHTNESS_PULSE -> intArrayOf(30, 120 + phase / 5, 255)
            VideoAudioEffect.BEAT_PULSE -> intArrayOf(255, 28, 152)
            VideoAudioEffect.EQ -> intArrayOf(20 + phase / 3, 255, 72)
            VideoAudioEffect.COMET -> intArrayOf(255, 68, 20 + phase / 4)
            VideoAudioEffect.RIPPLE -> intArrayOf(42, 104 + phase / 3, 255)
            VideoAudioEffect.BASS_SWEEP -> intArrayOf(210, 20 + phase / 5, 255)
            else -> intArrayOf(0, 0, 0)
        }
    }

    private fun VideoAudioEffect.usesBeatEvents() = when (VideoAudioEffectCatalogue.pickerEffect(this)) {
        VideoAudioEffect.BEAT_PULSE -> true
        else -> false
    }

    companion object {
        const val BLACK_FRAME_HOLD = 30
        const val MIN_PULSE_ATTACK = .22f
        const val PULSE_STRENGTH_RANGE = .78f
        const val DEFAULT_PULSE_DECAY_MILLIS = 420f
        const val MAX_BEAT_EVENT_AGE_NANOS = 500_000_000L
        const val MAX_CHROMA_ACCENT = .28f
        const val MAX_BRIGHTNESS_ACCENT = .22f
        const val PERIMETER_ZONES = 16
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

/** RECORD_AUDIO is required before any transition allocates a new playback or microphone recorder. */
object AudioSourceAdmissionPolicy {
    fun requiresNewAudioSource(current: RenderMode, target: RenderMode): Boolean =
        !RenderRequirements.forMode(current).audio && RenderRequirements.forMode(target).audio
    fun permits(audioInput: AudioInput, current: RenderMode, target: RenderMode, recordAudioGranted: Boolean): Boolean =
        // Both AudioPlaybackCapture and VOICE_RECOGNITION allocate AudioRecord and require RECORD_AUDIO.
        !requiresNewAudioSource(current, target) || recordAudioGranted
}

/** Playback capture is projection-backed even for audio-only rendering. */
object ProjectionOwnershipPolicy {
    fun retainsProjection(requirements: RenderRequirements): Boolean = requirements.audio || requirements.video
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
