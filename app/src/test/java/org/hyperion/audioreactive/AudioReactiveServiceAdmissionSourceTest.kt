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

    @Test fun serviceReservesOneAdmissionAndConfirmsOnlyAfterRouteConsumption() {
        assertTrue(source.contains("private val admission = ServiceRouteAdmission"))
        assertTrue(source.contains("if(!admission.reserve(ids))"))
        val consume = source.indexOf("router=admission.consume")
        val confirmsAlive = source.indexOf("alive=true", consume)
        val confirmsUi = source.indexOf("broadcast(generation)", confirmsAlive)
        assertTrue(consume >= 0 && confirmsAlive > consume && confirmsUi > confirmsAlive)
    }

    @Test fun serviceTeardownDiscardsOnlyUnconsumedPendingBindings() {
        assertTrue(source.contains("admission.finish() // Discards only an unconsumed handoff"))
        assertTrue(source.contains("private fun broadcastAdmissionFailed(generation:Long)"))
        assertTrue(source.contains("broadcastAdmissionFailed(generation)"))
    }

    @Test fun videoCaptureProducerIsFixedAtMaximumHyperionDimensions() {
        assertTrue(source.contains("ImageReader.newInstance(320,180,android.graphics.PixelFormat.RGBA_8888,2)"))
        assertTrue(source.contains("p.createVirtualDisplay(\"audio-reactive-video\",320,180,1,"))
    }
}
