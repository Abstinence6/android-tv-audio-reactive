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
    var signalPresent: Boolean = rms > 0f || peak > 0f
) {
    companion object { const val BAND_COUNT = 16 }
}
data class Rgb(val r: Int, val g: Int, val b: Int)
/** Bounded persisted controls used directly by the audio renderer. */
data class EffectParameters(val speed: Float = 1f, val trail: Float = .5f, val beatThreshold: Float = .2f, val hueShift: Float = 0f) {
    fun valid() = speed in .25f..3f && trail in 0f..1f && beatThreshold in .05f.. .95f && hueShift in -180f..180f
}

enum class Effect(val label: String, val wled1dReferenceStyle: Boolean = false) {
    // Existing selections remain stable for persisted settings.
    SPECTRUM("Spectrum"), PULSE("Pulse"), FIRE("Fire"), OCEAN("Ocean"), AURORA("Aurora"),
    NEON("Neon"), SUNSET("Sunset"), FOREST("Forest"), MONOCHROME("Monochrome"), RAINBOW("Rainbow"),
    MEL_SPECTRUM("Mel Spectrum / 16-band GEQ"), BASS_PULSE("Bass Pulse"), BASS_CHASE("Bass Chase"),
    RUNNING_SPARKS("Running Sparks / Comet"), METEOR_TRAILS("Meteor Trails"),
    BEAT_EXPLOSION("Beat Explosion"), EMBERS("Fire / Embers"), FIREFLIES("Fireflies / Twinkle"),
    COLOR_WAVES("Color Waves"), THREE_BAND_RATIO("Three-band Ratio"), DYNAMIC_HUE("Dynamic Hue"),
    COLOR_ORGAN("Color Organ"), VU_PEAK_HOLD("VU Peak Hold"), BLURZ_TRAILS("Blurz / Trails"),
    WATERFALL("Waterfall", true), BEAT_RIPPLE("Beat Ripple"),
    // Original app-side interpretations of familiar audio-reactive styles; no WLED code is used.
    JUGGLE("Juggle"), PRISM("Prism"), BASS_GRADIENT("Bass Gradient"),
    WAVE_BANDS("Wave Bands"), SCANNER("Scanner"), PENDULUM("Pendulum"),
    STARFIELD("Starfield"), PLASMA("Plasma"), MUSIC_BOX("Music Box"),
    EQUALIZER_SWEEP("Equalizer Sweep"),
    // Every 1D Audio Reactive style in the WLED reference catalogue, independently implemented here.
    RIPPLE_PEAK("Ripple Peak", true), GRAVCENTER("Gravcenter", true), GRAVCENTRIC("Gravcentric", true),
    GRAVIMETER("Gravimeter", true), GRAVFREQ("Gravfreq", true), JUGGLES("Juggles", true),
    MATRIPIX("Matripix", true), MIDNOISE("Midnoise", true), NOISEFIRE("Noisefire", true),
    NOISEMETER("Noisemeter", true), PIXELWAVE("Pixelwave", true), PLASMOID("Plasmoid", true),
    PUDDLEPEAK("Puddlepeak", true), PUDDLES("Puddles", true), PIXELS("Pixels", true),
    BLURZ("Blurz", true), DJ_LIGHT("DJ Light", true), FREQMAP("Freqmap", true),
    FREQMATRIX("Freqmatrix", true), FREQPIXELS("Freqpixels", true), FREQWAVE("Freqwave", true),
    NOISEMOVE("Noisemove", true), ROCKTAVES("Rocktaves", true)
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
    private var hannWindow = FloatArray(CaptureCadence.ANALYSIS_SAMPLES)
    private var windowCount = 0
    private val reusableFeatures = AudioFeatures(0f, 0f, 0f, 0f, 0f, 0f, bands)
    private var smoothedLevel = 0f
    private var adaptivePeak = MIN_ADAPTIVE_PEAK
    private var noiseFloor = 0f
    private var previousLevel = 0f
    private var onsetBaseline = 0f
    private var onsetRefractoryFrames = 0

    fun reset() {
        bands.fill(0f); bandEnvelope.fill(0f); bandPeak.fill(MIN_BAND_PEAK); bandNoiseFloor.fill(0f)
        smoothedLevel = 0f; adaptivePeak = MIN_ADAPTIVE_PEAK; noiseFloor = 0f; previousLevel = 0f
        onsetBaseline = 0f; onsetRefractoryFrames = 0
    }

    fun analyze(pcm: ShortArray, sensitivity: Float): AudioFeatures = analyze(pcm, pcm.size, sensitivity)

    fun analyze(pcm: ShortArray, sampleCount: Int, sensitivity: Float): AudioFeatures {
        val count = sampleCount.coerceIn(0, pcm.size)
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
        val bass = averageBands(0, 4)
        val mid = averageBands(5, 10)
        val treble = averageBands(11, AudioFeatures.BAND_COUNT)
        return setFeatures(smoothedLevel, (peak * gain).coerceIn(0f, 1f), onset, bass, mid, treble, gated > 0f)
    }

