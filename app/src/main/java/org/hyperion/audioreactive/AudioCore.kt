package org.hyperion.audioreactive

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Reusable normalized measurements; consumers finish before requesting the next result. */
class AudioFeatures(
    var rms: Float,
    var peak: Float,
    var onset: Float,
    var bass: Float,
    var mid: Float,
    var treble: Float,
    val bands: FloatArray = FloatArray(BAND_COUNT),
    /** True only after the analyzer's gate accepts actual PCM energy. */
    var signalPresent: Boolean = rms > 0f || peak > 0f,
    /** Monotonic accepted acoustic beat event identifier; zero means no event has been accepted. */
    var beatSequence: Long = 0L,
    /** Strength and timestamp describe the latest accepted event, never a synthesized cadence. */
    var beatStrength: Float = 0f,
    var beatTimestampNanos: Long = 0L,
    var tempoBpm: Float = 0f,
    var tempoConfidence: Float = 0f,
    /** -1 is fully left, +1 is fully right; mono analysis is centered at zero. */
    var stereoBalance: Float = 0f,
    /** 0=low-frequency weighted, 1=high-frequency weighted spectrum centroid. */
    var spectralCentroid: Float = 0f,
    /** Positive frame-to-frame normalized spectral novelty, independent of loudness. */
    var spectralFlux: Float = 0f,
) {
    companion object { const val BAND_COUNT = 16 }
}
data class Rgb(val r: Int, val g: Int, val b: Int)
/** Bounded persisted controls used directly by the audio renderer. */
data class EffectParameters(val speed: Float = 1f, val trail: Float = .5f, val beatThreshold: Float = .2f, val hueShift: Float = 0f) {
    fun valid() = speed in .25f..3f && trail in 0f..1f && beatThreshold in .05f.. .95f && hueShift in -180f..180f
}

enum class Effect(val wled1dReferenceStyle: Boolean = false) {
    // Existing selections remain stable for persisted settings.
    SPECTRUM, PULSE, FIRE, OCEAN, AURORA,
    NEON, SUNSET, FOREST, MONOCHROME, RAINBOW,
    MEL_SPECTRUM, BASS_PULSE, BASS_CHASE,
    RUNNING_SPARKS, METEOR_TRAILS,
    BEAT_EXPLOSION, EMBERS, FIREFLIES,
    COLOR_WAVES, THREE_BAND_RATIO, DYNAMIC_HUE,
    COLOR_ORGAN, VU_PEAK_HOLD, BLURZ_TRAILS,
    WATERFALL(true), BEAT_RIPPLE,
    // Original app-side interpretations of familiar audio-reactive styles; no WLED code is used.
    JUGGLE, PRISM, BASS_GRADIENT,
    WAVE_BANDS, SCANNER, PENDULUM,
    STARFIELD, PLASMA, MUSIC_BOX,
    EQUALIZER_SWEEP,
    // Every 1D Audio Reactive style in the WLED reference catalogue, independently implemented here.
    RIPPLE_PEAK(true), GRAVCENTER(true), GRAVCENTRIC(true),
    GRAVIMETER(true), GRAVFREQ(true), JUGGLES(true),
    MATRIPIX(true), MIDNOISE(true), NOISEFIRE(true),
    NOISEMETER(true), PIXELWAVE(true), PLASMOID(true),
    PUDDLEPEAK(true), PUDDLES(true), PIXELS(true),
    BLURZ(true), DJ_LIGHT(true), FREQMAP(true),
    FREQMATRIX(true), FREQPIXELS(true), FREQWAVE(true),
    NOISEMOVE(true), ROCKTAVES(true)
}

/** Android presents one compact representative for each legacy family; wire tokens remain stable. */
object EffectCatalogue {
    val visible: List<Effect> = Effect.entries.takeWhile { it != Effect.RIPPLE_PEAK }

    /** Retained legacy selections resolve to a visible representative without rewriting persistence. */
    fun pickerEffect(effect: Effect): Effect = when (effect) {
        Effect.RIPPLE_PEAK, Effect.PUDDLEPEAK -> Effect.BEAT_RIPPLE
        Effect.GRAVCENTER -> Effect.BASS_PULSE
        Effect.GRAVCENTRIC, Effect.GRAVFREQ -> Effect.BASS_GRADIENT
        Effect.GRAVIMETER, Effect.NOISEMETER -> Effect.VU_PEAK_HOLD
        Effect.JUGGLES -> Effect.JUGGLE
        Effect.MATRIPIX, Effect.PIXELS, Effect.FREQPIXELS -> Effect.FIREFLIES
        Effect.MIDNOISE, Effect.NOISEMOVE -> Effect.DYNAMIC_HUE
        Effect.NOISEFIRE -> Effect.FIRE
        Effect.PIXELWAVE, Effect.FREQWAVE -> Effect.WAVE_BANDS
        Effect.PLASMOID -> Effect.PLASMA
        Effect.PUDDLES -> Effect.OCEAN
        Effect.BLURZ -> Effect.BLURZ_TRAILS
        Effect.DJ_LIGHT -> Effect.PULSE
        Effect.FREQMAP -> Effect.SPECTRUM
        Effect.FREQMATRIX, Effect.ROCKTAVES -> Effect.EQUALIZER_SWEEP
        else -> effect
    }
}

/**
 * Fixed-size PCM analyzer. It uses a Hann window and 16 logarithmically spaced probes rather
 * than a dependency-heavy FFT. All adaptive state is clamped, and the only working band array
 * is retained for the lifetime of the analyzer.
 */
class PcmAnalyzer {
    private val bands = FloatArray(AudioFeatures.BAND_COUNT)
    private val bandEnvelope = FloatArray(AudioFeatures.BAND_COUNT)
    private val bandPeak = FloatArray(AudioFeatures.BAND_COUNT) { MIN_BAND_PEAK }
    private val bandNoiseFloor = FloatArray(AudioFeatures.BAND_COUNT)
    private val previousSpectrum = FloatArray(AudioFeatures.BAND_COUNT)
    private var hannWindow = FloatArray(CaptureCadence.ANALYSIS_SAMPLES)
    private var windowCount = 0
    private val reusableFeatures = AudioFeatures(0f, 0f, 0f, 0f, 0f, 0f, bands)
    private var smoothedLevel = 0f
    private var adaptivePeak = MIN_ADAPTIVE_PEAK
    private var noiseFloor = 0f
    private var previousLevel = 0f
    private var onsetBaseline = 0f
    private var onsetRefractoryFrames = 0
    // Beat state is fixed-size: no per-block collections, and timestamps—not render frames—drive it.
    private val beatIntervalsNanos = LongArray(BEAT_HISTORY_SIZE)
    private var beatIntervalCount = 0
    private var beatIntervalCursor = 0
    private var beatNoveltyBaseline = 0f
    private var previousBeatEnergy = 0f
    private var lastBeatTimestampNanos = Long.MIN_VALUE
    private var analysisTimestampNanos = 0L
    private var beatSequence = 0L
    private var beatStrength = 0f
    private var tempoBpm = 0f
    private var tempoConfidence = 0f
    private var configuredBeatThreshold = .2f
    private var suppliedTimestampNanos = -1L
    private var stereoMono = ShortArray(CaptureCadence.ANALYSIS_SAMPLES)

