import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.HexFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

abstract class DownloadSpeechFile : DefaultTask() {
    @get:Input abstract val sourceUrl: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val destination: RegularFileProperty

    @TaskAction
    fun download() {
        val file = destination.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        file.parentFile.mkdirs()
        val pending = Files.createTempFile(file.parentFile.toPath(), file.name, ".part")
        try {
            val connection = URI(sourceUrl.get()).toURL().openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 60_000
            }
            connection.getInputStream().use { input ->
                Files.newOutputStream(pending).use { output ->
                    DigestInputStream(input, digest).copyTo(output)
                }
            }
            require(HexFormat.of().formatHex(digest.digest()) == sha256.get()) {
                "The offline speech dependency failed its SHA-256 check."
            }
            Files.move(pending, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending)
        }
    }
}

val speechRuntime = tasks.register<DownloadSpeechFile>("downloadOfflineSpeechRuntime") {
    sourceUrl.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-2.0.3.aar")
    sha256.set("4c766e2e74c9d33d595338c21fe3b305c73ba45d47e96a235675c33142a2ec50")
    destination.set(layout.buildDirectory.file("offline-speech/sherpa-onnx-2.0.3.aar"))
}

val speechModel = tasks.register<DownloadSpeechFile>("downloadOfflineSpeechModel") {
    sourceUrl.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2")
    sha256.set("c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1")
    destination.set(layout.buildDirectory.file("offline-speech/sherpa-onnx-whisper-tiny.tar.bz2"))
}

abstract class PrepareSpeechAssets : Sync() {
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
}

val prepareSpeechAssets = tasks.register<PrepareSpeechAssets>("prepareOfflineSpeechAssets") {
    dependsOn(speechModel)
    from(tarTree(resources.bzip2(speechModel.flatMap { it.destination }))) {
        include("*/tiny-encoder.int8.onnx", "*/tiny-decoder.int8.onnx", "*/tiny-tokens.txt")
    }
    assetsDirectory.set(layout.buildDirectory.dir("generated/offline-speech"))
    into(assetsDirectory)
}

val releaseSigningInputs = listOf(
    "SB_RELEASE_KEYSTORE", "SB_RELEASE_STORE_PASSWORD", "SB_RELEASE_KEY_ALIAS", "SB_RELEASE_KEY_PASSWORD"
).associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseSigningInputs.values.any { !it.isNullOrBlank() }
if (hasReleaseSigning) {
    require(releaseSigningInputs.values.all { !it.isNullOrBlank() }) {
        "Provide all four SB_RELEASE_* settings to sign a release."
    }
}

val releaseRepository = providers.gradleProperty("secondBrainReleaseRepository")
    .orElse(providers.environmentVariable("SB_RELEASE_REPOSITORY"))
    .getOrElse("LeoU29935/second-brain-releases").trim()
require(releaseRepository.isEmpty() ||
    (releaseRepository.matches(Regex("[A-Za-z0-9_-]{1,100}/[A-Za-z0-9_.-]{1,100}")) &&
        releaseRepository.substringAfter('/') !in setOf(".", ".."))) {
    "Set secondBrainReleaseRepository to a public GitHub owner/name."
}

val releaseVersion = providers.gradleProperty("secondBrainVersionName").orNull
val releaseCode = providers.gradleProperty("secondBrainVersionCode").orNull
require((releaseVersion == null) == (releaseCode == null)) {
    "Set secondBrainVersionName and secondBrainVersionCode together."
}
val appVersionName = releaseVersion ?: "3.1.2"
val appVersionCode = (releaseCode ?: "22").toInt()
require(appVersionName.matches(Regex("(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*)){1,3}")) &&
    when (appVersionName) {
        "0.3" -> appVersionCode == 3
        "0.3.1" -> appVersionCode == 4
        "0.4" -> appVersionCode == 5
        "0.4.1" -> appVersionCode == 6
        "0.4.2" -> appVersionCode == 7
        "0.4.2.1" -> appVersionCode == 8
        "0.4.3" -> appVersionCode == 9
        "0.4.5" -> appVersionCode == 10
        "0.4.6" -> appVersionCode == 11
        "0.4.7" -> appVersionCode == 12
        "0.4.8" -> appVersionCode == 13
        "0.4.8.1" -> appVersionCode == 14
        "0.4.8.2" -> appVersionCode == 15
        "0.4.8.3" -> appVersionCode == 16
        "0.4.8.4" -> appVersionCode == 17
        "3.0" -> appVersionCode == 18
        "3.0.1" -> appVersionCode == 19
        "3.1" -> appVersionCode == 20
        "3.1.1" -> appVersionCode == 21
        "3.1.2" -> appVersionCode == 22
        else -> appVersionCode in 9..2_100_000_000
    }) {
    "V0.3 through V0.4.2.1 use version codes 3 through 8. Later releases use increasing version codes."
}

android {
    namespace = "com.leo.secondbrain"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.leo.secondbrain"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "RELEASE_REPOSITORY", "\"$releaseRepository\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val productionSigning = if (hasReleaseSigning) signingConfigs.create("production") {
        storeType = "PKCS12"
        storeFile = file(requireNotNull(releaseSigningInputs["SB_RELEASE_KEYSTORE"]))
        storePassword = releaseSigningInputs["SB_RELEASE_STORE_PASSWORD"]
        keyAlias = releaseSigningInputs["SB_RELEASE_KEY_ALIAS"]
        keyPassword = releaseSigningInputs["SB_RELEASE_KEY_PASSWORD"]
    } else null

    buildTypes {
        release {
            signingConfig = productionSigning
            optimization {
                enable = true
            }
        }
        create("playRelease") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            buildConfigField("String", "RELEASE_REPOSITORY", "\"\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging.jniLibs.useLegacyPackaging = true
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareSpeechAssets, PrepareSpeechAssets::assetsDirectory)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(files(speechRuntime.flatMap { it.destination }).builtBy(speechRuntime))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.window)
    implementation(libs.okhttp)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
