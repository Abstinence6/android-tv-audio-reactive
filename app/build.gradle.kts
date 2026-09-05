plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseTagPattern = Regex("^v(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})$")
val releaseVersion = System.getenv("GITHUB_REF_NAME")
    ?.let(releaseTagPattern::matchEntire)
    ?.destructured
    ?.let { (major, minor, patch) ->
        val majorNumber = major.toInt()
        val minorNumber = minor.toInt()
        val patchNumber = patch.toInt()
        majorNumber * 1_000_000 + minorNumber * 1_000 + patchNumber to "$major.$minor.$patch"
    }
val releaseSigningEnvironment = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningEnvironment.associateWith { System.getenv(it)?.takeIf(String::isNotBlank) }
val hasReleaseSigning = releaseSigningValues.values.all { it != null }
val releaseCertificateFingerprint = System.getenv("RELEASE_CERT_SHA256")
    ?.replace(":", "")
    ?.lowercase()
    ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

android {
    namespace = "org.hyperion.audioreactive"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.hyperion.audioreactive"
        minSdk = 29
        targetSdk = 36
        versionCode = releaseVersion?.first ?: 2
        versionName = releaseVersion?.second ?: "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Debug builds are deliberately unable to install from the signed release channel.
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"\"")
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningValues.getValue("RELEASE_STORE_FILE")))
                storePassword = requireNotNull(releaseSigningValues.getValue("RELEASE_STORE_PASSWORD"))
                keyAlias = requireNotNull(releaseSigningValues.getValue("RELEASE_KEY_ALIAS"))
                keyPassword = requireNotNull(releaseSigningValues.getValue("RELEASE_KEY_PASSWORD"))
            }
        }
    }
    buildTypes {
        getByName("release") {
            // This value is set only on signed release variants after CI validates the secret.
            buildConfigField("String", "RELEASE_CERT_SHA256", "\"${releaseCertificateFingerprint.orEmpty()}\"")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources { excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties") } }
}

val validateReleaseInputs by tasks.registering {
    group = "verification"
    description = "Validates the release tag and signing inputs before producing release artifacts."
    doLast {
        check(releaseVersion != null) {
            "Release builds require GITHUB_REF_NAME to be a semantic version tag in the form vMAJOR.MINOR.PATCH, with each component from 0 to 999."
        }
        val missing = releaseSigningValues.filterValues { it == null }.keys
        check(missing.isEmpty()) {
            "Release signing requires non-empty CI environment values: ${releaseSigningEnvironment.joinToString(", ")}. Missing: ${missing.joinToString(", ")}."
        }
        check(file(requireNotNull(releaseSigningValues.getValue("RELEASE_STORE_FILE"))).isFile) {
            "RELEASE_STORE_FILE does not point to a readable keystore file."
        }
        check(releaseCertificateFingerprint != null) {
            "RELEASE_CERT_SHA256 must be a SHA-256 certificate fingerprint (64 hexadecimal characters, optional colons)."
        }
    }
}

tasks.configureEach {
    val producesReleaseArtifact = name == "preReleaseBuild" || name == "assembleRelease" ||
        (name.contains("Release") && listOf("bundle", "makeApkFromBundle", "package", "sign", "zipApksFor").any(name::startsWith))
    if (producesReleaseArtifact) {
        dependsOn(validateReleaseInputs)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.leanback:leanback:1.2.0-alpha04")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    testImplementation("junit:junit:4.13.2")
}
