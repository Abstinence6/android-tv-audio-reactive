package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityCaptureUiSourceTest {
    private val source by lazy {
        sequenceOf(
            File("src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
            File("app/src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
        ).first(File::isFile).readText()
    }

    @Test fun captureControlBecomesTheOnlyRecoveryControlForAQualifiedLocalRouteLoss() {
        assertTrue(source.contains("RouteRecoveryPolicy.Origin.LOCAL_CAPTURE_BUTTON"))
        assertTrue(source.contains("RouteRecoveryPolicy.Decision.START_NEW_LOCAL_ADMISSION"))
        assertTrue(source.contains("mayRecover -> getString(R.string.capture_retry)"))
        assertTrue(source.contains("captureButton = Button(this).apply { id = View.generateViewId(); setOnClickListener { handleCaptureToggle() } }"))
        assertFalse(source.contains("recoveryButton"))
    }

    @Test fun animationToCaptureModeStopsTheNoInputServiceAndHidesAudioOnlyControls() {
        assertTrue(source.contains("AnimationModeTransitionPolicy.requiresRestart"))
        assertTrue(source.contains("if (restartForInputOwnership) AudioReactiveService.stopExisting(this)"))
        assertTrue(source.contains("audioSection.visibility = if (audio) View.VISIBLE else View.GONE"))
        assertTrue(source.contains("sensitivityRow.visibility = if (audio) View.VISIBLE else View.GONE"))
        assertTrue(source.contains("silenceFadeToggle.visibility = if (mixed) View.VISIBLE else View.GONE"))
        assertTrue(source.contains("silenceBrightnessRow.visibility = if (mixed && settings.silenceFadeEnabled) View.VISIBLE else View.GONE"))
        assertTrue(source.contains("animationColourRow.visibility = if (animation) View.VISIBLE else View.GONE"))
        assertTrue(source.contains("testButton.visibility = if (video) View.VISIBLE else View.GONE"))
    }

    @Test fun singleScreenUsesCompactIndicatorAndKeepsOnlyLocalMaintenanceActions() {
        assertTrue(source.contains("captureIndicator.text = CaptureUiPresentation.indicator"))
        assertTrue(source.contains("text = getString(R.string.screen_test"))
        assertTrue(source.contains("buildAdditionalTools(mainPanel)"))
        val additional = source.substringAfter("private fun buildAdditionalTools").substringBefore("private fun addEffectSelector")
        assertTrue(additional.contains("getString(R.string.detailed_local_status)"))
        assertFalse(additional.contains("Перевірити оновлення"))
        assertFalse(source.contains("Home Assistant MQTT: приватний LAN broker"))
    }
}
