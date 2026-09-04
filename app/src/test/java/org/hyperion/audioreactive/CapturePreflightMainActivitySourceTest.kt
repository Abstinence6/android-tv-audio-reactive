package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard the visible capture flow: retries validate routes first and cannot request consent on all-fail. */
class CapturePreflightMainActivitySourceTest {
    private val source by lazy { sequenceOf(File("src/main/java/org/hyperion/audioreactive/MainActivity.kt"), File("app/src/main/java/org/hyperion/audioreactive/MainActivity.kt")).first(File::isFile).readText() }

    @Test fun preflightUsesBoundedReadOnlyBindersDiscardsOldBindingsAndLocksAdmission() {
        assertTrue(source.contains("CapturePreflightRetry.bind(cancelled, progress) { WledCapturePreflight.bind(settings) }"))
        assertTrue(source.contains("CapturePreflightRetry.bind(cancelled, progress) { HyperionCapturePreflight.bind(settings) }"))
        assertTrue(source.contains("private fun invalidatePendingCaptureAdmission()"))
        assertTrue(source.contains("WledRouteBindings.discard(pendingWled); HyperionRouteBindings.discard(pendingHyperion)"))
        assertTrue(source.contains("captureAdmissionLocked = true"))
        assertTrue(source.contains("val locked = active || captureAdmissionLocked"))
        assertTrue(source.contains("спроба ${'$'}attempt/${'$'}{CapturePreflightRetry.MAX_ATTEMPTS}"))
    }

    @Test fun callbacksAreBoundToAdmissionGenerationAndStaleResultsCannotStartService() {
        assertTrue(source.contains("pendingAdmissionGeneration != admissionGeneration"))
        assertTrue(source.contains("pendingProjectionGeneration?.let { generation ->"))
        assertTrue(source.contains("captureToggleCoordinator.onMediaProjectionConsentResult(generation, result, data)"))
        assertTrue(source.contains("invalidatePendingCaptureAdmission(); captureToggleCoordinator.invalidatePending()"))
        assertTrue(source.contains("captureToggleCoordinator.onCaptureServiceOwnershipConfirmed(generation)"))
        assertTrue(source.contains(".putExtra(AudioReactiveService.EXTRA_ADMISSION_GENERATION, admissionGeneration)"))
    }

    @Test fun mediaProjectionReadyCallbackIsOnlyReachableWithABinding() {
        val preflight = source.substringAfter("override fun prepareOutputForCapture").substringBefore("override fun startCapture")
        assertTrue(preflight.contains("if (wled == null && hyperion == null)"))
        assertTrue(preflight.contains("onDenied()"))
        assertTrue(preflight.contains("} else {\n                    pendingWled = wled"))
        assertTrue(preflight.contains("onReady()"))
        assertFalse(preflight.substringBefore("if (wled == null && hyperion == null)").contains("onReady()"))
    }
}
