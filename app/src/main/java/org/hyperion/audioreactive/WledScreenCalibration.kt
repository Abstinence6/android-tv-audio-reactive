package org.hyperion.audioreactive

import kotlin.math.pow

enum class PerimeterDirection { CW, CCW }
enum class ScreenEdge { BOTTOM, RIGHT, TOP, LEFT }

/** Persisted, app-only mapping from screen edges to one immutable WLED physical strip. */
data class WledScreenCalibration(
    val identity: String,
    val physicalLedCount: Int,
    val startPixel: Int = 0,
    val direction: PerimeterDirection = PerimeterDirection.CW,
    val bottom: Int,
    val right: Int,
    val top: Int,
    val left: Int,
    val bottomInsetPercent: Int = 0,
    val rightInsetPercent: Int = 0,
    val topInsetPercent: Int = 0,
    val leftInsetPercent: Int = 0,
    val depthPercent: Int = 10,
    val samplesPerEdge: Int = 16,
    val gamma: Float = 2.2f,
    val brightnessLimit: Float = 1f,
) {
    fun validFor(device: WledDevice? = null): Boolean =
        isMacIdentity(identity) && physicalLedCount in 1..4096 && startPixel in 0 until physicalLedCount &&
            bottom >= 0 && right >= 0 && top >= 0 && left >= 0 && bottom + right + top + left == physicalLedCount &&
            listOf(bottomInsetPercent, rightInsetPercent, topInsetPercent, leftInsetPercent).all { it in 0..45 } &&
            depthPercent in 2..25 && samplesPerEdge in 4..64 && samplesPerEdge % 4 == 0 &&
            gamma in 1f..3.5f && brightnessLimit in .05f..1f &&
            (device == null || device.identity == identity && device.leds == physicalLedCount)

    fun allocation(edge: ScreenEdge) = when (edge) {
        ScreenEdge.BOTTOM -> bottom; ScreenEdge.RIGHT -> right; ScreenEdge.TOP -> top; ScreenEdge.LEFT -> left
    }
    fun inset(edge: ScreenEdge) = when (edge) {
        ScreenEdge.BOTTOM -> bottomInsetPercent; ScreenEdge.RIGHT -> rightInsetPercent
        ScreenEdge.TOP -> topInsetPercent; ScreenEdge.LEFT -> leftInsetPercent
    }

    companion object {
        fun proportional(identity: String, leds: Int): WledScreenCalibration {
            require(leds in 1..4096)
            val base = leds / 4; val rem = leds % 4
            return WledScreenCalibration(identity, leds, 0, PerimeterDirection.CW,
                base + if (rem > 0) 1 else 0, base + if (rem > 1) 1 else 0,
                base + if (rem > 2) 1 else 0, base)
        }
    }
}

