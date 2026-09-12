package org.hyperion.audioreactive

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

    @Test fun candleFireplaceAndFireworksHaveDistinctCompositionsAtTheSamePaletteAndTick() {
        val output = listOf(AnimationEffect.CANDLE, AnimationEffect.FIREPLACE, AnimationEffect.FIREWORKS)
            .map { render(it, tick = 37L) }
        assertEquals(3, output.map { it.toList() }.distinct().size)
        output.forEach { pixels -> assertTrue(pixels.any { it != 0.toByte() }) }
    }

    @Test fun everyReworkedEffectAdvancesBetweenFrames() {
        listOf(
            AnimationEffect.WATER, AnimationEffect.LAKE, AnimationEffect.OCEAN,
            AnimationEffect.CANDLE, AnimationEffect.FIREPLACE, AnimationEffect.FIREWORKS,
        ).forEach { effect ->
            assertFalse("$effect must animate", render(effect, tick = 37L).contentEquals(render(effect, tick = 81L)))
        }
    }

    private fun render(effect: AnimationEffect, tick: Long): ByteArray =
        AnimationFrameRenderer(frame).render(effect, AnimationColour.AUTO, 1f, tick, parameters).copyOf()
}
