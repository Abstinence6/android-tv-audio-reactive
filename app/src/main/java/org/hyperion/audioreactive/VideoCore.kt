package org.hyperion.audioreactive

import android.media.Image
import kotlin.math.abs
import kotlin.math.max

/** Fixed-buffer RGBA ImageReader downsampler and video/audio compositor. */
class VideoFrameProcessor(private val width: Int, private val height: Int) {
    val video = ByteArray(width * height * 3)
    val composite = ByteArray(video.size)
    private var blackFrames = 0

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

    fun compose(features: AudioFeatures?, settings: AudioSettings): ByteArray {
        val hasAudioAccent = features?.signalPresent == true && settings.renderMode == RenderMode.VIDEO_AUDIO
        var p = 0; var zone = 0
        while (p < video.size) {
            var r = video[p].toInt() and 255; var g = video[p + 1].toInt() and 255; var b = video[p + 2].toInt() and 255
            val gain = if (hasAudioAccent) videoAudioGain(features, settings.videoAudioEffect, zone) else 0f
            var brightness = settings.brightness * (1f + settings.audioBoost * gain)
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


    /** Audio only applies a non-negative brightness accent; it never replaces source hue/chroma. */
    private fun videoAudioGain(f: AudioFeatures?, effect: VideoAudioEffect, zone: Int): Float {
        if (f?.signalPresent != true) return 0f
        val band = f.bands[zone % AudioFeatures.BAND_COUNT]
        return when (effect) {
            VideoAudioEffect.BRIGHTNESS_PULSE -> f.rms
            VideoAudioEffect.BEAT_PULSE -> max(f.rms, f.onset)
            VideoAudioEffect.EQ -> band
            VideoAudioEffect.COMET -> if (zone % 16 == ((f.onset * 15).toInt())) max(f.onset, .15f) else f.rms * .18f
            VideoAudioEffect.RIPPLE -> max(0f, f.onset - abs((zone % 16) - 8) / 16f)
            VideoAudioEffect.BASS_SWEEP -> f.bass * (1f - abs((zone % 16) - ((f.bass * 15).toInt())) / 16f)
        }.coerceIn(0f, 1f)
    }

    companion object { const val BLACK_FRAME_HOLD = 30 }
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
    data class Result(val mode: RenderMode, val audioChecked: Boolean, val videoChecked: Boolean, val rejected: Boolean)
    fun resolve(audio: Boolean, video: Boolean, previous: RenderMode): Result = when {
        audio && video -> Result(RenderMode.VIDEO_AUDIO, true, true, false)
        audio -> Result(RenderMode.AUDIO, true, false, false)
        video -> Result(RenderMode.VIDEO, false, true, false)
        else -> when (previous) {
            RenderMode.AUDIO -> Result(previous, true, false, true)
            RenderMode.VIDEO -> Result(previous, false, true, true)
            RenderMode.VIDEO_AUDIO -> Result(previous, true, true, true)
        }
    }
}
object LeanbackTabPolicy {
    /** Exactly two fixed Ukrainian D-pad tabs. */
    val tabs = listOf("Керування", "Додатково")
    fun controls(tab: Int) = when (tab) {
        0 -> listOf("toggle", "capture-mode", "compatible-effects", "rainbow-visual-source")
        1 -> listOf("source-video-quality", "shared-fps", "base-video-colour-treatment", "video-saturation", "live-effect-controls", "outputs", "discovery", "wled-source-zones", "calibration")
        else -> emptyList()
    }
}
object TvTabSelectionPolicy {
    fun selectedIndex(requested: Int): Int = requested.coerceIn(0, LeanbackTabPolicy.tabs.lastIndex)
    fun panelIsVisible(panel: Int, selected: Int): Boolean = panel == selectedIndex(selected)
}
object EffectSelectionPolicy { fun enabledWhileCaptureActive() = true }
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
    fun showVideoControls(mode: RenderMode): Boolean = mode != RenderMode.AUDIO
    fun showVideoColourTreatment(mode: RenderMode): Boolean = VideoColourTreatmentPolicy.visible(mode)
    fun showVideoSaturation(mode: RenderMode): Boolean = mode != RenderMode.AUDIO
    fun showWledZones(output: OutputMode): Boolean = output == OutputMode.WLED
}
