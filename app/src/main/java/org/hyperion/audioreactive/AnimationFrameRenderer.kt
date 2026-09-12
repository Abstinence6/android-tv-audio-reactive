package org.hyperion.audioreactive

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Fixed-buffer procedural renderer for route-preflighted no-input animation output. */
class AnimationFrameRenderer(private val frame: SourceFrameSpec) {
    private val pixels = ByteArray(frame.bytes)
    init { require(frame.width > 0 && frame.height > 0) }

    fun render(effect: AnimationEffect, colour: AnimationColour, brightness: Float, tick: Long, parameters: EffectParameters): ByteArray {
        val time = tick * .055f * parameters.speed
        var pixel = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val nx = x.toFloat() / (frame.width - 1).coerceAtLeast(1)
            val ny = y.toFloat() / (frame.height - 1).coerceAtLeast(1)
            val wave = ((sin(nx * 12f + time) + sin(ny * 9f - time * .7f) + 2f) * .25f).coerceIn(0f, 1f)
            val comet = (1f - abs(nx - ((time * .12f) % 1f)) * 8f).coerceIn(0f, 1f)
            val secondComet = (1f - abs(nx - ((.5f + time * .09f) % 1f)) * 10f).coerceIn(0f, 1f)
            val sparkle = ((sin((x * 17 + y * 31).toFloat() + time * 7f) + 1f) * .5f)
            val baseHue = paletteHue(colour, effect, time)
            val (hue, saturation, value) = when (effect) {
                // Water is fast, small overlapping ripples with a bright shallow surface.
                AnimationEffect.WATER -> {
                    val ripples = ((sin(nx * 20f + time * 2.4f) + sin((nx + ny) * 14f - time * 1.7f) + 2f) * .25f)
                    Triple(baseHue + ripples * 18f, .72f, .16f + ripples * .78f)
                }
                // Lake is intentionally calm: wide, slow horizontal bands and a darker depth gradient.
                AnimationEffect.LAKE -> {
                    val bands = ((sin(ny * 18f - time * .55f) + sin(ny * 7f + nx * 2f + time * .3f) + 2f) * .25f)
                    Triple(baseHue - 12f + bands * 10f, .60f, (.10f + bands * .54f) * (.72f + ny * .28f))
                }
                // Ocean uses diagonal swells, deep blue troughs, and a narrow moving foam crest.
                AnimationEffect.OCEAN -> {
                    val swellPhase = nx * 10f + ny * 7f - time * 1.35f
                    val swell = ((sin(swellPhase) + 1f) * .5f)
                    val foam = ((sin(swellPhase * 1.9f + nx * 8f) - .72f) / .28f).coerceIn(0f, 1f)
                    Triple(baseHue + 12f + foam * 18f, .82f - foam * .38f, .06f + swell * .62f + foam * .32f)
                }
                AnimationEffect.COMET_STREAM -> Triple(baseHue, .55f, .05f + comet)
                AnimationEffect.DOUBLE_COMET -> Triple(baseHue + secondComet * 75f, .65f, .04f + maxOf(comet, secondComet))
                AnimationEffect.METEOR_SHOWER -> Triple(baseHue + nx * 50f, .45f, .03f + sparkle * comet)
                // Candle is a single warm flame with a small, irregular halo above its wick.
                AnimationEffect.CANDLE -> {
                    val flameX = .5f + sin(time * 2.8f) * .035f
                    val flame = (1f - sqrt((nx - flameX) * (nx - flameX) * 9f + (ny - .48f) * (ny - .48f) * 3.2f) * 1.7f).coerceIn(0f, 1f)
                    val flicker = .72f + sin(time * 8f + nx * 11f) * .14f
                    Triple(if (colour == AnimationColour.AUTO) 28f + flame * 18f else baseHue, .92f, .025f + flame * flicker)
                }
                // Fireplace is a wide ember bed with multiple independent upward flames.
                AnimationEffect.FIREPLACE -> {
                    val ember = ((sin(nx * 28f + time * 2f) + 1f) * .5f) * (1f - ny).coerceIn(0f, 1f)
                    val flameA = (1f - abs(nx - .28f - sin(time * 1.7f) * .06f) * 4.5f - ny * 1.35f).coerceIn(0f, 1f)
                    val flameB = (1f - abs(nx - .67f - sin(time * 2.1f + 1f) * .08f) * 3.8f - ny * 1.15f).coerceIn(0f, 1f)
                    val heat = maxOf(ember * .42f, flameA, flameB)
                    Triple(if (colour == AnimationColour.AUTO) 12f + heat * 34f else baseHue, .96f, .035f + heat * .92f)
                }
                AnimationEffect.EMBERS -> Triple(if (colour == AnimationColour.AUTO) 22f else baseHue, .94f, (.18f + sparkle * .82f) * (1f - ny * .35f))
                // Fireworks are one bright expanding radial ring against a dark sky.
                AnimationEffect.FIREWORKS -> {
                    val burst = (time * .18f) % 1f
                    val centerX = .2f + ((tick / 18L) % 4) * .22f
                    val centerY = .24f + ((tick / 31L) % 3) * .16f
                    val radius = sqrt((nx - centerX) * (nx - centerX) + (ny - centerY) * (ny - centerY))
                    val ring = (1f - abs(radius - (.06f + burst * .62f)) * 22f).coerceIn(0f, 1f) * (1f - burst * .55f)
                    val spokes = ((sin(radius * 110f - time * 6f + nx * 18f) + 1f) * .5f)
                    Triple(baseHue + nx * 145f + burst * 80f, .88f - burst * .22f, .012f + ring * (.35f + spokes * .65f))
                }
                // Firework bursts overlap three smaller blooms, each with a separate cadence.
                AnimationEffect.FIREWORK_BURSTS -> {
                    val phaseA = (time * .24f) % 1f
                    val phaseB = (time * .17f + .37f) % 1f
                    val phaseC = (time * .21f + .71f) % 1f
                    fun bloom(cx: Float, cy: Float, phase: Float): Float {
                        val radius = sqrt((nx - cx) * (nx - cx) + (ny - cy) * (ny - cy))
                        return (1f - abs(radius - (.03f + phase * .38f)) * 30f).coerceIn(0f, 1f) * (1f - phase * .5f)
                    }
                    val burst = maxOf(bloom(.22f, .28f, phaseA), bloom(.54f, .45f, phaseB), bloom(.78f, .22f, phaseC))
                    Triple(baseHue + ny * 120f + time * 35f, .9f, .01f + burst * .99f)
                }
                // A sparkler has a fixed hot wand tip and dense, short-lived outward sparks.
                AnimationEffect.SPARKLER -> {
                    val tipX = .5f + sin(time * 1.4f) * .12f
                    val tipY = .64f + sin(time * 2.1f) * .04f
                    val distance = sqrt((nx - tipX) * (nx - tipX) + (ny - tipY) * (ny - tipY))
                    val angle = kotlin.math.atan2(ny - tipY, nx - tipX)
                    val rays = ((sin(angle * 13f + time * 9f) + 1f) * .5f)
                    val sparks = (1f - distance * (7f + rays * 14f)).coerceIn(0f, 1f)
                    Triple(if (colour == AnimationColour.AUTO) 45f + rays * 20f else baseHue, .35f + rays * .5f, .008f + sparks * (.3f + rays * .7f))
                }
                AnimationEffect.AURORA, AnimationEffect.NORTHERN_LIGHTS -> Triple(baseHue + wave * 80f, .78f, .15f + wave * .72f)
                AnimationEffect.LAVA_LAMP -> Triple(baseHue + wave * 100f, .82f, .22f + wave * .68f)
                AnimationEffect.NEON_RAIN -> Triple(baseHue, .92f, if (((x + tick / 2) % 13) < 2) .9f else .06f)
                AnimationEffect.STARFIELD -> Triple(baseHue, .32f, if (sparkle > .93f) 1f else .015f)
                AnimationEffect.PLASMA -> Triple(baseHue + wave * 220f, .88f, .2f + wave * .78f)
                AnimationEffect.RAINBOW_CHASE -> Triple(nx * 360f + time * 110f, .9f, .25f + wave * .7f)
                AnimationEffect.COLOUR_WAVES -> Triple(baseHue + nx * 180f + time * 70f, .82f, .2f + wave * .76f)
                AnimationEffect.TWILIGHT -> Triple(if (colour == AnimationColour.AUTO) 255f + ny * 55f else baseHue, .62f, .10f + wave * .45f)
                AnimationEffect.PULSE_GRID -> Triple(baseHue, .75f, if ((x + y + tick / 2) % 12 < 3) .82f else .08f)
                // A rotating galaxy uses curved arms around a bright moving core.
                AnimationEffect.GALAXY_SPIRAL -> {
                    val dx = nx - .5f; val dy = ny - .5f
                    val radius = sqrt(dx * dx + dy * dy)
                    val angle = kotlin.math.atan2(dy, dx)
                    val arm = ((sin(angle * 3f + radius * 24f - time * 1.8f) + 1f) * .5f)
                    val core = (1f - radius * 3.2f).coerceIn(0f, 1f)
                    Triple(250f + arm * 55f, .55f + arm * .35f, .01f + core * .95f + arm * (1f - radius).coerceIn(0f, 1f) * .5f)
                }
                // Matrix rain is independently falling green code columns with sparse bright heads.
                AnimationEffect.MATRIX_RAIN -> {
                    val column = (x * 37 + 11) % 29
                    val fall = ((ny * 18f + time * (1.5f + (column % 5) * .22f) + column * .31f) % 1f)
                    val head = (1f - fall * 11f).coerceIn(0f, 1f)
                    val trail = (1f - fall * 2.4f).coerceIn(0f, 1f)
                    Triple(112f + head * 18f, .72f, .008f + trail * .34f + head * .66f)
                }
                // Solar flare radiates hot pulses and thin arcs from a glowing centre.
                AnimationEffect.SOLAR_FLARE -> {
                    val dx = nx - .5f; val dy = ny - .5f
                    val radius = sqrt(dx * dx + dy * dy)
                    val angle = kotlin.math.atan2(dy, dx)
                    val flare = ((sin(angle * 9f - time * 3.2f) + 1f) * .5f) * (1f - radius).coerceIn(0f, 1f)
                    val corona = (1f - abs(radius - (.22f + sin(time * 1.4f) * .045f)) * 11f).coerceIn(0f, 1f)
                    Triple(18f + flare * 38f, .88f, .02f + corona * .75f + flare * .45f)
                }
                // Crystal cave combines slowly shifting facets with cool reflected highlights.
                AnimationEffect.CRYSTAL_CAVE -> {
                    val facet = sin(nx * 19f + ny * 11f + time * .8f) * sin(nx * 7f - ny * 23f - time * .55f)
                    val edge = (1f - abs(facet) * 5f).coerceIn(0f, 1f)
                    val depth = (.18f + ny * .46f + facet * .18f).coerceIn(0f, 1f)
                    Triple(185f + facet * 55f + edge * 30f, .58f + edge * .3f, .02f + depth * .72f + edge * .26f)
                }
                // Laser tunnel converges coloured beams toward a moving vanishing point.
                AnimationEffect.LASER_TUNNEL -> {
                    val vx = .5f + sin(time * .7f) * .16f
                    val vy = .5f + sin(time * .9f) * .11f
                    val dx = nx - vx; val dy = ny - vy
                    val radius = sqrt(dx * dx + dy * dy)
                    val angle = kotlin.math.atan2(dy, dx)
                    val beam = (1f - abs(sin(angle * 12f + time * 2.4f)) * 8f).coerceIn(0f, 1f)
                    val rings = (1f - abs(((radius * 9f - time * 1.8f) % 1f) - .5f) * 10f).coerceIn(0f, 1f)
                    Triple((time * 65f + angle * 28f) % 360f, .9f, .006f + beam * (1f - radius).coerceIn(0f, 1f) * .92f + rings * .25f)
                }
                // Deliberately y-invariant: WLED's zone reducer preserves this as a one-dimensional strip.
                AnimationEffect.WLED_1D_FIREWORKS -> {
                    val phase = (time * .22f) % 1f
                    val launch = .14f + ((tick / 41L) % 4) * .24f
                    val rising = (1f - abs(nx - launch * (phase / .30f).coerceIn(0f, 1f)) * 18f).coerceIn(0f, 1f)
                    val expansion = ((phase - .30f) / .70f).coerceIn(0f, 1f)
                    val radius = .025f + expansion * .42f
                    val left = (1f - abs(nx - (launch - radius)) * 26f).coerceIn(0f, 1f)
                    val right = (1f - abs(nx - (launch + radius)) * 26f).coerceIn(0f, 1f)
                    val core = (1f - abs(nx - launch) * 16f).coerceIn(0f, 1f) * (1f - expansion)
                    val burst = if (phase < .30f) rising else maxOf(left, right, core)
                    Triple(if (colour == AnimationColour.AUTO) 20f + expansion * 190f else baseHue, .86f, .006f + burst * (.42f + expansion * .58f))
                }
            }
            putHsv(pixel, hue, saturation, value * brightness)
            pixel += 3
        }
        return pixels
    }

    private fun paletteHue(colour: AnimationColour, effect: AnimationEffect, time: Float): Float = when {
        colour.hue.isFinite() -> colour.hue
        colour == AnimationColour.MONOCHROME -> 0f
        effect in setOf(AnimationEffect.WATER, AnimationEffect.LAKE, AnimationEffect.OCEAN) -> 205f
        effect in setOf(AnimationEffect.CANDLE, AnimationEffect.FIREPLACE, AnimationEffect.EMBERS) -> 22f
        else -> (time * 47f) % 360f
    }

    private fun putHsv(at: Int, hue: Float, saturation: Float, value: Float) {
        val h = ((hue % 360f) + 360f) % 360f; val v = value.coerceIn(0f, 1f); val c = v * saturation.coerceIn(0f, 1f)
        val q = c * (1f - abs((h / 60f) % 2f - 1f)); val m = v - c
        val (r, g, b) = when ((h / 60f).toInt()) { 0 -> Triple(c,q,0f); 1 -> Triple(q,c,0f); 2 -> Triple(0f,c,q); 3 -> Triple(0f,q,c); 4 -> Triple(q,0f,c); else -> Triple(c,0f,q) }
        pixels[at] = ((r + m) * 255).toInt().coerceIn(0,255).toByte(); pixels[at + 1] = ((g + m) * 255).toInt().coerceIn(0,255).toByte(); pixels[at + 2] = ((b + m) * 255).toInt().coerceIn(0,255).toByte()
    }
}
