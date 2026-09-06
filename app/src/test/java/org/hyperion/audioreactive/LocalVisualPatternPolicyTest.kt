package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVisualPatternPolicyTest {
    @Test fun everyDeclaredPatternIsEnumerableAndOneButtonCycleVisitsEachExactlyOnce() {
        val patterns = LocalVisualPatternPolicy.patterns
        assertEquals(LocalVisualPattern.entries.toList(), patterns)
        assertEquals(14, patterns.size)
        assertTrue(patterns.containsAll(listOf(
            LocalVisualPattern.HORIZONTAL_BARS,
            LocalVisualPattern.VERTICAL_BARS,
            LocalVisualPattern.CORNER_COLOURS,
            LocalVisualPattern.COLOUR_WHEEL,
            LocalVisualPattern.CHECKERBOARD,
            LocalVisualPattern.GRAYSCALE_RAMP,
            LocalVisualPattern.MOVING_BARS,
        )))

        var index = patterns.lastIndex
        val visited = linkedSetOf<LocalVisualPattern>()
        repeat(patterns.size) {
            index = LocalVisualPatternPolicy.next(index)
            visited += patterns[index]
        }
        assertEquals(patterns.toSet(), visited)
        assertEquals(0, LocalVisualPatternPolicy.next(patterns.lastIndex))
    }

    @Test fun proceduralPatternsAreLocalBackgroundOnlyWithoutCaptureRouteOrNetworkCalls() {
        val source = sequenceOf(
            File("src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
            File("app/src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
        ).first(File::isFile).readText()
        val visualBlock = source.substringAfter("private fun showLocalVisualPattern")
            .substringBefore("private fun showMqttSettingsDialog")

        assertTrue(source.contains("private class LocalVisualPatternDrawable"))
        assertTrue(source.contains("contentRoot.background = LocalVisualPatternDrawable"))
        assertTrue(visualBlock.contains("RainbowVisualSourcePolicy.start()"))
        assertFalse(visualBlock.contains("MediaProjection"))
        assertFalse(visualBlock.contains("AudioReactiveService"))
        assertFalse(visualBlock.contains("OutputRouter"))
        assertFalse(visualBlock.contains("startActivity("))
        assertFalse(visualBlock.contains(".connect("))
    }

    @Test fun movingBarsAnimatorMutatesItsActivationDrawableInsteadOfAllocatingPerFrame() {
        val source = sequenceOf(
            File("src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
            File("app/src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
        ).first(File::isFile).readText()
        val animatorBlock = source.substringAfter("private val movingBarsAnimator")
            .substringBefore("private lateinit var status")
        val visualBlock = source.substringAfter("private fun showLocalVisualPattern")
            .substringBefore("private fun showMqttSettingsDialog")

        assertFalse(animatorBlock.contains("LocalVisualPatternDrawable("))
        assertTrue(animatorBlock.contains("movingBarsDrawable?.updatePhase(movingPatternPhase)"))
        assertTrue(source.contains("fun updatePhase(value: Float)"))
        assertTrue(source.contains("invalidateSelf()"))
        assertTrue(visualBlock.contains("movingBarsDrawable = LocalVisualPatternDrawable(LocalVisualStyle.MOVING_BARS, movingPatternPhase)"))
        assertTrue(visualBlock.contains("rainbowHandler.removeCallbacks(movingBarsAnimator)"))
    }
}
