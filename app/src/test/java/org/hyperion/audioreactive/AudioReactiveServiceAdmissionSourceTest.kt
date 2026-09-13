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
        assertTrue(source.contains("OutputDiagnosticAdmission.reserveCapture()"))
        assertTrue(source.contains("else if(admission.reserve(ids)) true"))
        assertTrue(source.contains("if(!lifecycle.whileStarting { channel(); startForeground"))
    }

    @Test fun invalidProjectionAndRouteAdmissionsUseOneTerminalTeardownPath() {
        assertTrue(source.contains("if(!animation && (intent?.getIntExtra(EXTRA_RESULT_CODE,0)!=Activity.RESULT_OK||data==null)){rejectInvalidStart(generation);return START_NOT_STICKY}"))
        assertTrue(source.contains("if(animation != frozen.isNoInputAnimation()){rejectInvalidStart(generation);return START_NOT_STICKY}"))
        assertTrue(source.contains("if(!valid){rejectInvalidStart(generation);return START_NOT_STICKY}"))
        assertTrue(source.contains("private fun rejectInvalidStart(generation:Long){invalidAdmissionGeneration=generation;lifecycle.stop()}"))
        assertTrue(source.contains("invalidAdmissionGeneration?.let{generation->invalidAdmissionGeneration=null;broadcastAdmissionFailed(generation)}?:broadcast()"))
    }

    @Test fun serviceChecksCancellationAroundEachCaptureAcquire() {
        assertTrue(source.contains("lifecycle.acquire(\n     acquire = { (getSystemService"))
        assertTrue(source.contains("lifecycle.acquire(acquire={createAudio(p,s)}"))
        assertTrue(source.contains("lifecycle.acquire(acquire={ImageReader.newInstance"))
        assertTrue(source.contains("lifecycle.acquire(acquire={p.createVirtualDisplay"))
        assertTrue(source.contains("lifecycle.acquire(acquire={ admission.consume"))
    }

    @Test fun serviceStopsRouterBeforeAdmissionAndRetainsPublicSafeFailureDiagnostic() {
        val routerStop = source.indexOf("attempt(\"router\"){router?.stop()")
        val admissionFinish = source.indexOf("attempt(\"admission\"){admission.finish();OutputDiagnosticAdmission.releaseCapture()}")
        assertTrue(routerStop >= 0 && admissionFinish > routerStop)
        assertTrue(source.contains("STARTUP_FAILURE_DIAGNOSTIC"))
        assertTrue(source.contains("CLEANUP_FAILURE_DIAGNOSTIC"))
        assertTrue(source.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
        assertTrue(source.contains("attempt(\"self\"){stopSelf()}"))
    }

    @Test fun serviceOwnsOnlyTheSourcesSelectedByTheLiveMode() {
        assertTrue(source.contains("if(s.requiresAudio()&&!lifecycle.acquire(acquire={createAudio(p,s)}"))
        assertTrue(source.contains("if(s.requiresVideo()&&!createVideoWhileStarting(p)) return"))
        assertTrue(source.contains("if(s.requiresAudio()&&recorder==null) recorder=createAudio(p,s)"))
        assertTrue(source.contains("if(!s.requiresAudio()&&recorder!=null) releaseAudio()"))
        assertTrue(source.contains("if(s.requiresVideo()&&reader==null&&!createVideo(p)) return false"))
        assertTrue(source.contains("if(!s.requiresVideo()&&reader!=null) releaseVideo()"))
    }
}