    fun reset() {
        bands.fill(0f); bandEnvelope.fill(0f); bandPeak.fill(MIN_BAND_PEAK); bandNoiseFloor.fill(0f); previousSpectrum.fill(0f)
        smoothedLevel = 0f; adaptivePeak = MIN_ADAPTIVE_PEAK; noiseFloor = 0f; previousLevel = 0f
        onsetBaseline = 0f; onsetRefractoryFrames = 0
        resetBeatState(clearSequence = true)
        analysisTimestampNanos = 0L
        configuredBeatThreshold = .2f; suppliedTimestampNanos = -1L
    }

    /** Supplies live persisted threshold and capture-time monotonic timestamp without allocating. */
    fun configureBeatTracking(beatThreshold: Float, timestampNanos: Long) {
        configuredBeatThreshold = beatThreshold.coerceIn(.05f, .95f)
        suppliedTimestampNanos = timestampNanos
    }

    fun analyze(pcm: ShortArray, sensitivity: Float): AudioFeatures = analyze(pcm, pcm.size, sensitivity)
    fun analyze(pcm: ShortArray, sampleCount: Int, sensitivity: Float): AudioFeatures = analyze(pcm, sampleCount, sensitivity, configuredBeatThreshold, takeTimestamp(sampleCount))

    /** Downmixes interleaved stereo for shared analysis and retains only its bounded L/R energy balance. */
    fun analyzeStereo(interleaved: ShortArray, sampleCount: Int, sensitivity: Float, beatThreshold: Float, timestampNanos: Long): AudioFeatures {
        val values = sampleCount.coerceIn(0, interleaved.size)
        val frames = values / 2
        if (frames > stereoMono.size) stereoMono = ShortArray(frames)
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        for (frame in 0 until frames) {
            val left = interleaved[frame * 2].toInt()
            val right = interleaved[frame * 2 + 1].toInt()
            stereoMono[frame] = ((left + right) / 2).toShort()
            leftEnergy += abs(left).toDouble()
            rightEnergy += abs(right).toDouble()
        }
        val result = analyze(stereoMono, frames, sensitivity, beatThreshold, timestampNanos)
        result.stereoBalance = if (leftEnergy + rightEnergy > 0.0) ((rightEnergy - leftEnergy) / (rightEnergy + leftEnergy)).toFloat().coerceIn(-1f, 1f) else 0f
        return result
    }

    /** The capture path supplies System.nanoTime(); tests supply deterministic monotonic timestamps. */
    fun analyze(pcm: ShortArray, sampleCount: Int, sensitivity: Float, beatThreshold: Float, timestampNanos: Long): AudioFeatures {
        reusableFeatures.stereoBalance = 0f
        val count = sampleCount.coerceIn(0, pcm.size)
        val timestamp = timestampNanos.coerceAtLeast(analysisTimestampNanos)
        analysisTimestampNanos = timestamp
        if (count == 0) {
            decayToSilence()
            previousLevel = smoothedLevel
            smoothedLevel *= (1f - LEVEL_RELEASE)
            return features(0f, 0f, 0f)
        }
        prepareWindow(count)
        var mean = 0.0
        for (i in 0 until count) mean += pcm[i] / PCM_SCALE
        mean /= count
        var energy = 0.0
        var peak = 0f
        for (i in 0 until count) {
            val x = (pcm[i] / PCM_SCALE - mean) * hannWindow[i]
            energy += x * x
            peak = max(peak, abs(x).toFloat())
        }
        val rawRms = sqrt(energy / count).toFloat().coerceIn(0f, 1f)
        // Noise updates slowly upward and quickly downward, preventing breathing in quiet audio.
        noiseFloor += (rawRms - noiseFloor) * if (rawRms < noiseFloor) FLOOR_RELEASE else FLOOR_ATTACK
        noiseFloor = noiseFloor.coerceIn(0f, MAX_NOISE_FLOOR)
        adaptivePeak += (rawRms - adaptivePeak) * if (rawRms > adaptivePeak) PEAK_ATTACK else PEAK_RELEASE
        adaptivePeak = adaptivePeak.coerceIn(MIN_ADAPTIVE_PEAK, 1f)
        val gain = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        val gate = max(ABSOLUTE_GATE, noiseFloor * NOISE_GATE_MULTIPLIER)
        val gated = if (rawRms <= gate) 0f else ((rawRms - gate) / (adaptivePeak - gate).coerceAtLeast(MIN_RANGE) * gain).coerceIn(0f, 1f)
        smoothedLevel += (gated - smoothedLevel) * if (gated > smoothedLevel) LEVEL_ATTACK else LEVEL_RELEASE
        smoothedLevel = smoothedLevel.coerceIn(0f, 1f)
        val rawOnset = ((smoothedLevel - previousLevel) * ONSET_GAIN).coerceIn(0f, 1f)
        previousLevel = smoothedLevel
        val onset = boundedOnset(rawOnset, gated > 0f)
        for (band in bands.indices) bands[band] = probeBand(pcm, count, mean, band, gain)
        val centroid = spectralCentroid()
        val flux = spectralFlux()
        val bass = averageBands(0, 4)
        val mid = averageBands(5, 10)
        val treble = averageBands(11, AudioFeatures.BAND_COUNT)
        updateBeat(bass, mid, smoothedLevel, gated > 0f, beatThreshold, timestamp)
        return setFeatures(smoothedLevel, (peak * gain).coerceIn(0f, 1f), onset, bass, mid, treble, gated > 0f).also {
            it.spectralCentroid = centroid
            it.spectralFlux = flux
        }
    }

