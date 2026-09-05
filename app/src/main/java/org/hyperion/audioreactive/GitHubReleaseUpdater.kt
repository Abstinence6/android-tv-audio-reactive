package org.hyperion.audioreactive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Pure validation rules for the fixed, public GitHub release channel. */
object ReleaseUpdatePolicy {
    const val OWNER = "Abstinence6"
    const val REPOSITORY = "android-tv-audio-reactive"
    const val RELEASES_URL = "https://api.github.com/repos/$OWNER/$REPOSITORY/releases?per_page=100"
    private val tagPattern = Regex("^v(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})$")

    data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        val versionCode: Int get() = major * 1_000_000 + minor * 1_000 + patch
        override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    }

    fun parseTag(tag: String?): Version? = tag?.let { tagPattern.matchEntire(it) }?.destructured?.let { (major, minor, patch) ->
        Version(major.toInt(), minor.toInt(), patch.toInt())
    }
    fun isNewer(tag: String?, currentVersionName: String): Boolean = parseTag(tag)?.let { remote ->
        parseTag("v$currentVersionName")?.let { remote > it }
    } ?: false
    fun <T> highestNewer(candidates: Iterable<T>, currentVersionName: String, tagOf: (T) -> String?): T? {
        val current = parseTag("v$currentVersionName") ?: return null
        return candidates.mapNotNull { candidate ->
            parseTag(tagOf(candidate))?.takeIf { it > current }?.let { candidate to it }
        }.maxByOrNull { it.second }?.first
    }
    fun expectedAssetName(tag: String?): String? = parseTag(tag)?.let { "android-tv-audio-reactive-v${it.major}.${it.minor}.${it.patch}-release.apk" }
    fun expectedChecksumName(tag: String?): String? = expectedAssetName(tag)?.plus(".sha256")
    fun expectedAssetUrl(tag: String?): String? = expectedAssetName(tag)?.let { "https://github.com/$OWNER/$REPOSITORY/releases/download/$tag/$it" }
    fun expectedChecksumUrl(tag: String?): String? = expectedChecksumName(tag)?.let { "https://github.com/$OWNER/$REPOSITORY/releases/download/$tag/$it" }
    fun isExpectedAsset(name: String?, tag: String?): Boolean = name == expectedAssetName(tag)
    fun isExpectedChecksum(name: String?, tag: String?): Boolean = name == expectedChecksumName(tag)
    fun isTrustedMetadataUrl(value: String?): Boolean = value == RELEASES_URL || runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.host == "api.github.com" &&
            uri.path == "/repos/$OWNER/$REPOSITORY/releases" &&
            uri.query == "per_page=100&page=${uri.query?.substringAfter("per_page=100&page=")?.toIntOrNull()}"
    }.getOrDefault(false)
    fun isExpectedDownloadUrl(value: String?, tag: String?, checksum: Boolean): Boolean =
        value == if (checksum) expectedChecksumUrl(tag) else expectedAssetUrl(tag)
    /** Every redirect must remain HTTPS on an explicitly named GitHub delivery host. */
    fun isTrustedRedirectUrl(value: String?): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.host in setOf(
            "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"
        )
    }.getOrDefault(false)
    fun sha256FromSidecar(text: String, assetName: String): String? {
        val match = Regex("^([0-9a-fA-F]{64})  ?${Regex.escape(assetName)}\\s*$").matchEntire(text.trim()) ?: return null
        return match.groupValues[1].lowercase()
    }
}

