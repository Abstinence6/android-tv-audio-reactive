package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdateIntegrationSourceTest {
    @Test fun updaterRequiresThreeVisibleStagesAndVerifiesIdentityBeforeInstaller() {
        val manifest = sourceFile("AndroidManifest.xml", "src/main", "app/src/main").readText()
        val activity = sourceFile("MainActivity.kt", "src/main/java/org/hyperion/audioreactive", "app/src/main/java/org/hyperion/audioreactive").readText()
        val updater = sourceFile("GitHubReleaseUpdater.kt", "src/main/java/org/hyperion/audioreactive", "app/src/main/java/org/hyperion/audioreactive").readText()
        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(activity.contains("releaseUpdater.checkForUpdate()"))
        assertTrue(activity.contains("releaseUpdater.downloadSelectedUpdate()"))
        assertTrue(activity.contains("releaseUpdater.installDownloadedUpdate()"))
        assertTrue(activity.contains("override fun onDestroy()"))
        assertTrue(updater.contains("Metadata only; it never downloads an APK"))
        assertTrue(updater.contains("Called only from the visible D-pad download control"))
        assertTrue(updater.contains("Called only from the visible D-pad install control"))
        assertTrue(updater.contains("archive.packageName == BuildConfig.APPLICATION_ID"))
        assertTrue(updater.contains("archive.versionName =="))
        assertTrue(updater.contains("archive.longVersionCode == expected.versionCode.toLong()"))
        assertTrue(updater.contains("apkContentsSigners"))
        assertTrue(updater.contains("sha256FromSidecar"))
        assertTrue(updater.contains("releasePages()"))
        assertTrue(updater.contains("closed.set(true)"))
    }

    private fun sourceFile(name: String, vararg roots: String): File = roots.asSequence()
        .map { File(it, name) }
        .firstOrNull(File::isFile) ?: error("$name not found")
}