    private fun features(rms: Float, peak: Float, onset: Float): AudioFeatures {
        resetBeatState(clearSequence = false)
        return setFeatures(rms, peak, onset, 0f, 0f, 0f, false)
    }
    private fun setFeatures(rms: Float, peak: Float, onset: Float, bass: Float, mid: Float, treble: Float, signalPresent: Boolean): AudioFeatures {
        reusableFeatures.rms = rms; reusableFeatures.peak = peak; reusableFeatures.onset = onset
        reusableFeatures.bass = bass; reusableFeatures.mid = mid; reusableFeatures.treble = treble
        reusableFeatures.signalPresent = signalPresent
        if (!signalPresent) { reusableFeatures.spectralCentroid = 0f; reusableFeatures.spectralFlux = 0f }
        reusableFeatures.beatSequence = beatSequence
        reusableFeatures.beatStrength = beatStrength
        reusableFeatures.beatTimestampNanos = if (signalPresent) lastBeatTimestampNanos.coerceAtLeast(0L) else 0L
        reusableFeatures.tempoBpm = if (signalPresent) tempoBpm else 0f
        reusableFeatures.tempoConfidence = if (signalPresent) tempoConfidence else 0f
        return reusableFeatures
    }
    private fun averageBands(start: Int, end: Int): Float { var sum = 0f; for (i in start until end) sum += bands[i]; return sum / (end - start) }
    private fun spectralCentroid(): Float {
        var energy = 0f; var weighted = 0f
        for (index in bands.indices) { val value = bands[index]; energy += value; weighted += value * index }
        return if (energy > 0f) (weighted / energy / bands.lastIndex).coerceIn(0f, 1f) else 0f
    }
    private fun spectralFlux(): Float {
        var rising = 0f; var total = 0f
        for (index in bands.indices) { val value = bands[index]; rising += (value - previousSpectrum[index]).coerceAtLeast(0f); total += value; previousSpectrum[index] = value }
        return if (total > 0f) (rising / total).coerceIn(0f, 1f) else 0f
    }
    private fun takeTimestamp(sampleCount: Int): Long {
        if (suppliedTimestampNanos >= 0L) {
            val timestamp = suppliedTimestampNanos
            suppliedTimestampNanos = -1L
            return timestamp
        }
        return nextTimestamp(sampleCount)
    }
    private fun nextTimestamp(sampleCount: Int): Long {
        analysisTimestampNanos += sampleCount.coerceAtLeast(0) * NANOS_PER_SECOND / SAMPLE_RATE.toLong()
        return analysisTimestampNanos
    }
    /** Accepts only positive bass/percussive energy novelty; tempo only validates accepted PCM events. */
    private fun updateBeat(bass: Float, mid: Float, level: Float, signalPresent: Boolean, requestedThreshold: Float, timestamp: Long) {
        if (!signalPresent) { resetBeatState(clearSequence = false); return }
        val energy = (bass * .72f + mid * .20f + level * .08f).coerceIn(0f, 1f)
        val novelty = (energy - previousBeatEnergy).coerceAtLeast(0f)
        previousBeatEnergy = energy
        val control = requestedThreshold.coerceIn(.05f, .95f)
        val threshold = (MIN_BEAT_NOVELTY + control * BEAT_CONTROL_RANGE + beatNoveltyBaseline * (BEAT_BASELINE_MULTIPLIER + control)).coerceIn(MIN_BEAT_NOVELTY, .95f)
        val interval = if (lastBeatTimestampNanos == Long.MIN_VALUE) Long.MAX_VALUE else timestamp - lastBeatTimestampNanos
        val outsideRefractory = interval >= BEAT_REFRACTORY_NANOS
        if (novelty >= threshold && outsideRefractory) {
            beatSequence++
            beatStrength = (novelty / threshold).coerceIn(0f, 1f)
            if (lastBeatTimestampNanos != Long.MIN_VALUE && interval in MIN_TEMPO_INTERVAL_NANOS..MAX_TEMPO_INTERVAL_NANOS) recordBeatInterval(interval)
            lastBeatTimestampNanos = timestamp
        }
        beatNoveltyBaseline += (novelty - beatNoveltyBaseline) * if (novelty > beatNoveltyBaseline) BEAT_BASELINE_ATTACK else BEAT_BASELINE_RELEASE
        beatNoveltyBaseline = beatNoveltyBaseline.coerceIn(0f, 1f)
    }
    private fun recordBeatInterval(interval: Long) {
        beatIntervalsNanos[beatIntervalCursor] = interval
        beatIntervalCursor = (beatIntervalCursor + 1) % BEAT_HISTORY_SIZE
        beatIntervalCount = (beatIntervalCount + 1).coerceAtMost(BEAT_HISTORY_SIZE)
        var sum = 0L
        for (index in 0 until beatIntervalCount) sum += beatIntervalsNanos[index]
        val mean = sum.toDouble() / beatIntervalCount
        var deviation = 0.0
        for (index in 0 until beatIntervalCount) deviation += kotlin.math.abs(beatIntervalsNanos[index] - mean)
        tempoBpm = (60_000_000_000.0 / mean).toFloat().coerceIn(0f, 300f)
        tempoConfidence = (beatIntervalCount.toFloat() / BEAT_HISTORY_SIZE * (1.0 - deviation / beatIntervalCount / mean).coerceIn(0.0, 1.0)).toFloat()
    }
    private fun resetBeatState(clearSequence: Boolean) {
        beatIntervalsNanos.fill(0L); beatIntervalCount = 0; beatIntervalCursor = 0
        beatNoveltyBaseline = 0f; previousBeatEnergy = 0f; lastBeatTimestampNanos = Long.MIN_VALUE
        beatStrength = 0f; tempoBpm = 0f; tempoConfidence = 0f
        if (clearSequence) beatSequence = 0L
    }
    /** The capture path is fixed at 1024 samples; alternate callers grow this only when needed. */
    private fun prepareWindow(count: Int) {
        if (count == windowCount) return
        if (count > hannWindow.size) hannWindow = FloatArray(count)
        val denominator = (count - 1).coerceAtLeast(1)
        for (i in 0 until count) hannWindow[i] = (.5 - .5 * cos(2.0 * PI * i / denominator)).toFloat()
        windowCount = count
    }
    private fun decayToSilence() {
        for (band in bands.indices) {
            bandEnvelope[band] *= (1f - BAND_RELEASE)
            previousSpectrum[band] *= (1f - BAND_RELEASE)
            if (bandEnvelope[band] < SILENCE_EPSILON) bandEnvelope[band] = 0f
            bands[band] = bandEnvelope[band]
        }
        onsetBaseline *= (1f - ONSET_BASELINE_RELEASE)
        onsetRefractoryFrames = 0
    }
    private fun boundedOnset(raw: Float, signalPresent: Boolean): Float {
        if (!signalPresent) {
            onsetBaseline *= (1f - ONSET_BASELINE_RELEASE)
            onsetRefractoryFrames = 0
            return 0f
        }
        val threshold = max(MIN_ONSET_THRESHOLD, onsetBaseline * ONSET_THRESHOLD_MULTIPLIER)
        val accepted = if (onsetRefractoryFrames == 0 && raw >= threshold) raw else 0f
        if (onsetRefractoryFrames > 0) onsetRefractoryFrames--
        if (accepted > 0f) onsetRefractoryFrames = ONSET_REFRACTORY_FRAMES
        onsetBaseline += (raw - onsetBaseline) * if (raw > onsetBaseline) ONSET_BASELINE_ATTACK else ONSET_BASELINE_RELEASE
        onsetBaseline = onsetBaseline.coerceIn(0f, 1f)
        return accepted.coerceIn(0f, 1f)
    }
    private fun probeBand(pcm: ShortArray, count: Int, mean: Double, band: Int, gain: Float): Float {
        val frequency = MEL_FREQUENCIES[band]
        var real = 0.0; var imag = 0.0
        for (i in 0 until count) {
            val phase = 2.0 * PI * frequency * i / SAMPLE_RATE
            val x = (pcm[i] / PCM_SCALE - mean) * hannWindow[i]
            real += x * cos(phase); imag -= x * sin(phase)
        }
        val magnitude = (sqrt(real * real + imag * imag) * 2.0 / count).toFloat()
        bandNoiseFloor[band] += (magnitude - bandNoiseFloor[band]) * if (magnitude < bandNoiseFloor[band]) BAND_FLOOR_RELEASE else BAND_FLOOR_ATTACK
        bandNoiseFloor[band] = bandNoiseFloor[band].coerceIn(0f, MAX_BAND_NOISE_FLOOR)
        bandPeak[band] += (magnitude - bandPeak[band]) * if (magnitude > bandPeak[band]) BAND_PEAK_ATTACK else BAND_PEAK_RELEASE
        bandPeak[band] = bandPeak[band].coerceIn(MIN_BAND_PEAK, 1f)
        val gate = max(BAND_ABSOLUTE_GATE, bandNoiseFloor[band] * BAND_NOISE_GATE_MULTIPLIER)
        val normalized = if (magnitude <= gate) 0f else ((magnitude - gate) / (bandPeak[band] - gate).coerceAtLeast(MIN_BAND_RANGE) * gain).coerceIn(0f, 1f)
        bandEnvelope[band] += (normalized - bandEnvelope[band]) * if (normalized > bandEnvelope[band]) BAND_ATTACK else BAND_RELEASE
        bandEnvelope[band] = bandEnvelope[band].coerceIn(0f, 1f)
        return bandEnvelope[band]
    }

