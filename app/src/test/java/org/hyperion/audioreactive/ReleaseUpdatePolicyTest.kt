package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdatePolicyTest {
    @Test fun enforcesCanonicalBoundedTagsAndVersionCodeMapping() {
        assertEquals(ReleaseUpdatePolicy.Version(0, 0, 0), ReleaseUpdatePolicy.parseTag("v0.0.0"))
        assertEquals(ReleaseUpdatePolicy.Version(999, 999, 999), ReleaseUpdatePolicy.parseTag("v999.999.999"))
        assertEquals(999_999_999, ReleaseUpdatePolicy.parseTag("v999.999.999")!!.versionCode)
        assertNull(ReleaseUpdatePolicy.parseTag("v1000.0.0"))
        assertNull(ReleaseUpdatePolicy.parseTag("v01.2.3"))
        assertNull(ReleaseUpdatePolicy.parseTag("v1.2.3-rc1"))
    }

    @Test fun comparesStableVersionsNumerically() {
        assertTrue(ReleaseUpdatePolicy.isNewer("v0.10.0", "0.2.0"))
        assertFalse(ReleaseUpdatePolicy.isNewer("v0.2.0", "0.2.0"))
        assertFalse(ReleaseUpdatePolicy.isNewer("v1000.0.0", "0.2.0"))
    }

    @Test fun selectsHighestNewerSemVerAcrossFlattenedReleasePages() {
        val pages = listOf(
            listOf("v0.10.0", "v0.2.1", "not-a-release", "v0.2.0"),
            listOf("v1.0.0", "v0.999.999", "v1000.0.0"),
            listOf("v0.11.0", "v1.0.0"),
        )

        assertEquals("v1.0.0", ReleaseUpdatePolicy.highestNewer(pages.flatten(), "0.2.0") { it })
        assertNull(ReleaseUpdatePolicy.highestNewer(pages.flatten(), "1.0.0") { it })
    }

    @Test fun selectsOnlyExactSignedReleaseAssetsAndSidecars() {
        val tag = "v0.2.1"
        assertEquals("android-tv-audio-reactive-v0.2.1-release.apk", ReleaseUpdatePolicy.expectedAssetName(tag))
        assertEquals("android-tv-audio-reactive-v0.2.1-release.apk.sha256", ReleaseUpdatePolicy.expectedChecksumName(tag))
        assertTrue(ReleaseUpdatePolicy.isExpectedAsset("android-tv-audio-reactive-v0.2.1-release.apk", tag))
        assertFalse(ReleaseUpdatePolicy.isExpectedAsset("android-tv-audio-reactive-v0.2.1-debug.apk", tag))
        assertTrue(ReleaseUpdatePolicy.isExpectedDownloadUrl("https://github.com/Abstinence6/android-tv-audio-reactive/releases/download/v0.2.1/android-tv-audio-reactive-v0.2.1-release.apk", tag, false))
        assertFalse(ReleaseUpdatePolicy.isExpectedDownloadUrl("https://github.com/Abstinence6/android-tv-audio-reactive/releases/download/v0.2.2/android-tv-audio-reactive-v0.2.1-release.apk", tag, false))
    }

    @Test fun permitsOnlyPinnedMetadataAndStrictHttpsRedirectHosts() {
        assertTrue(ReleaseUpdatePolicy.isTrustedMetadataUrl(ReleaseUpdatePolicy.RELEASES_URL))
        assertTrue(ReleaseUpdatePolicy.isTrustedMetadataUrl("https://api.github.com/repos/Abstinence6/android-tv-audio-reactive/releases?per_page=100&page=2"))
        assertFalse(ReleaseUpdatePolicy.isTrustedMetadataUrl("https://api.github.com/repos/Abstinence6/android-tv-audio-reactive/releases?page=2&per_page=100"))
        assertFalse(ReleaseUpdatePolicy.isTrustedMetadataUrl("https://api.github.com/repos/other/android-tv-audio-reactive/releases?per_page=100"))
        assertTrue(ReleaseUpdatePolicy.isTrustedRedirectUrl("https://github.com/Abstinence6/android-tv-audio-reactive/releases/download/v0.2.1/a.apk"))
        assertTrue(ReleaseUpdatePolicy.isTrustedRedirectUrl("https://objects.githubusercontent.com/file"))
        assertFalse(ReleaseUpdatePolicy.isTrustedRedirectUrl("http://github.com/file"))
        assertFalse(ReleaseUpdatePolicy.isTrustedRedirectUrl("https://github.com.evil.example/file"))
    }

    @Test fun checksumSidecarMustBindTheExactAsset() {
        val asset = "android-tv-audio-reactive-v0.2.1-release.apk"
        val hash = "a".repeat(64)
        assertEquals(hash, ReleaseUpdatePolicy.sha256FromSidecar("$hash  $asset\n", asset))
        assertNull(ReleaseUpdatePolicy.sha256FromSidecar("$hash  other.apk", asset))
        assertNull(ReleaseUpdatePolicy.sha256FromSidecar("not-a-hash  $asset", asset))
    }
}
