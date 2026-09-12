package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRecoveryMainActivitySourceTest {
    private val source by lazy {
        sequenceOf(
            File("src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
            File("app/src/main/java/org/hyperion/audioreactive/MainActivity.kt"),
        ).first(File::isFile).readText()
    }

    @Test fun routeLossRetryIsGatedToTheLocalCaptureButton() {
        assertTrue(source.contains("RouteRecoveryPolicy.Origin.LOCAL_CAPTURE_BUTTON"))
        assertTrue(source.contains("RouteRecoveryPolicy.Origin.REMOTE_ACTION"))
        assertTrue(source.contains("RouteRecoveryPolicy.Decision.REQUIRE_LOCAL_BUTTON"))
        assertTrue(source.contains("getString(R.string.route_lost_local)"))
    }

    @Test fun remoteRouteLossPathDoesNotInvokeTheCaptureCoordinator() {
        val remote = source.substringAfter("private fun handleRemoteAction").substringBefore("override fun onStart")
        val routeLostBranch = remote.substringAfter("RouteRecoveryPolicy.Decision.REQUIRE_LOCAL_BUTTON")
            .substringBefore("RouteRecoveryPolicy.Decision.START_NEW_LOCAL_ADMISSION")
        assertFalse(routeLostBranch.contains("captureToggleCoordinator.toggle()"))
        assertFalse(routeLostBranch.contains("requestMediaProjectionConsent"))
        assertFalse(routeLostBranch.contains("startCapture("))
    }
}