    private companion object {
        const val SAMPLE_RATE = 48_000.0; const val PCM_SCALE = 32768.0
        const val MIN_SENSITIVITY = .1f; const val MAX_SENSITIVITY = 4f
        const val ABSOLUTE_GATE = .004f; const val NOISE_GATE_MULTIPLIER = 1.35f; const val MAX_NOISE_FLOOR = .20f
        const val FLOOR_ATTACK = .003f; const val FLOOR_RELEASE = .08f
        const val MIN_ADAPTIVE_PEAK = .035f; const val PEAK_ATTACK = .30f; const val PEAK_RELEASE = .012f
        const val MIN_RANGE = .015f; const val LEVEL_ATTACK = .42f; const val LEVEL_RELEASE = .09f; const val ONSET_GAIN = 3.5f
        const val MIN_BAND_PEAK = .006f; const val MAX_BAND_NOISE_FLOOR = .20f; const val BAND_ABSOLUTE_GATE = .0008f
        const val BAND_NOISE_GATE_MULTIPLIER = 1.35f; const val BAND_FLOOR_ATTACK = .003f; const val BAND_FLOOR_RELEASE = .08f
        const val BAND_PEAK_ATTACK = .30f; const val BAND_PEAK_RELEASE = .012f; const val MIN_BAND_RANGE = .003f
        const val BAND_ATTACK = .42f; const val BAND_RELEASE = .09f; const val SILENCE_EPSILON = .0001f
        const val MIN_ONSET_THRESHOLD = .08f; const val ONSET_THRESHOLD_MULTIPLIER = 1.7f
        const val ONSET_BASELINE_ATTACK = .15f; const val ONSET_BASELINE_RELEASE = .05f; const val ONSET_REFRACTORY_FRAMES = 3
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val BEAT_HISTORY_SIZE = 8
        const val BEAT_REFRACTORY_NANOS = 180_000_000L
        const val MIN_TEMPO_INTERVAL_NANOS = 250_000_000L; const val MAX_TEMPO_INTERVAL_NANOS = 1_500_000_000L
        const val MIN_BEAT_NOVELTY = .025f; const val BEAT_CONTROL_RANGE = .12f; const val BEAT_BASELINE_MULTIPLIER = 1.35f
        const val BEAT_BASELINE_ATTACK = .08f; const val BEAT_BASELINE_RELEASE = .025f
        val MEL_FREQUENCIES = doubleArrayOf(55.0, 80.0, 115.0, 165.0, 235.0, 335.0, 475.0, 675.0, 960.0, 1360.0, 1930.0, 2740.0, 3890.0, 5520.0, 7830.0, 11_100.0)
    }
}

/** Reusable bounded 1D RGB24 renderer; mutable trails are reset when an effect is selected anew. */
class EffectFrameRenderer(private val width: Int = HyperionFlatbuffer.AUDIO_WIDTH) {
    init { require(width in MIN_WIDTH..MAX_WIDTH && width % WIDTH_STEP == 0) { "1D effect width must be 16..512 in 16-pixel steps" } }
    private val pixels = ByteArray(width * 3)
    private val trail = FloatArray(width)
    private val peakHold = FloatArray(width)
    private val waterfall = FloatArray(width)
    private val fireHeat = FloatArray(width)
    private val bassPulse = FloatArray(width)
    private var activeEffect: Effect? = null
    private var phase = 0f
    private var cometPosition = 0f
    private var sparkPosition = 0f
    private var meteorPosition = 0f
    private var emberPosition = 0f
    private var beatCenterRadius = 0f
    private var beatEdgeRadius = 0f
    private var beatCenterEnergy = 0f
    private var beatEdgeEnergy = 0f
    private var deterministicSeed = 0x13579bdf

