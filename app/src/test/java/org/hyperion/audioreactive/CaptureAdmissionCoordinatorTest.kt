package org.hyperion.audioreactive

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAdmissionCoordinatorTest {
    @Test fun secondPressDuringPreflightIsIgnoredAndReplacementUsesNewGeneration() {
        val host = Host()
        val coordinator = CaptureToggleCoordinator(host)
        coordinator.toggle(); coordinator.toggle()
        assertEquals(1, host.preflights)
        coordinator.invalidatePending()
        coordinator.toggle()
        assertEquals(2, host.preflights)
        assertTrue(host.generations[1] > host.generations[0])
    }

    @Test fun staleConsentAfterReplacementCannotStartService() {
        val host = Host(recordAudio = true)
        val coordinator = CaptureToggleCoordinator(host)
        coordinator.toggle(); val stale = host.generations.single()
        coordinator.invalidatePending()
        coordinator.toggle(); val current = host.generations.last()
        host.ready[current]!!.invoke()
        coordinator.onMediaProjectionConsentResult(stale, android.app.Activity.RESULT_OK, Intent())
        assertEquals(0, host.starts)
        coordinator.onMediaProjectionConsentResult(current, android.app.Activity.RESULT_OK, Intent())
        assertEquals(1, host.starts)
    }

    @Test fun stalePermissionAndDestroyedAdmissionCannotStartCapture() {
        val host = Host(recordAudio = false)
        val coordinator = CaptureToggleCoordinator(host)
        coordinator.toggle(); val generation = host.generations.single()
        host.ready[generation]!!.invoke()
        coordinator.invalidatePending()
        coordinator.onRecordAudioPermissionResult(generation, true)
        assertEquals(0, host.projectionRequests)
        assertEquals(0, host.starts)
    }

    @Test fun secondToggleAfterConsentBeforeServiceOwnershipDoesNotCreateAnotherBindingOrStart() {
        val host = Host(recordAudio = true)
        val coordinator = CaptureToggleCoordinator(host)
        coordinator.toggle(); val generation = host.generations.single()
        host.ready[generation]!!.invoke()
        coordinator.onMediaProjectionConsentResult(generation, android.app.Activity.RESULT_OK, Intent())
        coordinator.toggle() // Service has not yet made AudioReactiveService.exists() visible.
        assertEquals(1, host.preflights)
        assertEquals(1, host.bindings)
        assertEquals(1, host.starts)
        coordinator.onCaptureServiceOwnershipConfirmed(generation)
        coordinator.toggle()
        assertEquals(2, host.preflights)
    }

    private class Host(private val recordAudio: Boolean = true) : CaptureToggleCoordinator.Host {
        var preflights = 0; var bindings = 0; var starts = 0; var projectionRequests = 0
        val generations = mutableListOf<Long>(); val ready = mutableMapOf<Long, () -> Unit>()
        override fun serviceExists() = false
        override fun hasRecordAudioPermission() = recordAudio
        override fun stopExistingService() = Unit
        override fun requestRecordAudioPermission(generation: Long) = Unit
        override fun requestMediaProjectionConsent(generation: Long) { projectionRequests++ }
        override fun prepareOutputForCapture(generation: Long, onReady: () -> Unit, onDenied: () -> Unit) { preflights++; bindings++; generations += generation; ready[generation] = onReady }
        override fun startCapture(generation: Long, resultCode: Int, data: Intent) { starts++ }
        override fun onStoppedExistingService() = Unit
        override fun onCaptureStartDenied() = Unit
        override fun onCaptureStartApproved() = Unit
    }
}
