package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdateIntegrationSourceTest {
    @Test fun launchTimeConfirmationDownloadsVerifiesAndOpensSystemInstallerOnlyAfterYes() {
        val manifest = sourceFile("AndroidManifest.xml", "src/main", "app/src/main").readText()
        val activity = sourceFile("MainActivity.kt", "src/main/java/org/hyperion/audioreactive", "app/src/main/java/org/hyperion/audioreactive").readText()
        val updater = sourceFile("GitHubReleaseUpdater.kt", "src/main/java/org/hyperion/audioreactive", "app/src/main/java/org/hyperion/audioreactive").readText()
        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(activity.contains("releaseUpdater.checkForUpdate()"))
        assertTrue(activity.contains(".setTitle(\"Доступне оновлення\")"))
        assertTrue(activity.contains(".setNegativeButton(\"Ні\", null)"))
        assertTrue(activity.contains(".setPositiveButton(\"Так\") { _, _ -> releaseUpdater.updateSelectedRelease() }"))
        assertTrue(activity.contains("releaseUpdater.updateSelectedRelease()"))
        assertFalse(activity.contains("Перевірити оновлення"))
        assertFalse(activity.contains("updateButton"))
        assertFalse(activity.contains("downloadSelectedUpdate()"))
        assertFalse(activity.contains("installDownloadedUpdate()"))
        assertTrue(activity.contains("override fun onDestroy()"))
        assertTrue(updater.contains("Metadata only; it never downloads an APK"))
        assertTrue(updater.contains("Called only after the visible launch-time confirmation"))
        assertTrue(updater.contains("if (release == null) publish(\"\", false)"))
        assertFalse(updater.contains("Оновлень немає"))
        assertFalse(updater.contains("Перевіряю оновлення"))
        assertTrue(updater.contains("downloadAndVerify(release)"))
        assertTrue(updater.contains("openVerifiedUpdate(apk, release.tag)"))
        val downloadAndVerify = updater.substringAfter("private fun downloadAndVerify").substringBefore("private fun verifyArchive")
        val openVerifiedUpdate = updater.substringAfter("private fun openVerifiedUpdate").substringBefore("private fun findNewestRelease")
        assertTrue(downloadAndVerify.contains("sha256(target) == expectedDigest"))
        assertTrue(downloadAndVerify.contains("verifyArchive(target, release.tag)"))
        assertTrue(downloadAndVerify.indexOf("sha256(target) == expectedDigest") < downloadAndVerify.indexOf("verifyArchive(target, release.tag)"))
        assertTrue(openVerifiedUpdate.contains("verifyArchive(apk, tag)"))
        assertTrue(openVerifiedUpdate.indexOf("verifyArchive(apk, tag)") < openVerifiedUpdate.indexOf("openPackageInstaller(apk)"))
        assertTrue(updater.contains("canRequestPackageInstalls()"))
        assertTrue(updater.contains("ACTION_MANAGE_UNKNOWN_APP_SOURCES"))
        assertTrue(activity.contains("releaseUpdater.resumePendingInstall()"))
        assertTrue(updater.contains("fun resumePendingInstall()"))
        assertTrue(updater.contains("if (!awaitingInstallPermission || !context.packageManager.canRequestPackageInstalls()) return"))
        assertTrue(updater.contains("Оновлення перевірено й збережено"))
        assertTrue(updater.contains("archive.packageName == BuildConfig.APPLICATION_ID"))
        assertTrue(updater.contains("archive.versionName =="))
        assertTrue(updater.contains("archive.longVersionCode == expected.versionCode.toLong()"))
        assertTrue(updater.contains("apkContentsSigners"))
        assertTrue(updater.contains("hasPinnedReleaseCertificate"))
        assertTrue(updater.contains("BuildConfig.RELEASE_CERT_SHA256"))
        assertFalse(updater.contains("getPackageInfo(BuildConfig.APPLICATION_ID"))
        assertTrue(updater.contains("sha256FromSidecar"))
        assertTrue(updater.contains("releasePages()"))
        assertTrue(updater.contains("closed.compareAndSet(false, true)"))
        assertTrue(updater.contains("selectSystemInstaller"))
        assertTrue(updater.contains("queryIntentActivities"))
        assertTrue(updater.contains("setComponent(component)"))
        assertTrue(updater.contains("resolveActivity(explicit"))
        assertTrue(updater.contains("FLAG_UPDATED_SYSTEM_APP"))
        assertTrue(updater.contains("activeConnection?.disconnect()"))
        assertTrue(updater.contains("File(context.cacheDir, \"updates\").listFiles()?.forEach(File::delete)"))
        assertTrue(updater.contains("while (true) { checkOpen(); val count = input.read(buffer); checkOpen()"))
    }

    @Test fun closeCancelsTransferDeletesBothCacheFormsAndSuppressesLateWork() {
        val updater = sourceFile("GitHubReleaseUpdater.kt", "src/main/java/org/hyperion/audioreactive", "app/src/main/java/org/hyperion/audioreactive").readText()
        val close = updater.substringAfter("override fun close()").substringBefore("private companion object")
        assertTrue(close.contains("closed.compareAndSet(false, true)"))
        assertTrue(close.contains("activeConnection?.disconnect()"))
        assertTrue(close.contains("downloadedApk?.delete()"))
        assertTrue(close.contains("File(context.cacheDir, \"updates\").listFiles()?.forEach(File::delete)"))
        assertTrue(updater.contains("if (!closed.get()) context.mainExecutor.execute { if (!closed.get()) block() }"))
        assertTrue(updater.contains("if (closed.get()) { partial.delete(); target.delete() }"))
    }

    private fun sourceFile(name: String, vararg roots: String): File = roots.asSequence()
        .map { File(it, name) }
        .firstOrNull(File::isFile) ?: error("$name not found")
}
