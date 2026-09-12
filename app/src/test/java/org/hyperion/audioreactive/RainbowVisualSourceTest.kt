package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainbowVisualSourceTest {
    @Test fun rainbowIsLocalOnlyAndCannotStartCaptureOutputOrNetwork() {
        RainbowVisualSourcePolicy.stop()
        assertFalse(RainbowVisualSourcePolicy.running)
        RainbowVisualSourcePolicy.start()
        assertTrue(RainbowVisualSourcePolicy.running)
        assertFalse(RainbowVisualSourcePolicy.mayStartCapture())
        assertFalse(RainbowVisualSourcePolicy.mayUseOutputRoute())
        assertFalse(RainbowVisualSourcePolicy.mayUseNetwork())
        RainbowVisualSourcePolicy.stop()
    }

    @Test fun mainActivityUsesItsOwnBackgroundWithoutLaunchingAnotherActivity() {
        val source = sequenceOf(
            File("src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
            File("app/src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
        ).first(File::isFile).readText()
        assertTrue(source.contains("contentRoot.background = GradientDrawable"))
        assertTrue(source.contains("RainbowVisualSourcePolicy.start()"))
        assertTrue(source.contains("testButton.isEnabled = !captureAdmissionLocked"))
        assertTrue(source.contains("setOnClickListener { cycleLocalVisualPattern() }"))
        assertTrue(source.contains("getString(R.string.local_pattern_status, label)"))
        assertFalse(source.contains("private fun runSelectedOutputTest()"))
        assertFalse(source.contains("RainbowVisualActivity"))
        assertFalse(source.contains("startActivity(Intent(this, RainbowVisualActivity::class.java))"))
    }

    @Test fun mainActivityDestroyRemovesBothVisualAnimationCallbacks() {
        val source = sequenceOf(
            File("src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
            File("app/src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
        ).first(File::isFile).readText()
        val onDestroyStart = source.indexOf("override fun onDestroy() {")
        val onDestroyEnd = source.indexOf("\n    private fun handleCaptureToggle()", onDestroyStart)
        assertTrue(onDestroyStart >= 0)
        assertTrue(onDestroyEnd > onDestroyStart)
        val onDestroy = source.substring(onDestroyStart, onDestroyEnd)

        assertTrue(onDestroy.contains("rainbowHandler.removeCallbacks(rainbowAnimator)"))
        assertTrue(onDestroy.contains("rainbowHandler.removeCallbacks(movingBarsAnimator)"))
    }
}