class GitHubReleaseUpdater(
    private val context: Context,
    private val onStatus: (String, Boolean) -> Unit,
    private val onUpdateAvailable: () -> Unit,
    private val onReadyToInstall: () -> Unit,
) : AutoCloseable {
    private data class Release(val tag: String, val apkUrl: String, val checksumUrl: String)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    @Volatile private var selectedRelease: Release? = null
    @Volatile private var downloadedApk: File? = null

    /** Metadata only; it never downloads an APK or opens the installer. */
    fun checkForUpdate() {
        selectedRelease = null
        downloadedApk?.delete(); downloadedApk = null
        publish("Перевіряю оновлення…", true)
        executor.execute {
            val result = runCatching { findNewestRelease() }
            deliver {
                result.fold(
                    { release ->
                        selectedRelease = release
                        if (release == null) publish("Оновлень немає.", false)
                        else { publish("Оновлення доступне. Виберіть «Завантажити оновлення».", false); onUpdateAvailable() }
                    },
                    { publish("Не вдалося перевірити оновлення.", false) },
                )
            }
        }
    }

    /** Called only from the visible D-pad download control. */
    fun downloadSelectedUpdate() {
        val release = selectedRelease ?: run { publish("Спочатку перевірте оновлення.", false); return }
        downloadedApk?.delete(); downloadedApk = null
        publish("Завантажую оновлення…", true)
        executor.execute {
            val result = runCatching { downloadAndVerify(release) }
            deliver {
                result.fold(
                    { apk -> downloadedApk = apk; publish("Оновлення завантажено. Виберіть «Встановити оновлення».", false); onReadyToInstall() },
                    { publish("Не вдалося завантажити або перевірити оновлення.", false) },
                )
            }
        }
    }

    /** Called only from the visible D-pad install control; never from a network callback. */
    fun installDownloadedUpdate() {
        val apk = downloadedApk ?: run { publish("Спочатку завантажте оновлення.", false); return }
        if (!verifyArchive(apk, requireNotNull(selectedRelease).tag)) { publish("Завантажене оновлення не пройшло перевірку.", false); apk.delete(); downloadedApk = null; return }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            publish("Дозвольте встановлення з цього застосунку, потім виберіть «Встановити оновлення» ще раз.", false)
            return
        }
        runCatching { openPackageInstaller(apk) }.onFailure { publish("Не вдалося відкрити системне підтвердження встановлення.", false) }
    }

    private fun findNewestRelease(): Release? {
        val candidates = releasePages().asSequence().flatMap { releases ->
            (0 until releases.length()).asSequence().mapNotNull { releases.optJSONObject(it) }
        }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
            .mapNotNull { releaseFromJson(it) }
            .toList()
        return ReleaseUpdatePolicy.highestNewer(candidates, BuildConfig.VERSION_NAME) { it.tag }
    }

    private fun releaseFromJson(release: JSONObject): Release? {
        val tag = release.optString("tag_name")
        if (ReleaseUpdatePolicy.parseTag(tag) == null) return null
        val assets = release.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).asSequence().map { assets.optJSONObject(it) }
            .firstOrNull { ReleaseUpdatePolicy.isExpectedAsset(it?.optString("name"), tag) } ?: return null
        val apkUrl = apk.optString("browser_download_url")
        if (!ReleaseUpdatePolicy.isExpectedDownloadUrl(apkUrl, tag, checksum = false)) return null
        val checksumUrl = (0 until assets.length()).asSequence().map { assets.optJSONObject(it) }
            .firstOrNull { ReleaseUpdatePolicy.isExpectedChecksum(it?.optString("name"), tag) }
            ?.optString("browser_download_url")?.takeIf { ReleaseUpdatePolicy.isExpectedDownloadUrl(it, tag, checksum = true) } ?: return null
        return Release(tag, apkUrl, checksumUrl)
    }

    private fun downloadAndVerify(release: Release): File {
        val assetName = requireNotNull(ReleaseUpdatePolicy.expectedAssetName(release.tag))
        val expectedDigest = ReleaseUpdatePolicy.sha256FromSidecar(readText(release.checksumUrl, 4 * 1024, metadata = false), assetName)
            ?: error("Invalid checksum sidecar")
        val target = downloadApk(release.apkUrl, assetName)
        try {
            check(sha256(target) == expectedDigest) { "APK checksum mismatch" }
            check(verifyArchive(target, release.tag)) { "APK identity verification failed" }
            return target
        } catch (failure: Throwable) { target.delete(); throw failure }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(apk: File, tag: String): Boolean = runCatching {
        val expected = requireNotNull(ReleaseUpdatePolicy.parseTag(tag))
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
        check(archive.packageName == BuildConfig.APPLICATION_ID)
        check(archive.versionName == "${expected.major}.${expected.minor}.${expected.patch}")
        check(archive.longVersionCode == expected.versionCode.toLong())
        val installed = context.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, flags)
        val archiveSigning = requireNotNull(archive.signingInfo)
        val installedSigning = requireNotNull(installed.signingInfo)
        check(signerDigests(archiveSigning.apkContentsSigners) == signerDigests(installedSigning.apkContentsSigners))
        true
    }.getOrDefault(false)

    private fun signerDigests(signatures: Array<Signature>): List<String> = signatures.map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }.sorted()

    private fun readText(url: String, limit: Int, metadata: Boolean): String {
        if (metadata) check(ReleaseUpdatePolicy.isTrustedMetadataUrl(url)) else check(ReleaseUpdatePolicy.isExpectedDownloadUrl(url, selectedRelease?.tag, checksum = true))
        return withConnection(url, metadata) { connection ->
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            readLimited(connection, limit)
        }
    }

    /** Follows only GitHub's numbered release pages, then evaluates every stable release returned. */
    private fun releasePages(): List<JSONArray> {
        val pages = mutableListOf<JSONArray>()
        repeat(MAX_RELEASE_PAGES) { index ->
            // GitHub's Link header can use an internal /repositories/{id} URL. Build and
            // validate the public pinned endpoint ourselves instead of trusting that alias.
            val pageUrl = if (index == 0) ReleaseUpdatePolicy.RELEASES_URL else "${ReleaseUpdatePolicy.RELEASES_URL}&page=${index + 1}"
            check(ReleaseUpdatePolicy.isTrustedMetadataUrl(pageUrl))
            withConnection(pageUrl, metadata = true) { connection ->
                check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                val releases = JSONArray(readLimited(connection, 256 * 1024))
                pages += releases
                if (releases.length() < RELEASES_PER_PAGE) return pages
            }
        }
        error("Too many release pages")
    }

    private fun downloadApk(url: String, assetName: String): File {
        check(ReleaseUpdatePolicy.isExpectedDownloadUrl(url, selectedRelease?.tag, checksum = false))
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(updateDir, assetName); val partial = File(updateDir, "$assetName.part").apply { delete() }
        withConnection(url, metadata = false) { connection ->
            check(connection.responseCode in 200..299); check(connection.contentLengthLong in 1..MAX_APK_BYTES)
            BufferedInputStream(connection.inputStream).use { input -> FileOutputStream(partial).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var total = 0L
                while (true) { val count = input.read(buffer); if (count < 0) break; total += count; check(total <= MAX_APK_BYTES); output.write(buffer, 0, count) }
            } }
        }
        check(partial.length() > 3 && partial.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }) { "Downloaded file is not an APK archive" }
        check(partial.renameTo(target)); return target
    }

    private fun readLimited(connection: HttpURLConnection, limit: Int): String {
        check(connection.contentLengthLong <= limit || connection.contentLengthLong == -1L)
        connection.inputStream.use { input -> val bytes = ByteArrayOutputStream(); val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var total = 0
            while (true) { val count = input.read(buffer); if (count < 0) break; total += count; check(total <= limit); bytes.write(buffer, 0, count) }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }

    private inline fun <T> withConnection(url: String, metadata: Boolean, block: (HttpURLConnection) -> T): T {
        val connection = open(url, metadata); return try { block(connection) } finally { connection.disconnect() }
    }
    private fun open(initialUrl: String, metadata: Boolean): HttpURLConnection {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) {
            check(if (metadata) ReleaseUpdatePolicy.isTrustedMetadataUrl(url) else ReleaseUpdatePolicy.isTrustedRedirectUrl(url))
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000; setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("User-Agent", "android-tv-audio-reactive-updater") }
            if (connection.responseCode !in 300..399) return connection
            val location = connection.getHeaderField("Location") ?: error("Redirect without location"); connection.disconnect(); url = URI(url).resolve(location).toString()
        }
        error("Too many redirects")
    }
    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun openPackageInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun publish(message: String, busy: Boolean) = deliver { onStatus(message, busy) }
    private fun deliver(block: () -> Unit) { if (!closed.get()) context.mainExecutor.execute { if (!closed.get()) block() } }
    override fun close() { closed.set(true); selectedRelease = null; downloadedApk?.delete(); downloadedApk = null; executor.shutdownNow() }
    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_RELEASE_PAGES = 10
        const val RELEASES_PER_PAGE = 100
        const val MAX_APK_BYTES = 250L * 1024 * 1024
    }
}
