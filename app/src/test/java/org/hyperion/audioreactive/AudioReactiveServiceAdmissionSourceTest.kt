package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioReactiveServiceAdmissionSourceTest {
    private val source by lazy {
        sequenceOf(
            File("src/main/java/org/hyperion/audioreactive/AudioReactiveService.kt"),
            File("app/src/main/java/org/hyperion/audioreactive/AudioReactiveService.kt"),
        ).first(File::isFile).readText()
    }

    @Test fun serviceGatesAdmissionAndForegroundStartupAgainstTeardown() {
        assertTrue(source.contains("private val lifecycle = CaptureServiceLifecycle(::performTeardown)"))
        assertTrue(source.contains("if(!lifecycle.beginStart { admission.reserve(ids) }) { admission.discardLifecycleRejectedStart(ids); return START_NOT_STICKY }"))
        assertTrue(source.contains("if(!lifecycle.whileStarting { channel(); startForeground"))
    }

    @Test fun invalidProjectionAndRouteAdmissionsUseOneTerminalTeardownPath() {
        assertTrue(source.contains("data==null){rejectInvalidStart(generation);return START_NOT_STICKY}"))
        assertTrue(source.contains("if(!valid){rejectInvalidStart(generation);return START_NOT_STICKY}"))
        assertTrue(source.contains("private fun rejectInvalidStart(generation:Long){invalidAdmissionGeneration=generation;lifecycle.stop()}"))
        assertTrue(source.contains("invalidAdmissionGeneration?.let{generation->invalidAdmissionGeneration=null;broadcastAdmissionFailed(generation)}?:broadcast()"))
    }

    @Test fun serviceChecksCancellationAroundEachCaptureAcquire() {
        assertTrue(source.contains("lifecycle.acquire(\n     acquire = { (getSystemService"))
        assertTrue(source.contains("lifecycle.acquire(acquire={createAudio(p)}"))
        assertTrue(source.contains("lifecycle.acquire(acquire={ImageReader.newInstance"))
        assertTrue(source.contains("lifecycle.acquire(acquire={p.createVirtualDisplay"))
        assertTrue(source.contains("lifecycle.acquire(acquire={ admission.consume"))
    }

    @Test fun serviceStopsRouterBeforeAdmissionAndRetainsPublicSafeFailureDiagnostic() {
        val routerStop = source.indexOf("attempt(\"router\"){router?.stop()")
        val admissionFinish = source.indexOf("attempt(\"admission\"){admission.finish()}")
        assertTrue(routerStop >= 0 && admissionFinish > routerStop)
        assertTrue(source.contains("STARTUP_FAILURE_DIAGNOSTIC"))
        assertTrue(source.contains("CLEANUP_FAILURE_DIAGNOSTIC"))
        assertTrue(source.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
        assertTrue(source.contains("attempt(\"self\"){stopSelf()}"))
    }
}