    fun reset() {
        trail.fill(0f); peakHold.fill(0f); waterfall.fill(0f); fireHeat.fill(0f); bassPulse.fill(0f)
        activeEffect = null; phase = 0f; cometPosition = 0f; sparkPosition = 0f; meteorPosition = 0f; emberPosition = 0f
        beatCenterRadius = 0f; beatEdgeRadius = 0f; beatCenterEnergy = 0f; beatEdgeEnergy = 0f
        deterministicSeed = 0x13579bdf
    }

    fun render(effect: Effect, features: AudioFeatures, brightness: Float, tick: Long, parameters: EffectParameters = EffectParameters()): ByteArray {
        require(parameters.valid())
        if (activeEffect != effect) resetEffectState(effect)
        // A gated analyzer result must never be turned into a palette, trail, or stale beat glow.
        if (!features.signalPresent) {
            resetEffectState(effect)
            pixels.fill(0)
            return pixels
        }
        val level = brightness.coerceIn(0f, 1f)
        phase += (.035f + features.rms * .045f) * parameters.speed
        if (phase >= TWO_PI) phase -= TWO_PI
        cometPosition = (cometPosition + (.12f + features.bass * .62f) * parameters.speed) % width
        sparkPosition = (sparkPosition + (.55f + features.treble * 1.8f) * parameters.speed) % width
        meteorPosition = (meteorPosition + (.28f + features.onset * 2.4f + features.bass * .35f) * parameters.speed) % width
        deterministicSeed = deterministicSeed * 1664525 + 1013904223
        updateEffectState(effect, features, parameters)
        for (x in 0 until width) renderPixel(effect, features, level, tick, x, parameters)
        return pixels
    }

    private fun resetEffectState(effect: Effect) {
        trail.fill(0f); peakHold.fill(0f); waterfall.fill(0f); fireHeat.fill(0f); bassPulse.fill(0f)
        sparkPosition = 0f; meteorPosition = 0f; emberPosition = 0f; beatCenterRadius = 0f; beatEdgeRadius = 0f; beatCenterEnergy = 0f; beatEdgeEnergy = 0f
        activeEffect = effect
    }

    private fun updateEffectState(effect: Effect, f: AudioFeatures, p: EffectParameters) {
        when (effect) {
            Effect.FIRE -> for (x in 0 until width) {
                val neighbor = (if (x == 0) fireHeat[x] else fireHeat[x - 1]) * .16f
                val ignition = f.bass * (.24f + pseudo(x, 0L) * .30f) + f.onset * pseudo(x, 1L) * .36f
                fireHeat[x] = (fireHeat[x] * .79f + neighbor + ignition).coerceIn(0f, 1f)
            }
            Effect.EMBERS -> {
                for (x in 0 until width) trail[x] *= .45f + p.trail * .5f
                emberPosition = (emberPosition + (.75f + f.bass * .80f) * p.speed) % width
                if (f.onset > p.beatThreshold || f.bass > .38f) trail[emberPosition.toInt()] = max(trail[emberPosition.toInt()], (f.onset + f.bass * .55f).coerceIn(.18f, 1f))
            }
            Effect.BASS_PULSE -> {
                val middle = width / 2
                for (x in 0 until middle) bassPulse[x] = max(bassPulse[x] * .76f, bassPulse[x + 1] * .88f)
                for (x in width - 1 downTo middle + 1) bassPulse[x] = max(bassPulse[x] * .76f, bassPulse[x - 1] * .88f)
                bassPulse[middle] = max(bassPulse[middle] * .72f, (f.bass * .70f + f.onset).coerceIn(0f, 1f))
            }
            Effect.BEAT_RIPPLE -> {
                val trigger = (f.onset + f.bass * .55f).coerceIn(0f, 1f)
                beatCenterEnergy *= .82f; beatEdgeEnergy *= .80f
                beatCenterRadius += .42f + f.bass * .80f; beatEdgeRadius += .36f + f.onset * .72f
                if (trigger > p.beatThreshold) { beatCenterEnergy = max(beatCenterEnergy, trigger); beatEdgeEnergy = max(beatEdgeEnergy, trigger * .82f); beatCenterRadius = 0f; beatEdgeRadius = 0f }
            }
            else -> Unit
        }
    }