    private fun features(rms: Float, peak: Float, onset: Float) = setFeatures(rms, peak, onset, 0f, 0f, 0f, false)
    private fun setFeatures(rms: Float, peak: Float, onset: Float, bass: Float, mid: Float, treble: Float, signalPresent: Boolean): AudioFeatures {
        reusableFeatures.rms = rms; reusableFeatures.peak = peak; reusableFeatures.onset = onset
        reusableFeatures.bass = bass; reusableFeatures.mid = mid; reusableFeatures.treble = treble
        reusableFeatures.signalPresent = signalPresent
        return reusableFeatures
    }
    private fun averageBands(start: Int, end: Int): Float { var sum = 0f; for (i in start until end) sum += bands[i]; return sum / (end - start) }
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
    private var emberPosition = 0f
    private var beatCenterRadius = 0f
    private var beatEdgeRadius = 0f
    private var beatCenterEnergy = 0f
    private var beatEdgeEnergy = 0f
    private var deterministicSeed = 0x13579bdf

    fun reset() {
        trail.fill(0f); peakHold.fill(0f); waterfall.fill(0f); fireHeat.fill(0f); bassPulse.fill(0f)
        activeEffect = null; phase = 0f; cometPosition = 0f; emberPosition = 0f
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
        cometPosition = (cometPosition + (.18f + features.bass * .48f) * parameters.speed) % width
        deterministicSeed = deterministicSeed * 1664525 + 1013904223
        updateEffectState(effect, features, parameters)
        for (x in 0 until width) renderPixel(effect, features, level, tick, x, parameters)
        return pixels
    }

    private fun resetEffectState(effect: Effect) {
        trail.fill(0f); peakHold.fill(0f); waterfall.fill(0f); fireHeat.fill(0f); bassPulse.fill(0f)
        emberPosition = 0f; beatCenterRadius = 0f; beatEdgeRadius = 0f; beatCenterEnergy = 0f; beatEdgeEnergy = 0f
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
            Effect.SPECTRUM -> setHsv(x, 15f + p * 300f + parameters.hueShift, .94f, smoothBand(f, x) * level)
            Effect.MEL_SPECTRUM -> setHsv(x, 15f + p * 300f, .94f, band * level)
            // Pulse is global RMS/onset; Bass Pulse propagates outward from the centre.
            Effect.PULSE -> setHsv(x, 335f + p * 48f, .88f, (f.rms + f.onset * .68f).coerceIn(0f, 1f) * level)
            Effect.BASS_PULSE -> setHsv(x, 335f + p * 48f, .88f, (bassPulse[x] * (1f - centered * .20f)).coerceIn(0f, 1f) * level)
            Effect.BASS_CHASE -> { val d = circularDistance(x.toFloat(), cometPosition); setHsv(x, 12f + p * 52f, .95f, (f.bass * (1f - d / 5f).coerceIn(0f, 1f) + .05f * wave) * level) }
            Effect.RUNNING_SPARKS -> { val d = circularDistance(x.toFloat(), cometPosition); trail[x] = max(trail[x] * (.45f + parameters.trail * .5f), (1f - d / 2.2f).coerceIn(0f, 1f) * (f.rms + .35f)); setHsv(x, 38f + p * 42f + parameters.hueShift, .75f, trail[x] * level) }
            Effect.METEOR_TRAILS -> { val d = (x - cometPosition + width) % width; trail[x] = max(trail[x] * .84f, if (d < 1.2f) (f.onset + f.bass).coerceAtLeast(.35f) else 0f); setHsv(x, 195f + p * 75f, .92f, trail[x] * (1f - d / width * .65f) * level) }
            Effect.BEAT_EXPLOSION -> setHsv(x, 5f + p * 280f, .92f, (f.onset * (1f - centered) + f.rms * wave * .45f).coerceIn(0f, 1f) * level)
            // Fire retains a cooling heat field; Embers only ignites sparse drifting sparks.
            Effect.FIRE -> setMix(x, 36, 0, 0, 255, 198, 18, fireHeat[x], level)
            Effect.EMBERS -> setMix(x, 0, 0, 0, 255, 92, 8, trail[x], level)
            Effect.FIREFLIES -> { val sparkle = pseudo(x, tick / 3) > .84f; setHsv(x, 52f + p * 38f, .72f, ((if (sparkle) .25f + f.onset else .025f) + f.rms * wave * .34f) * level) }
            Effect.COLOR_WAVES -> setHsv(x, p * 360f + phase * 90f + f.treble * 80f, .90f, (f.rms * (.45f + wave * .55f)) * level)
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
            Effect.PRISM -> setHsv(x, p * 360f + phase * 150f + f.treble * 100f, .95f, (f.mid * .45f + wave * .55f) * level)
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
            Effect.AURORA -> setHsv(x, 265f + p * 80f + phase * 80f, .9f, (f.rms * .45f + wave * .55f) * level)
            Effect.NEON -> setHsv(x, 285f + p * 105f + f.treble * 80f + phase * 130f, 1f, f.peak * (.45f + wave * .55f) * level)
            Effect.SUNSET -> setHsv(x, 350f + p * 50f, .9f, (f.rms * .55f + wave * .45f) * level)
            Effect.FOREST -> setMix(x, 0, 20, 8, 85, 255, 75, (f.bass * .7f + wave * .3f).coerceIn(0f, 1f), level)
            Effect.RAINBOW -> setHsv(x, p * 360f + phase * 120f + f.treble * 90f, .9f, f.rms * (.6f + wave * .4f) * level)
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
