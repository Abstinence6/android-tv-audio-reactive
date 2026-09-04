package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandActivitiesSourceTest {
    private val source by lazy { sourceFile().readText() }

    @Test fun eachConcreteRemoteSurfaceHardcodesItsMatchingMainActivityAction() {
        assertContains("class RemoteOnActivity : RemoteCommandActivity() {\n    override val commandAction = MainActivity.ACTION_ON")
        assertContains("class RemoteOffActivity : RemoteCommandActivity() {\n    override val commandAction = MainActivity.ACTION_OFF")
        assertContains("class RemoteToggleActivity : RemoteCommandActivity() {\n    override val commandAction = MainActivity.ACTION_TOGGLE")
    }

    @Test fun remoteSurfacesForwardOnlyToMainActivityWithTheFixedActionThenFinish() {
        assertContains("Intent(this, MainActivity::class.java)")
        assertContains(".setAction(commandAction)")
        assertContains("Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP")
        assertContains("finish()")
    }

    @Test fun remoteSurfacesDoNotOwnCapturePermissionsOrTransport() {
        listOf("startService", "MediaProjection", "AudioRecord", "Socket", "requestPermissions").forEach { forbidden ->
            assertFalse("Remote activity source must not contain $forbidden", source.contains(forbidden))
        }
    }

    private fun assertContains(expected: String) = assertTrue("Missing: $expected", source.contains(expected))

    private fun sourceFile(): File = sequenceOf(
        File("src/main/java/org/hyperion/audioreactive/RemoteCommandActivities.kt"),
        File("app/src/main/java/org/hyperion/audioreactive/RemoteCommandActivities.kt"),
    ).firstOrNull(File::isFile) ?: error("RemoteCommandActivities.kt not found")
}