    private fun renderPixel(effect: Effect, f: AudioFeatures, level: Float, tick: Long, x: Int, parameters: EffectParameters) {
        val p = x.toFloat() / (width - 1).coerceAtLeast(1)
        val centered = abs(p * 2f - 1f)
        val wave = ((sin(p * TWO_PI + phase) + 1f) * .5f)
        val bandIndex = (x * AudioFeatures.BAND_COUNT / width).coerceIn(0, f.bands.lastIndex)
        val band = f.bands[bandIndex].coerceIn(0f, 1f)
        when (effect) {
            Effect.MONOCHROME -> setGray(x, f.rms * level)
            // Spectrum is smoothed for a continuous spectral gradient; MEL is direct 16-band GEQ.
            Effect.SPECTRUM -> setHsv(x, 15f + p * 300f + parameters.hueShift + f.spectralCentroid * 42f, .94f, smoothBand(f, x) * (1f + f.spectralFlux * .22f).coerceAtMost(1f) * level)
            Effect.MEL_SPECTRUM -> setHsv(x, 15f + p * 300f, .94f, band * level)
            // Pulse is global RMS/onset; Bass Pulse propagates outward from the centre.
            Effect.PULSE -> setHsv(x, 335f + p * 48f, .88f, (f.rms + f.onset * .68f).coerceIn(0f, 1f) * level)
            Effect.BASS_PULSE -> setHsv(x, 335f + p * 48f, .88f, (bassPulse[x] * (1f - centered * .20f)).coerceIn(0f, 1f) * level)
            Effect.BASS_CHASE -> { val d = (x - cometPosition + width) % width; val head = (1f - d / (2.5f + f.bass * 4f)).coerceIn(0f, 1f); setHsv(x, 4f + f.bass * 38f, .98f, head * f.bass * level) }
            Effect.RUNNING_SPARKS -> { val d = circularDistance(x.toFloat(), sparkPosition); val spark = pseudo(x, tick / 2) > (.90f - f.treble * .28f); trail[x] = max(trail[x] * (.35f + parameters.trail * .42f), if (spark && d < width *.34f) f.peak else 0f); setHsv(x, 42f + pseudo(x, tick) * 34f + parameters.hueShift, .55f, trail[x] * level) }
            Effect.METEOR_TRAILS -> { val d = (meteorPosition - x + width) % width; val tailLength = 5f + parameters.trail * 18f; val meteor = (1f - d / tailLength).coerceIn(0f, 1f); trail[x] = max(trail[x] * .72f, meteor * (f.onset + f.bass * .65f).coerceAtLeast(.18f)); setHsv(x, 195f + p * 42f, .95f, trail[x] * level) }
            Effect.BEAT_EXPLOSION -> setHsv(x, 5f + p * 280f, .92f, (f.onset * (1f - centered) + f.rms * wave * .45f).coerceIn(0f, 1f) * level)
            // Fire retains a cooling heat field; Embers only ignites sparse drifting sparks.
            Effect.FIRE -> setMix(x, 36, 0, 0, 255, 198, 18, fireHeat[x], level)
            Effect.EMBERS -> setMix(x, 0, 0, 0, 255, 92, 8, trail[x], level)
            Effect.FIREFLIES -> { val sparkle = pseudo(x, tick / 3) > .84f; setHsv(x, 52f + p * 38f, .72f, ((if (sparkle) .25f + f.onset else .025f) + f.rms * wave * .34f) * level) }
            Effect.COLOR_WAVES -> { val crest = ((sin(p * TWO_PI * 2.2f - phase * 2.4f) + 1f) * .5f); setHsv(x, 185f + crest * 115f + f.treble * 45f, .88f, (f.rms * .18f + crest * f.mid * .82f) * level) }
            Effect.THREE_BAND_RATIO -> { val v = when { x < width / 3 -> f.bass; x < width * 2 / 3 -> f.mid; else -> f.treble }; setHsv(x, if (x < width / 3) 0f else if (x < width * 2 / 3) 125f else 220f, .9f, (v * (.65f + wave * .35f)) * level) }
            Effect.DYNAMIC_HUE -> setHsv(x, f.bass * 20f + f.mid * 130f + f.treble * 240f + p * 90f + tick % 360, .95f, (f.rms * (.55f + wave * .45f)) * level)
            Effect.COLOR_ORGAN -> setHsv(x, if (band > .55f) 320f else 100f + x * 12f, .90f, (band * .8f + f.onset * .2f) * level)
            Effect.VU_PEAK_HOLD -> { peakHold[x] = max(peakHold[x] * .94f, band); setHsv(x, 120f - peakHold[x] * 115f, .95f, peakHold[x] * level) }
            Effect.BLURZ_TRAILS -> { trail[x] = (trail[x] * .82f + band * .18f + f.onset * (1f - centered) * .22f).coerceIn(0f, 1f); setHsv(x, 250f + p * 100f + phase * 40f, .88f, trail[x] * level) }
            Effect.WATERFALL -> { waterfall[x] = (waterfall[x] * .72f + f.bands[(x + ((tick / 2) % width).toInt()) % AudioFeatures.BAND_COUNT] * .28f).coerceIn(0f, 1f); setHsv(x, 210f + waterfall[x] * 100f, .92f, waterfall[x] * level) }
            Effect.BEAT_RIPPLE -> {
                val centerDistance = abs(x - (width - 1) * .5f)
                val edgeDistance = minOf(x.toFloat(), (width - 1 - x).toFloat())
                val centerRing = (1f - abs(centerDistance - beatCenterRadius) / 1.25f).coerceIn(0f, 1f) * beatCenterEnergy
                val edgeRing = (1f - abs(edgeDistance - beatEdgeRadius) / 1.1f).coerceIn(0f, 1f) * beatEdgeEnergy
                setHsv(x, 190f + p * 150f + phase * 85f, .92f, (centerRing + edgeRing * .72f + f.rms * .10f).coerceIn(0f, 1f) * level)
            }
            Effect.JUGGLE -> { val dot = ((sin(p * TWO_PI * 3f + phase * 4f) + 1f) * .5f); setHsv(x, p * 360f + phase * 100f, .9f, (dot * dot * (.25f + f.rms * .75f)) * level) }
            Effect.PRISM -> { val facet = ((p * 7f + phase * 1.8f).toInt() % 7 + 7) % 7; setHsv(x, facet * (360f / 7f) + f.treble * 28f, .98f, (f.mid * .72f + f.onset * .28f) * level) }
            Effect.BASS_GRADIENT -> setHsv(x, 350f - p * 250f + parameters.hueShift, .92f, (f.bass * (1f - centered * .55f) + f.onset * .30f) * level)
            Effect.WAVE_BANDS -> { val v = (band * .62f + wave * f.rms * .38f).coerceIn(0f, 1f); setHsv(x, 175f + bandIndex * 11f + phase * 75f, .88f, v * level) }
            Effect.SCANNER -> { val position = ((sin(phase * 2.2f) + 1f) * .5f) * (width - 1); val d = abs(x - position); setHsv(x, 125f + p * 80f, .95f, ((1f - d / 3.5f).coerceIn(0f, 1f) * (f.rms + .25f)) * level) }
            Effect.PENDULUM -> { val position = ((sin(phase * 1.35f) + 1f) * .5f) * (width - 1); val d = abs(x - position); setHsv(x, 42f + p * 240f, .9f, ((1f - d / 4.5f).coerceIn(0f, 1f) * (f.bass + f.mid * .45f)) * level) }
            Effect.STARFIELD -> { val sparkle = pseudo(x, tick / 2) > (.93f - f.onset * .18f); setHsv(x, 195f + p * 110f, .45f, (if (sparkle) .5f + f.peak * .5f else f.rms * .12f) * level) }
            Effect.PLASMA -> { val plasma = ((sin(p * TWO_PI * 2f + phase * 2f) + sin(p * TWO_PI * 5f - phase * 1.4f) + 2f) * .25f); setHsv(x, plasma * 360f + parameters.hueShift, .92f, (plasma * (.35f + f.rms * .65f)) * level) }
            Effect.MUSIC_BOX -> { val note = f.bands[(bandIndex + (tick / 3 % AudioFeatures.BAND_COUNT).toInt()) % AudioFeatures.BAND_COUNT]; setHsv(x, 30f + p * 300f, .82f, (note * (.55f + wave * .45f) + f.onset * .18f) * level) }
            Effect.EQUALIZER_SWEEP -> { val shifted = f.bands[(bandIndex + (phase * 5f).toInt()) % AudioFeatures.BAND_COUNT]; setHsv(x, 120f + p * 180f, .93f, (shifted * (.70f + wave * .30f)) * level) }
            // The following are independent, bounded 1D interpretations of the named reference styles.
            Effect.RIPPLE_PEAK -> { val ring = (1f - abs(abs(x - (width - 1) * .5f) - ((phase * 9f) % (width / 2f + 1f))) / 1.35f).coerceIn(0f, 1f); setHsv(x, 190f + p * 120f, .9f, (ring * (f.onset + f.peak * .45f) + f.rms * .06f) * level) }
            Effect.GRAVCENTER -> { val pull = (1f - centered).coerceIn(0f, 1f); setHsv(x, 18f + p * 55f, .92f, (f.bass * pull + f.onset * pull * .7f) * level) }
            Effect.GRAVCENTRIC -> { val orbit = ((sin(centered * 14f - phase * 3f) + 1f) * .5f); setHsv(x, 220f + p * 100f, .9f, (orbit * f.mid * (1f - centered * .35f) + f.onset * .22f) * level) }
            Effect.GRAVIMETER -> { val mass = (f.bass * (1f - centered * .7f) + f.rms * .22f).coerceIn(0f, 1f); setMix(x, 5, 0, 30, 255, 125, 5, mass, level) }
            Effect.GRAVFREQ -> { val weight = f.bands[(bandIndex + 2) % AudioFeatures.BAND_COUNT]; setHsv(x, 260f - p * 190f, .92f, (weight * (1f - centered * .28f) + f.bass * .15f) * level) }
            Effect.JUGGLES -> { val dots = ((sin(p * TWO_PI * 4f + phase * 4.3f) + sin(p * TWO_PI * 7f - phase * 2.1f) + 2f) * .25f); setHsv(x, p * 360f + phase * 110f, .92f, (dots * dots * (.15f + f.rms * .85f)) * level) }
            Effect.MATRIPIX -> { val drop = pseudo(x, tick / 2) > (.78f - f.treble * .40f); setHsv(x, 95f + p * 45f, .88f, ((if (drop) f.treble + .25f else f.rms * .07f) * (1f - p * .36f)) * level) }
            Effect.MIDNOISE -> { val n = pseudo(x, tick / 2); setHsv(x, 145f + n * 110f, .85f, (n * f.mid + f.rms * .10f) * level) }
            Effect.NOISEFIRE -> { val n = pseudo(x, tick / 2); setMix(x, 18, 0, 0, 255, 185, 4, (n * f.bass + f.onset * .42f).coerceIn(0f, 1f), level) }
            Effect.NOISEMETER -> { val n = pseudo(x, tick / 3); val meter = if (p <= f.rms) 1f else 0f; setHsv(x, 120f - p * 115f + n * 15f, .9f, (meter * (.55f + n * .45f)) * level) }
            Effect.PIXELWAVE -> { val crest = ((sin(p * TWO_PI * 2.5f + phase * 3f) + 1f) * .5f); setHsv(x, 185f + p * 100f, .92f, (crest * f.mid + f.treble * (1f - crest) * .45f) * level) }
            Effect.PLASMOID -> { val blob = ((sin(p * TWO_PI * 3f + phase) + sin(p * TWO_PI * 6f - phase * 1.7f) + 2f) * .25f); setHsv(x, blob * 330f + f.treble * 60f, .9f, (blob * (.3f + f.rms * .7f)) * level) }
            Effect.PUDDLEPEAK -> { val radius = ((phase * 7f) % (width / 2f + 1f)); val puddle = (1f - abs(abs(x - (width - 1) * .5f) - radius) / 2.1f).coerceIn(0f, 1f); setHsv(x, 195f + p * 70f, .86f, (puddle * f.onset + f.bass * .16f) * level) }
            Effect.PUDDLES -> { val puddle = ((sin(p * TWO_PI * 2f - phase * 2f) + 1f) * .5f); setMix(x, 0, 8, 35, 0, 180, 255, (puddle * f.bass + f.mid * .20f).coerceIn(0f, 1f), level) }
            Effect.PIXELS -> { val lit = pseudo(x, tick) < (f.rms * .68f + f.onset * .30f); setHsv(x, p * 360f + phase * 80f, .9f, if (lit) level else 0f) }
            Effect.BLURZ -> { val blur = (trail[x] * .78f + band * .22f + f.onset * .12f).coerceIn(0f, 1f); trail[x] = blur; setHsv(x, 245f + p * 90f, .88f, blur * level) }
            Effect.DJ_LIGHT -> { val strobe = if (f.onset > parameters.beatThreshold) 1f else .18f + wave * .22f; setHsv(x, 300f + p * 55f + phase * 120f, .8f, (strobe * (f.peak * .75f + f.rms * .25f)) * level) }
            Effect.FREQMAP -> { val mapped = f.bands[(x * AudioFeatures.BAND_COUNT / width + (phase * 2f).toInt()) % AudioFeatures.BAND_COUNT]; setHsv(x, 10f + p * 300f, .93f, mapped * level) }
            Effect.FREQMATRIX -> { val cell = f.bands[(bandIndex + (tick / 2).toInt()) % AudioFeatures.BAND_COUNT]; setHsv(x, if ((x + (tick / 3).toInt()) % 2 == 0) 105f else 185f, .9f, (cell * (.55f + wave * .45f)) * level) }
            Effect.FREQPIXELS -> { val hit = pseudo(x, tick / 2) < band; setHsv(x, 25f + bandIndex * 19f, .92f, if (hit) (band + .18f).coerceAtMost(1f) * level else 0f) }
            Effect.FREQWAVE -> { val freqWave = ((sin(p * TWO_PI * 2f + phase * 2.8f) + 1f) * .5f); setHsv(x, 165f + p * 150f, .88f, (band * freqWave + f.mid * (1f - freqWave) * .35f) * level) }
            Effect.NOISEMOVE -> { val n = pseudo((x + (phase * 8f).toInt()) % width, tick / 3); setHsv(x, 185f + n * 145f, .9f, (n * f.treble + f.rms * .12f) * level) }
            Effect.ROCKTAVES -> { val octave = f.bands[(bandIndex * 3 + (tick / 4).toInt()) % AudioFeatures.BAND_COUNT]; setHsv(x, 5f + p * 320f, .94f, (octave * (.5f + wave * .5f) + f.bass * .10f) * level) }
            Effect.OCEAN -> setMix(x, 0, 12, 70, 0, 225, 255, (f.mid * .6f + wave * .4f).coerceIn(0f, 1f), level)
            Effect.AURORA -> { val curtain = ((sin(p * TWO_PI * 1.3f + phase) + sin(p * TWO_PI * 3.7f - phase * .6f) + 2f) * .25f); setHsv(x, 135f + curtain * 105f, .82f, (f.mid * curtain + f.rms * .12f) * level) }
            Effect.NEON -> { val tube = if (((p * 12f + phase * 3f).toInt() and 1) == 0) 1f else .08f; setHsv(x, 300f + f.treble * 45f, 1f, tube * f.peak * level) }
            Effect.SUNSET -> setHsv(x, 350f + p * 50f, .9f, (f.rms * .55f + wave * .45f) * level)
            Effect.FOREST -> setMix(x, 0, 20, 8, 85, 255, 75, (f.bass * .7f + wave * .3f).coerceIn(0f, 1f), level)
            Effect.RAINBOW -> { val hue = (p * 360f + phase * 45f + f.spectralCentroid * 50f); setHsv(x, hue, .96f, (f.rms * .72f + f.onset * .28f) * level) }
        }
    }

