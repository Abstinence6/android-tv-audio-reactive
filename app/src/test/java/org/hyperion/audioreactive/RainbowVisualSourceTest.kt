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
        assertTrue(source.contains("TestFrameActionPolicy.ACTIVE_CAPTURE_REASON"))
        assertFalse(source.contains("RainbowVisualActivity"))
        assertFalse(source.contains("startActivity(Intent(this, RainbowVisualActivity::class.java))"))
    }
}