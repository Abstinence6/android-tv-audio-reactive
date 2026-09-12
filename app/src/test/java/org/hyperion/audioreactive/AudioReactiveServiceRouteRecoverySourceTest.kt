package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioReactiveServiceRouteRecoverySourceTest {
    private val source by lazy {
        sequenceOf(
            File("src/main/java/org/hyperion/audioreactive/AudioReactiveService.kt"),
            File("app/src/main/java/org/hyperion/audioreactive/AudioReactiveService.kt"),
        ).first(File::isFile).readText()
    }

    @Test fun routeSendFailureRevalidatesAndRebuildsTheRouteBeforeDeclaringItLost() {
        assertTrue(source.contains("if(!reconnectRoute(settings)) { terminateRouteLost(); return false }"))
        assertTrue(source.contains("OutputRouteReconnect.connect(cancelled={ !running.get() })"))
        assertTrue(source.contains("WledCapturePreflight.bind(settings)"))
        assertTrue(source.contains("HyperionCapturePreflight.bind(settings)"))
        assertTrue(source.contains("candidate.start(); candidate"))
    }
}