    private fun circularDistance(a: Float, b: Float): Float { val d = abs(a - b); return minOf(d, width - d) }
    private fun smoothBand(f: AudioFeatures, x: Int): Float {
        val index = (x * AudioFeatures.BAND_COUNT / width).coerceIn(0, f.bands.lastIndex)
        val here = f.bands[index]
        val before = f.bands[(index - 1).coerceIn(0, f.bands.lastIndex)]
        val after = f.bands[(index + 1).coerceIn(0, f.bands.lastIndex)]
        return (before * .22f + here * .56f + after * .22f).coerceIn(0f, 1f)
    }
    private fun pseudo(x: Int, tick: Long): Float { var n = deterministicSeed xor (x * 374761393) xor tick.toInt(); n = (n xor (n ushr 13)) * 1274126177; return ((n xor (n ushr 16)) and 0xffff) / 65535f }
    private fun setGray(x: Int, value: Float) { val c = (value.coerceIn(0f, 1f) * 255f).roundToInt(); setRgb(x, c, c, c) }
    private fun setMix(x: Int, ar: Int, ag: Int, ab: Int, br: Int, bg: Int, bb: Int, t: Float, level: Float) { val v = t.coerceIn(0f, 1f); setRgb(x, ((ar + (br - ar) * v) * level).roundToInt(), ((ag + (bg - ag) * v) * level).roundToInt(), ((ab + (bb - ab) * v) * level).roundToInt()) }
    private fun setHsv(x: Int, hue: Float, saturation: Float, value: Float) {
        val h = ((hue % 360f) + 360f) % 360f; val v = value.coerceIn(0f, 1f); val c = v * saturation.coerceIn(0f, 1f); val q = c * (1f - abs((h / 60f) % 2f - 1f)); val m = v - c
        val sector = (h / 60f).toInt(); val r: Float; val g: Float; val b: Float
        when (sector) { 0 -> { r = c; g = q; b = 0f }; 1 -> { r = q; g = c; b = 0f }; 2 -> { r = 0f; g = c; b = q }; 3 -> { r = 0f; g = q; b = c }; 4 -> { r = q; g = 0f; b = c }; else -> { r = c; g = 0f; b = q } }
        setRgb(x, ((r + m) * 255f).roundToInt(), ((g + m) * 255f).roundToInt(), ((b + m) * 255f).roundToInt())
    }
    private fun setRgb(x: Int, r: Int, g: Int, b: Int) { val at = x * 3; pixels[at] = r.coerceIn(0, 255).toByte(); pixels[at + 1] = g.coerceIn(0, 255).toByte(); pixels[at + 2] = b.coerceIn(0, 255).toByte() }
    private companion object {
        const val MIN_WIDTH = 16
        const val MAX_WIDTH = 512
        const val WIDTH_STEP = 16
        const val TWO_PI = (2.0 * PI).toFloat()
    }
}

