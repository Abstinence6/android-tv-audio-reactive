package org.hyperion.audioreactive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationFrameRendererTest {
    private val frame = SourceFrameSpec(24, 14, 20)
    private val parameters = EffectParameters()

    @Test fun waterLakeAndOceanHaveDistinctSurfaceMotionAtTheSamePaletteAndTick() {
        val output = listOf(AnimationEffect.WATER, AnimationEffect.LAKE, AnimationEffect.OCEAN)
            .map { render(it, tick = 37L) }
        assertEquals(3, output.map { it.toList() }.distinct().size)
        output.forEach { pixels -> assertTrue(pixels.any { it != 0.toByte() }) }
    }

    @Test fun everyReworkedEffectHasADistinctFrameAtTheSamePaletteAndTick() {
        val effects = listOf(
            AnimationEffect.WATER, AnimationEffect.LAKE, AnimationEffect.OCEAN,
            AnimationEffect.CANDLE, AnimationEffect.FIREPLACE, AnimationEffect.FIREWORKS,
            AnimationEffect.FIREWORK_BURSTS, AnimationEffect.SPARKLER,
            AnimationEffect.GALAXY_SPIRAL, AnimationEffect.MATRIX_RAIN, AnimationEffect.SOLAR_FLARE,
            AnimationEffect.CRYSTAL_CAVE, AnimationEffect.LASER_TUNNEL,
            AnimationEffect.WLED_1D_FIREWORKS,
        )
        val output = effects.map { render(it, tick = 37L) }
        assertEquals(effects.size, output.map { it.toList() }.distinct().size)
        output.forEach { pixels -> assertTrue(pixels.any { it != 0.toByte() }) }
    }

    @Test fun everyReworkedEffectAdvancesBetweenFrames() {
        listOf(
            AnimationEffect.WATER, AnimationEffect.LAKE, AnimationEffect.OCEAN,
            AnimationEffect.CANDLE, AnimationEffect.FIREPLACE, AnimationEffect.FIREWORKS,
            AnimationEffect.FIREWORK_BURSTS, AnimationEffect.SPARKLER,
            AnimationEffect.GALAXY_SPIRAL, AnimationEffect.MATRIX_RAIN, AnimationEffect.SOLAR_FLARE,
            AnimationEffect.CRYSTAL_CAVE, AnimationEffect.LASER_TUNNEL,
            AnimationEffect.WLED_1D_FIREWORKS,
        ).forEach { effect ->
            assertFalse("$effect must animate", render(effect, tick = 37L).contentEquals(render(effect, tick = 81L)))
        }
    }

    @Test fun wledOneDimensionalFireworksRemainRowInvariantAfterZoneReduction() {
        val full = render(AnimationEffect.WLED_1D_FIREWORKS, tick = 37L)
        val firstRow = full.copyOfRange(0, frame.width * 3)
        for (row in 1 until frame.height) {
            assertArrayEquals(firstRow, full.copyOfRange(row * frame.width * 3, (row + 1) * frame.width * 3))
        }
        val reduced = WledSourceFrame(frame, 16).write(full).copyOf()
        val later = WledSourceFrame(frame, 16).write(render(AnimationEffect.WLED_1D_FIREWORKS, tick = 81L)).copyOf()
        assertEquals(16 * 3, reduced.size)
        assertTrue(reduced.any { it != 0.toByte() })
        assertFalse(reduced.contentEquals(later))
    }

    private fun render(effect: AnimationEffect, tick: Long): ByteArray =
        AnimationFrameRenderer(frame).render(effect, AnimationColour.AUTO, 1f, tick, parameters).copyOf()
}