/** App-side perimeter remapper. It neither reads nor changes controller state. */
class WledPerimeterMapper(private val input: SourceFrameSpec, private val calibration: WledScreenCalibration) {
    private val reusable = ByteArray(calibration.physicalLedCount * 3)
    private val activeBounds = ActiveContentBounds(input)
    init { require(calibration.validFor()); require(input.bytes <= HyperionFlatbuffer.MAX_IMAGE_BYTES) }
    fun map(rgb: ByteArray): ByteArray {
        require(rgb.size == input.bytes); activeBounds.update(rgb)
        var logical = 0
        for (edge in edgeOrder) for (n in 0 until calibration.allocation(edge)) {
            val physical = if (calibration.direction == PerimeterDirection.CW) (calibration.startPixel + logical) % calibration.physicalLedCount
                else (calibration.startPixel - logical + calibration.physicalLedCount) % calibration.physicalLedCount
            sampleStripInto(rgb, edge, n, calibration.allocation(edge), physical * 3); logical++
        }
        return reusable
    }
    /** Average every pixel from the active edge inward, retaining calibration's insets and depth. */
    private fun sampleStripInto(rgb: ByteArray, edge: ScreenEdge, n: Int, count: Int, destination: Int) {
        val bounds = activeBounds; val inset = calibration.inset(edge)
        val insetX = bounds.width * inset / 100; val insetY = bounds.height * inset / 100
        val depthX = (bounds.width * calibration.depthPercent / 100).coerceAtLeast(1); val depthY = (bounds.height * calibration.depthPercent / 100).coerceAtLeast(1)
        val p = (n * calibration.samplesPerEdge / count).coerceIn(0, calibration.samplesPerEdge - 1); val t = (p.toFloat() + .5f) / calibration.samplesPerEdge
        val lateralX = when (edge) { ScreenEdge.BOTTOM -> bounds.left + (insetX + t * (bounds.width - 2 * insetX - 1)).toInt(); ScreenEdge.RIGHT -> 0; ScreenEdge.TOP -> bounds.left + (bounds.width - insetX - 1 - t * (bounds.width - 2 * insetX - 1)).toInt(); ScreenEdge.LEFT -> 0 }.coerceIn(bounds.left, bounds.right)
        val lateralY = when (edge) { ScreenEdge.BOTTOM -> 0; ScreenEdge.RIGHT -> bounds.top + (bounds.height - insetY - 1 - t * (bounds.height - 2 * insetY - 1)).toInt(); ScreenEdge.TOP -> 0; ScreenEdge.LEFT -> bounds.top + (insetY + t * (bounds.height - 2 * insetY - 1)).toInt() }.coerceIn(bounds.top, bounds.bottom)
        var r = 0; var g = 0; var b = 0; val depth = if (edge == ScreenEdge.BOTTOM || edge == ScreenEdge.TOP) depthY else depthX
        for (d in 0 until depth) {
            val x = when (edge) { ScreenEdge.BOTTOM, ScreenEdge.TOP -> lateralX; ScreenEdge.RIGHT -> bounds.right - insetX - d; ScreenEdge.LEFT -> bounds.left + insetX + d }.coerceIn(bounds.left, bounds.right)
            val y = when (edge) { ScreenEdge.BOTTOM -> bounds.bottom - insetY - d; ScreenEdge.TOP -> bounds.top + insetY + d; ScreenEdge.RIGHT, ScreenEdge.LEFT -> lateralY }.coerceIn(bounds.top, bounds.bottom)
            val source = (y * input.width + x) * 3; r += rgb[source].toInt() and 255; g += rgb[source + 1].toInt() and 255; b += rgb[source + 2].toInt() and 255
        }
        reusable[destination] = corrected((r / depth).toByte()); reusable[destination + 1] = corrected((g / depth).toByte()); reusable[destination + 2] = corrected((b / depth).toByte())
    }
    private fun corrected(value: Byte): Byte = (((value.toInt() and 255) / 255.0).pow(1.0 / calibration.gamma) * calibration.brightnessLimit * 255.0).toInt().coerceIn(0, 255).toByte()
    companion object { /** One shared edge order: map() must not allocate an edge array on every frame. */ internal val edgeOrder = ScreenEdge.entries }
}