object EffectRenderer {
    /** Allocating convenience for unit tests; capture uses EffectFrameRenderer's reusable buffer. */
    fun renderImage(effect: Effect, f: AudioFeatures, brightness: Float, tick: Long, parameters: EffectParameters = EffectParameters()): ByteArray = EffectFrameRenderer().render(effect, f, brightness, tick, parameters).copyOf()
    fun render(effect: Effect, f: AudioFeatures, brightness: Float, tick: Long): Rgb { val frame = renderImage(effect, f, brightness, tick); return Rgb(frame[0].toInt() and 255, frame[1].toInt() and 255, frame[2].toInt() and 255) }
}

/**
 * Expands the reusable one-dimensional audio effect into the router's fixed RGB24 image.
 *
 * Capture owns one full-display shape for its entire lifetime, including live AUDIO
 * transitions. Each output pixel (x, y) explicitly samples the matching audio strip pixel x,
 * preserving the strip's horizontal effect while avoiding a per-frame allocation.
 */
class AudioToFullFrameRenderer(private val target: SourceFrameSpec) {
    private val strip = EffectFrameRenderer(target.width)
    private val fullFrame = ByteArray(target.bytes)

    init { require(target.height > 0 && target.bytes <= HyperionFlatbuffer.MAX_IMAGE_BYTES) }

    fun render(effect: Effect, features: AudioFeatures, brightness: Float, tick: Long, parameters: EffectParameters = EffectParameters()): ByteArray {
        val row = strip.render(effect, features, brightness, tick, parameters)
        for (y in 0 until target.height) {
            val destination = y * target.width * 3
            for (x in 0 until target.width) {
                val source = x * 3
                val output = destination + source
                fullFrame[output] = row[source]
                fullFrame[output + 1] = row[source + 1]
                fullFrame[output + 2] = row[source + 2]
            }
        }
        return fullFrame
    }
}

/** Reusable bounded RGB24 filter; a black frame clears immediately to avoid stale light. */
class RgbFrameSmoother(private val bytes: Int) {
    private val filtered = ByteArray(bytes)
    private var initialized = false
    fun reset() { filtered.fill(0); initialized = false }
    fun apply(source: ByteArray, immediateBlack: Boolean = false): ByteArray {
        require(source.size == bytes)
        if (immediateBlack) { filtered.fill(0); initialized = true; return filtered }
        if (!initialized) { source.copyInto(filtered); initialized = true; return filtered }
        for (i in source.indices) {
            val old = filtered[i].toInt() and 255
            val target = source[i].toInt() and 255
            val limited = (target - old).coerceIn(-MAX_CHANNEL_DELTA, MAX_CHANNEL_DELTA)
            val alpha = if (limited >= 0) ATTACK else RELEASE
            filtered[i] = (old + limited * alpha).toInt().coerceIn(0, 255).toByte()
        }
        return filtered
    }
    private companion object { const val MAX_CHANNEL_DELTA = 72; const val ATTACK = .58f; const val RELEASE = .32f }
}
