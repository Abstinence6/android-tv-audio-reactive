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

    @Test fun recoveryControlIsVisibleOnlyForAQualifiedLocalRouteLoss() {
        assertTrue(source.contains("text = \"Повторно перевірити й увімкнути\""))
        assertTrue(source.contains("RouteRecoveryPolicy.Origin.LOCAL_CAPTURE_BUTTON"))
        assertTrue(source.contains("RouteRecoveryPolicy.Decision.START_NEW_LOCAL_ADMISSION"))
        assertTrue(source.contains("recoveryButton.visibility = if (mayRecover) View.VISIBLE else View.GONE"))
        val recovery = source.substringAfter("recoveryButton = Button(this)").substringBefore("val tabLayoutContainer")
        assertTrue(recovery.contains("setOnClickListener { handleCaptureToggle() }"))
        assertFalse(recovery.contains("requestMediaProjectionConsent"))
        assertFalse(recovery.contains("startCapture("))
    }

    @Test fun primaryScreenUsesCompactIndicatorAndMovesMaintenanceActionsToAdditionalTab() {
        assertTrue(source.contains("captureIndicator.text = CaptureUiPresentation.indicator"))
        assertTrue(source.contains("text = \"Тест екрана:"))
        val primary = source.substringAfter("private fun buildControlTab").substringBefore("private fun buildAdditionalTools")
        assertFalse(primary.contains("Детальний локальний стан"))
        assertFalse(primary.contains("Перевірити оновлення"))
        val additional = source.substringAfter("private fun buildAdditionalTools").substringBefore("private fun addEffectSelector")
        assertTrue(additional.contains("Детальний локальний стан"))
        assertTrue(additional.contains("Перевірити оновлення"))
    }
}