/** Conservative in-place detector for symmetric, uniform near-black cast bars. */
private class ActiveContentBounds(private val input: SourceFrameSpec) {
    var left = 0; var top = 0; var right = input.width - 1; var bottom = input.height - 1; var width = input.width; var height = input.height
    fun update(rgb: ByteArray) {
        fullFrame(); val l = darkColumns(rgb, 0, 1); val r = darkColumns(rgb, input.width - 1, -1)
        if (usablePair(l, r, input.width) && clearlyLit(rgb, l, 0, input.width - l - r, input.height)) { left = l; right = input.width - r - 1; width = right - left + 1 }
        val t = darkRows(rgb, 0, 1); val b = darkRows(rgb, input.height - 1, -1)
        if (usablePair(t, b, input.height) && clearlyLit(rgb, 0, t, input.width, input.height - t - b)) { top = t; bottom = input.height - b - 1; height = bottom - top + 1 }
    }
    private fun fullFrame() { left = 0; top = 0; right = input.width - 1; bottom = input.height - 1; width = input.width; height = input.height }
    private fun usablePair(a: Int, b: Int, size: Int): Boolean { val minBar = (size / 8).coerceAtLeast(2); return a >= minBar && b >= minBar && a <= size * 5 / 16 && b <= size * 5 / 16 && kotlin.math.abs(a - b) <= (size / 32).coerceAtLeast(2) && size - a - b >= size * 3 / 8 }
    private fun darkColumns(rgb: ByteArray, start: Int, step: Int): Int { var count = 0; var x = start; while (x in 0 until input.width && uniformNearBlack(rgb, x, 0, 1, input.height)) { count++; x += step }; return count }
    private fun darkRows(rgb: ByteArray, start: Int, step: Int): Int { var count = 0; var y = start; while (y in 0 until input.height && uniformNearBlack(rgb, 0, y, input.width, 1)) { count++; y += step }; return count }
    private fun uniformNearBlack(rgb: ByteArray, startX: Int, startY: Int, spanX: Int, spanY: Int): Boolean { for (y in startY until startY + spanY) for (x in startX until startX + spanX) { val at = (y * input.width + x) * 3; val r = rgb[at].toInt() and 255; val g = rgb[at + 1].toInt() and 255; val b = rgb[at + 2].toInt() and 255; if (r > 16 || g > 16 || b > 16 || maxOf(r, g, b) - minOf(r, g, b) > 8) return false }; return true }
    /** A crop needs broadly visible content, not isolated highlights in a dark scene. */
    private fun clearlyLit(rgb: ByteArray, startX: Int, startY: Int, spanX: Int, spanY: Int): Boolean { var lit = 0; for (gy in 0 until 8) for (gx in 0 until 8) { val x = startX + ((gx * 2 + 1) * spanX / 16).coerceIn(0, spanX - 1); val y = startY + ((gy * 2 + 1) * spanY / 16).coerceIn(0, spanY - 1); val at = (y * input.width + x) * 3; if ((rgb[at].toInt() and 255) + (rgb[at + 1].toInt() and 255) + (rgb[at + 2].toInt() and 255) >= 96) lit++ }; return lit >= 16 }
}

/**
 * Independent D-pad edge editing policy. A draft may be incomplete; it is saved only after its
 * explicit remaining count is zero. This deliberately never borrows LEDs from another edge.
 */
object WledCalibrationEditor {
    fun changeAllocation(c: WledScreenCalibration, edge: ScreenEdge, delta: Int): WledScreenCalibration {
        if (delta == 0) return c
        val next = (c.allocation(edge) + delta).coerceIn(0, c.physicalLedCount)
        return when (edge) {
            ScreenEdge.BOTTOM -> c.copy(bottom = next)
            ScreenEdge.RIGHT -> c.copy(right = next)
            ScreenEdge.TOP -> c.copy(top = next)
            ScreenEdge.LEFT -> c.copy(left = next)
        }
    }
    fun remaining(c: WledScreenCalibration): Int = c.physicalLedCount - ScreenEdge.entries.sumOf(c::allocation)
    fun changeInset(c: WledScreenCalibration, edge: ScreenEdge, delta: Int): WledScreenCalibration = when (edge) {
        ScreenEdge.BOTTOM -> c.copy(bottomInsetPercent = (c.bottomInsetPercent + delta).coerceIn(0, 45))
        ScreenEdge.RIGHT -> c.copy(rightInsetPercent = (c.rightInsetPercent + delta).coerceIn(0, 45))
        ScreenEdge.TOP -> c.copy(topInsetPercent = (c.topInsetPercent + delta).coerceIn(0, 45))
        ScreenEdge.LEFT -> c.copy(leftInsetPercent = (c.leftInsetPercent + delta).coerceIn(0, 45))
    }
}

object WledCalibrationWizardPolicy {
    fun editable(captureActive: Boolean) = !captureActive
    fun canSave(captureActive: Boolean, calibration: WledScreenCalibration, device: WledDevice) = editable(captureActive) && calibration.validFor(device)
}

object WledCalibrationPolicy {
    fun routeable(settings: AudioSettings, device: WledDevice): Boolean =
        settings.renderMode == RenderMode.AUDIO || settings.calibrationFor(device)?.validFor(device) == true
    fun skipped(settings: AudioSettings, fresh: Collection<WledDevice>): List<String> = fresh.filterNot { routeable(settings, it) }.map { it.identity }
}
