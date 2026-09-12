package org.hyperion.audioreactive

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable

/** Local-only visual catalog; it has no capture, route, socket, or output dependency. */
enum class LocalVisualPattern(
    val colors: IntArray? = null,
    val style: LocalVisualStyle = LocalVisualStyle.SOLID_OR_GRADIENT,
) {
    RAINBOW(style = LocalVisualStyle.RAINBOW),
    BLACK(intArrayOf(Color.BLACK)), WHITE(intArrayOf(Color.WHITE)), RED(intArrayOf(Color.RED)),
    GREEN(intArrayOf(Color.GREEN)), BLUE(intArrayOf(Color.BLUE)), RGB(intArrayOf(Color.RED, Color.GREEN, Color.BLUE)),
    HORIZONTAL_BARS(style = LocalVisualStyle.HORIZONTAL_BARS), VERTICAL_BARS(style = LocalVisualStyle.VERTICAL_BARS),
    CORNER_COLOURS(style = LocalVisualStyle.CORNER_COLOURS), COLOUR_WHEEL(style = LocalVisualStyle.COLOUR_WHEEL),
    CHECKERBOARD(style = LocalVisualStyle.CHECKERBOARD), GRAYSCALE_RAMP(style = LocalVisualStyle.GRAYSCALE_RAMP),
    MOVING_BARS(style = LocalVisualStyle.MOVING_BARS),
}

enum class LocalVisualStyle {
    SOLID_OR_GRADIENT, RAINBOW, HORIZONTAL_BARS, VERTICAL_BARS, CORNER_COLOURS,
    COLOUR_WHEEL, CHECKERBOARD, GRAYSCALE_RAMP, MOVING_BARS;

    val animated get() = this == RAINBOW || this == MOVING_BARS
}

/** Pure cycle contract so every visible pattern remains reachable from the one local button. */
object LocalVisualPatternPolicy {
    val patterns: List<LocalVisualPattern> = LocalVisualPattern.entries
    fun next(index: Int): Int = (index + 1) % patterns.size
}

/** Procedural display-only drawable; it owns no capture, route, socket, or output state. */
internal class LocalVisualPatternDrawable(
    private val style: LocalVisualStyle,
    private var phase: Float = 0f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bars = intArrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE)

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat().coerceAtLeast(1f)
        val height = bounds.height().toFloat().coerceAtLeast(1f)
        when (style) {
            LocalVisualStyle.HORIZONTAL_BARS -> drawBars(canvas, width, height, horizontal = true)
            LocalVisualStyle.VERTICAL_BARS -> drawBars(canvas, width, height, horizontal = false)
            LocalVisualStyle.CORNER_COLOURS -> drawCorners(canvas, width, height)
            LocalVisualStyle.COLOUR_WHEEL -> {
                paint.shader = SweepGradient(width / 2f, height / 2f, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
                canvas.drawRect(0f, 0f, width, height, paint)
                paint.shader = null
            }
            LocalVisualStyle.CHECKERBOARD -> drawCheckerboard(canvas, width, height)
            LocalVisualStyle.GRAYSCALE_RAMP -> {
                paint.shader = LinearGradient(0f, 0f, width, 0f, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width, height, paint)
                paint.shader = null
            }
            LocalVisualStyle.MOVING_BARS -> drawMovingBars(canvas, width, height)
            else -> error("Drawable is only used for procedural local patterns")
        }
    }

    private fun drawBars(canvas: Canvas, width: Float, height: Float, horizontal: Boolean) {
        bars.forEachIndexed { index, color ->
            paint.color = color
            if (horizontal) canvas.drawRect(0f, height * index / bars.size, width, height * (index + 1) / bars.size, paint)
            else canvas.drawRect(width * index / bars.size, 0f, width * (index + 1) / bars.size, height, paint)
        }
    }

    private fun drawCorners(canvas: Canvas, width: Float, height: Float) {
        val cells = arrayOf(
            intArrayOf(Color.YELLOW, Color.RED, Color.MAGENTA),
            intArrayOf(Color.WHITE, Color.BLACK, Color.GREEN),
            intArrayOf(Color.CYAN, Color.BLUE, Color.WHITE),
        )
        cells.forEachIndexed { row, colours -> colours.forEachIndexed { column, color ->
            paint.color = color
            canvas.drawRect(width * column / 3f, height * row / 3f, width * (column + 1) / 3f, height * (row + 1) / 3f, paint)
        } }
    }

    private fun drawCheckerboard(canvas: Canvas, width: Float, height: Float) {
        val cell = (minOf(width, height) / 8f).coerceAtLeast(1f)
        var y = 0f
        var row = 0
        while (y < height) {
            var x = 0f
            var column = 0
            while (x < width) {
                paint.color = if ((row + column) % 2 == 0) Color.WHITE else Color.BLACK
                canvas.drawRect(x, y, x + cell, y + cell, paint)
                x += cell; column++
            }
            y += cell; row++
        }
    }

    private fun drawMovingBars(canvas: Canvas, width: Float, height: Float) {
        val barWidth = width / bars.size
        bars.forEachIndexed { index, color ->
            paint.color = color
            val left = ((index * barWidth + phase * width) % (width + barWidth)) - barWidth
            canvas.drawRect(left, 0f, left + barWidth, height, paint)
            canvas.drawRect(left - (width + barWidth), 0f, left - (width + barWidth) + barWidth, height, paint)
        }
    }

    /** Redraw the existing local-only moving-bars surface without allocating a new Drawable. */
    fun updatePhase(value: Float) {
        phase = value
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}
